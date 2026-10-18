package com.railradius.service;

import com.railradius.util.GtfsTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * Finds every station reachable from a start with at most a number of transfers, on the flat {@link TimetableStore}
 * (S18): one round per transfer count, rides are labels in a {@link LabelArena}, per-trip and per-station state are
 * arrays, and all of it is reused from search to search ({@link SearchScratch}). It follows the rounds and processing
 * order of ARCHITECTURE.md → Search semantics contract. Its result is a {@link CompactResult}: the labels of the
 * chosen paths, which every trim of the arena keeps, copied out at the end.
 * <p>
 * Stations choose what to board in parallel and the boarded trips are ridden afterwards in station order, so the
 * result is the same as on one thread. Choosing paths needs the exact days of candidates ({@link LabelDays}), worked
 * out in parallel too.
 * <p>
 * A search that runs past its deadline stops (S33): a backstop for parameters the rate limit prices too low, since it
 * holds a {@link SearchGate} permit and its memory all the while.
 */
@Component
@Slf4j
public class CompactReachabilitySearch {

    private final TransitGraph graph;
    private final long deadlineNanos;
    // One per search running at once. A scratch fits one store, so after a reload the old ones are dropped
    private final Queue<SearchScratch> idleScratches = new ConcurrentLinkedQueue<>();

    public CompactReachabilitySearch(TransitGraph graph,
                                     @Value("${railradius.search.deadline-ms:30000}") long deadlineMs) {
        this.graph = graph;
        // Saturates, so Long.MAX_VALUE means no deadline
        this.deadlineNanos = TimeUnit.MILLISECONDS.toNanos(deadlineMs);
    }

    /** Thrown when a search runs past its deadline; the message is safe to show to the client. */
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public static class DeadlineExceededException extends RuntimeException {
        DeadlineExceededException() {
            super("This search took too long and was stopped, please try fewer changes or a shorter wait");
        }
    }

    /** One running search: what it searches, its scratch and when it started. */
    private record Run(TimetableStore store, SearchScratch scratch, String startId, int startIndex, int maxTransfers,
                       long[] patternDays, CompactTransferRound.Parameters parameters, SearchCounters counters,
                       long startNanos) {}

    /**
     * Per station: the fastest path for each transfer count that is faster than every path with fewer transfers,
     * ordered by ascending transfers. So the first path has the fewest transfers and the last one is the fastest.
     * The start station has an empty path. After each round, roundPaths gets the path that round added to each
     * station it added one to (never the start station).
     *
     * @throws DeadlineExceededException if the search runs past its deadline; it returns nothing then
     */
    public CompactResult search(String startId, int maxTransfers, int minTransferMins, int maxTransferMins,
                                Consumer<Map<String, CompactResult.PathSummary>> roundPaths) {
        if (maxTransfers > Byte.MAX_VALUE) throw new IllegalArgumentException("Too many transfers: " + maxTransfers);
        TimetableStore store = graph.timetableStore();
        SearchScratch scratch = acquire(store);
        try {
            long[] patternDays = store.dayPatterns().weekPeriodMasks(graph.todayEpochDay());
            Run run = new Run(store, scratch, startId, store.stations().indexOf(startId).orElse(LabelArena.NONE),
                    maxTransfers, patternDays,
                    new CompactTransferRound.Parameters(patternDays, minTransferMins * GtfsTime.SECONDS_PER_MINUTE,
                            maxTransferMins * GtfsTime.SECONDS_PER_MINUTE),
                    new SearchCounters(), System.nanoTime());
            CompactResult result = search(run, roundPaths);
            idleScratches.offer(scratch);
            return result;
        } catch (RuntimeException e) {
            // A stopped search leaves its scratch fit for the next one. An Error such as OutOfMemoryError can strike
            // while the arena grows its columns one by one, so that scratch is dropped (B38)
            idleScratches.offer(scratch);
            throw e;
        }
    }

    private CompactResult search(Run run, Consumer<Map<String, CompactResult.PathSummary>> roundPaths) {
        long startNanos = run.startNanos();
        SearchScratch scratch = run.scratch();
        SearchCounters counters = run.counters();
        scratch.startSearch();
        rideFromStart(run);
        finishRound(run, 0, roundPaths);
        logRound(0, counters.stopArrivals, run, startNanos);
        for (int transfers = 1; transfers <= run.maxTransfers() && scratch.rides().size() > 0; transfers++) {
            long roundStartNanos = System.nanoTime();
            long arrivalsBefore = counters.stopArrivals;
            checkDeadline(run, transfers);
            transferRound(run, transfers);
            finishRound(run, transfers, roundPaths);
            logRound(transfers, counters.stopArrivals - arrivalsBefore, run, roundStartNanos);
        }
        CompactResult result = result(run);

        log.info("  Search stats (compact): {} stop arrivals on {} trips, {} stations; {} ms, of which choosing paths "
                        + "{} ms; {} departures scanned, {} boarded, {} fallback scans over {} arrivals; scratch {} "
                        + "(labels {}), result {}",
                counters.stopArrivals, scratch.progress().reachedTripCount(),
                scratch.candidates().reachedCount() + 1, millisSince(startNanos),
                TimeUnit.NANOSECONDS.toMillis(counters.pathsNanos), counters.departuresScanned, counters.boardings,
                counters.fallbackScans, counters.fallbackScannedArrivals,
                String.format("%.1f MB", scratch.sizeBytes() / 1e6),
                String.format("%.1f MB", scratch.labels().sizeBytes() / 1e6),
                String.format("%.2f MB", result.sizeBytes() / 1e6));
        return result;
    }

    /** The chosen paths with the rides before them, trimmed out of the arena. */
    private CompactResult result(Run run) {
        SearchScratch scratch = run.scratch();
        trimArena(scratch, labels -> {});
        return new CompactResult(run.store(), graph, run.startId(), scratch.labels().resultLabels(),
                scratch.chosen().byStation(run.store().stations().size()), run.maxTransfers());
    }

    /**
     * Drops every label of the arena that keep doesn't mark, that no chosen path ends with, and that is no ride
     * before one of those. The chosen paths move to their new labels.
     */
    private static void trimArena(SearchScratch scratch, Consumer<LabelArena> keep) {
        LabelArena labels = scratch.labels();
        labels.startTrim();
        scratch.chosen().keep(labels);
        keep.accept(labels);
        labels.trim();
        scratch.chosen().remap(labels);
    }

    private SearchScratch acquire(TimetableStore store) {
        for (SearchScratch idle = idleScratches.poll(); idle != null; idle = idleScratches.poll()) {
            if (idle.store() == store) return idle;
        }
        return SearchScratch.of(store);
    }

    /** Round 0: every trip leaving the start, on its days from today on. */
    private void rideFromStart(Run run) {
        if (run.startIndex() == LabelArena.NONE) return;
        TimetableStore store = run.store();
        int departuresEnd = store.departuresEnd(run.startIndex());
        for (int position = store.departuresStart(run.startIndex()); position < departuresEnd; position++) {
            int event = store.departureEventAt(position);
            int trip = store.tripOf(event);
            long days = run.patternDays()[store.dayPattern(trip)];
            if (days != 0) ride(run, trip, event - store.firstEvent(trip), 0, 0, LabelArena.NONE, days);
        }
    }

    /**
     * Boards each station's departures from the arrivals of the previous round's rides and rides them, in station
     * order. Before riding, the arena drops every label that no arrival boarded from leads back to and no chosen path
     * ends with: no later round reads them. Then the arrivals boarded from get labels.
     */
    private void transferRound(Run run, int transfers) {
        TimetableStore store = run.store();
        SearchCounters counters = run.counters();
        CompactTransferRound round = run.scratch().transferRound();
        List<CompactTransferRound.StationBoardings> stations = round.boardings(run.startIndex(), run.parameters());
        trimArena(run.scratch(), arena -> round.keepBoardedFrom(stations, arena));
        round.labelBoardedFrom(stations);
        run.scratch().rides().clear();
        for (CompactTransferRound.StationBoardings station : stations) {
            // Riding takes most of a round, so a round that runs far over stops here too
            checkDeadline(run, transfers);
            counters.departuresScanned += station.departureCount;
            counters.boardings += station.count;
            counters.fallbackScans += station.fallbackScans;
            counters.fallbackScannedArrivals += station.fallbackScannedArrivals;
            for (int i = 0; i < station.count; i++) {
                int event = station.departureEvent(i);
                int trip = store.tripOf(event);
                ride(run, trip, event - store.firstEvent(trip), transfers, station.boardMinutes[i], station.from[i],
                        round.boardDays(station, i, trip, run.patternDays()));
            }
            station.release();
        }
    }

    private void checkDeadline(Run run, int transfers) {
        long elapsedNanos = System.nanoTime() - run.startNanos();
        if (elapsedNanos < deadlineNanos) return;
        CompactTransferRound.Parameters parameters = run.parameters();
        log.warn("  Search {} x{} (waits {}-{} min) stopped in round {} after {} ms, over its {} ms deadline",
                run.startId(), run.maxTransfers(), parameters.minWaitSeconds() / GtfsTime.SECONDS_PER_MINUTE,
                parameters.maxWaitSeconds() / GtfsTime.SECONDS_PER_MINUTE, transfers,
                TimeUnit.NANOSECONDS.toMillis(elapsedNanos), TimeUnit.NANOSECONDS.toMillis(deadlineNanos));
        throw new DeadlineExceededException();
    }

    /**
     * Rides the trip from boardIdx on, recording each stop until an earlier ride was there no slower on all its days,
     * or the trip leaves a stop at a time that runs on too few days ({@link TimetableStore#isRegularDeparture}). The
     * duration adds each leg's run time and the dwell before it, both clamped at 0. Only the arrivals that become
     * candidates get a label here; the next round works out the others from the ride.
     */
    private void ride(Run run, int trip, int boardIdx, int transfers, int boardDurationMinutes, int previous,
                      long days) {
        TimetableStore store = run.store();
        SearchScratch scratch = run.scratch();
        int firstEvent = store.firstEvent(trip);
        int endEvent = store.endEvent(trip);
        int boardEvent = firstEvent + boardIdx;
        int slotBase = scratch.progress().reach(trip, firstEvent, endEvent);
        int durationMinutes = boardDurationMinutes;
        int event = boardEvent + 1;
        for (; event < endEvent; event++) {
            int legEvent = event - 1;
            if (!store.hasLeg(legEvent) || !store.isRegularDeparture(legEvent)) break;
            durationMinutes += store.rideLegMinutes(boardEvent, legEvent);
            if (scratch.progress().reachedNoSlower(slotBase + event, durationMinutes, days)) break;
            scratch.progress().record(slotBase + event, durationMinutes, days);
            int station = store.station(event);
            if (station == run.startIndex()) continue;

            run.counters().stopArrivals++;
            StationCandidatesFlat candidates = scratch.candidates();
            if (candidates.enter(station, transfers)) scratch.roundStations().add(station);
            if (!candidates.accepts(station, durationMinutes)) continue;
            int label = scratch.labels().add(trip, boardIdx, event - firstEvent, durationMinutes, previous, days);
            candidates.add(station, label, scratch.labels());
        }
        int recordedStops = event - boardEvent - 1;
        // Nothing boards from the last round's arrivals
        if (recordedStops > 0 && transfers < run.maxTransfers()) {
            scratch.rides().add(boardEvent, previous, boardDurationMinutes, days, recordedStops);
        }
    }

    /**
     * Chooses the path with this many transfers of each station the round reached, among its candidates. A path that
     * is not faster than the chosen one with fewer transfers is left out: choosing a slower candidate can make it so.
     */
    private void finishRound(Run run, int transfers, Consumer<Map<String, CompactResult.PathSummary>> roundPaths) {
        long startNanos = System.nanoTime();
        SearchScratch scratch = run.scratch();
        IntList stations = scratch.roundStations();
        requestCandidateDays(scratch);
        scratch.days().resolve(run.store(), graph);
        // Choosing only reads the round's candidates and earlier paths, so stations do it in parallel
        int[] newPaths = IntStream.range(0, stations.size()).parallel()
                .map(i -> newPath(run, stations.get(i)))
                .toArray();
        Map<String, CompactResult.PathSummary> added = new HashMap<>();
        for (int i = 0; i < stations.size(); i++) {
            int station = stations.get(i);
            int label = newPaths[i];
            if (label == LabelArena.NONE) continue;
            int minutes = scratch.labels().durationMinutes(label);
            scratch.pathMinutes()[station] = minutes;
            scratch.chosen().add(station, label);
            added.put(run.store().stations().id(station), new CompactResult.PathSummary(transfers, minutes));
        }
        stations.clear();
        scratch.candidates().endRound();
        run.counters().pathsNanos += System.nanoTime() - startNanos;
        if (!added.isEmpty()) roundPaths.accept(added);
    }

    /** The chooser compares the days of candidates only where a station has more than one. */
    private static void requestCandidateDays(SearchScratch scratch) {
        StationCandidatesFlat candidates = scratch.candidates();
        IntList stations = scratch.roundStations();
        for (int i = 0; i < stations.size(); i++) {
            int station = stations.get(i);
            if (candidates.count(station) < 2) continue;
            for (int slot = candidates.first(station); slot != StationCandidatesFlat.NONE;
                 slot = candidates.next(slot)) {
                scratch.days().request(candidates.label(slot));
            }
        }
    }

    /** The label of the station's chosen path with the round's transfers; NONE if it has none or it is not faster. */
    private static int newPath(Run run, int station) {
        SearchScratch scratch = run.scratch();
        StationCandidatesFlat candidates = scratch.candidates();
        List<Integer> rides = new ArrayList<>();
        for (int slot = candidates.first(station); slot != StationCandidatesFlat.NONE; slot = candidates.next(slot)) {
            rides.add(candidates.label(slot));
        }
        if (rides.isEmpty()) return LabelArena.NONE;
        LabelArena labels = scratch.labels();
        int chosen = RegularJourneyChooser.choose(rides, scratch.days()::days, labels::durationMinutes);
        return labels.durationMinutes(chosen) < scratch.pathMinutes()[station] ? chosen : LabelArena.NONE;
    }

    // Counters are cumulative, so a round's share is the difference to the previous log line
    private static void logRound(int transfers, long arrivalCount, Run run, long startNanos) {
        SearchCounters counters = run.counters();
        log.debug("  Round {} (compact): {} stop arrivals, {} stations, {} ms; so far {} departures scanned, "
                        + "{} fallback scans over {} arrivals", transfers, arrivalCount,
                run.scratch().candidates().reachedCount(), millisSince(startNanos), counters.departuresScanned,
                counters.fallbackScans, counters.fallbackScannedArrivals);
    }

    private static long millisSince(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
