package com.railreach.service;

import com.railreach.util.GtfsTime;
import lombok.extern.slf4j.Slf4j;

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
 * The search of {@link ReachabilitySearch} on the flat {@link TimetableStore} (S18): rides are labels in a
 * {@link LabelArena}, per-trip and per-station state are arrays, and all of it is reused from search to search
 * ({@link SearchScratch}). It keeps the legacy search's rounds and processing order (ARCHITECTURE.md → Search
 * semantics contract), so both give the same result. Its result is a {@link CompactResult}: the labels of the chosen
 * paths, which every trim of the arena keeps, copied out at the end.
 * <p>
 * As in the legacy search, stations choose what to board in parallel and the boarded trips are ridden afterwards in
 * station order. Choosing paths needs the exact days of candidates ({@link LabelDays}), worked out in parallel too.
 */
@Slf4j
public class CompactReachabilitySearch implements SearchEngine {

    private final TransitGraph graph;
    // One per search running at once. A scratch fits one store, so after a reload the old ones are dropped
    private final Queue<SearchScratch> idleScratches = new ConcurrentLinkedQueue<>();

    CompactReachabilitySearch(TransitGraph graph) {
        this.graph = graph;
    }

    /** One running search: what it searches and its scratch. */
    private record Run(TimetableStore store, SearchScratch scratch, String startId, int startIndex, int maxTransfers,
                       long[] patternDays, CompactTransferRound.Parameters parameters, SearchCounters counters) {}

    @Override
    public void prepare() {
        graph.timetableStore();
    }

    @Override
    public SearchResult search(String startId, int maxTransfers, int minTransferMins, int maxTransferMins,
                               Consumer<Map<String, SearchResult.PathSummary>> roundPaths) {
        if (maxTransfers > Byte.MAX_VALUE) throw new IllegalArgumentException("Too many transfers: " + maxTransfers);
        TimetableStore store = graph.timetableStore();
        SearchScratch scratch = acquire(store);
        try {
            long[] patternDays = store.dayPatterns().weekPeriodMasks(graph.todayEpochDay());
            Run run = new Run(store, scratch, startId, store.stations().indexOf(startId).orElse(LabelArena.NONE),
                    maxTransfers, patternDays,
                    new CompactTransferRound.Parameters(patternDays, minTransferMins * GtfsTime.SECONDS_PER_MINUTE,
                            maxTransferMins * GtfsTime.SECONDS_PER_MINUTE),
                    new SearchCounters());
            return search(run, roundPaths);
        } finally {
            idleScratches.offer(scratch);
        }
    }

    private CompactResult search(Run run, Consumer<Map<String, SearchResult.PathSummary>> roundPaths) {
        long startNanos = System.nanoTime();
        SearchScratch scratch = run.scratch();
        SearchCounters counters = run.counters();
        scratch.startSearch();
        rideFromStart(run);
        int roundStart = 0;
        int roundEnd = scratch.labels().size();
        finishRound(run, 0, roundPaths);
        logRound(0, counters.stopArrivals, run, startNanos);
        for (int transfers = 1; transfers <= run.maxTransfers() && roundEnd > roundStart; transfers++) {
            long roundStartNanos = System.nanoTime();
            long arrivalsBefore = counters.stopArrivals;
            roundStart = transferRound(run, roundStart, roundEnd, transfers);
            roundEnd = scratch.labels().size();
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
     * Boards each station's departures from the previous round's arrivals there and rides them, in station order.
     * Before riding, the arena drops every label that nothing boards from, no later label rides on from and no chosen
     * path ends with: no later round reads them. Returns the first label of the round.
     */
    private int transferRound(Run run, int firstArrival, int endArrival, int transfers) {
        TimetableStore store = run.store();
        SearchCounters counters = run.counters();
        List<CompactTransferRound.StationBoardings> stations = run.scratch().transferRound()
                .boardings(firstArrival, endArrival, run.parameters());
        trimArena(run.scratch(), arena -> {
            for (CompactTransferRound.StationBoardings station : stations) {
                for (int i = 0; i < station.count; i++) {
                    arena.keepWithEarlierRides(station.fromLabels[i]);
                }
            }
        });
        LabelArena labels = run.scratch().labels();
        // Only reads the trim's index, so stations do it in parallel
        stations.parallelStream().forEach(station -> {
            for (int i = 0; i < station.count; i++) {
                station.fromLabels[i] = labels.trimmedIndex(station.fromLabels[i]);
            }
        });
        int roundStart = labels.size();
        for (CompactTransferRound.StationBoardings station : stations) {
            counters.departuresScanned += station.departureCount;
            counters.boardings += station.count;
            counters.fallbackScans += station.fallbackScans;
            counters.fallbackScannedArrivals += station.fallbackScannedArrivals;
            for (int i = 0; i < station.count; i++) {
                int trip = store.tripOf(station.departureEvents[i]);
                ride(run, trip, station.departureEvents[i] - store.firstEvent(trip), transfers,
                        station.boardMinutes[i], station.fromLabels[i], station.days[i]);
            }
        }
        return roundStart;
    }

    /**
     * Rides the trip from boardIdx on, recording each stop until an earlier ride was there no slower on all its days,
     * or the trip leaves a stop at a time that runs on too few days ({@link RareTrainFilter#regularDepartures}). The
     * duration adds each leg's run time and the dwell before it, both clamped at 0, like {@link TripTimetable}'s
     * offsets.
     */
    private void ride(Run run, int trip, int boardIdx, int transfers, int boardDurationMinutes, int previous,
                      long days) {
        TimetableStore store = run.store();
        SearchScratch scratch = run.scratch();
        int firstEvent = store.firstEvent(trip);
        int endEvent = store.endEvent(trip);
        scratch.progress().reach(trip, firstEvent, endEvent);
        int durationMinutes = boardDurationMinutes;
        for (int event = firstEvent + boardIdx + 1; event < endEvent; event++) {
            int legEvent = event - 1;
            if (!store.hasLeg(legEvent) || !store.isRegularDeparture(legEvent)) return;
            if (legEvent > firstEvent + boardIdx) {
                durationMinutes += Math.max(0, GtfsTime.diffMinutes(store.arrivalSeconds(legEvent),
                        store.departureSeconds(legEvent)));
            }
            durationMinutes += Math.max(0, GtfsTime.diffMinutes(store.departureSeconds(legEvent),
                    store.arrivalSeconds(event)));
            if (scratch.progress().reachedNoSlower(event, durationMinutes, days)) return;
            scratch.progress().record(event, durationMinutes, days);
            int station = store.station(event);
            if (station == run.startIndex()) continue;

            run.counters().stopArrivals++;
            StationCandidatesFlat candidates = scratch.candidates();
            if (candidates.enter(station, transfers)) scratch.roundStations().add(station);
            boolean candidate = candidates.accepts(station, durationMinutes);
            // Nothing boards from the last round's arrivals, so only its candidates need a label
            if (!candidate && transfers == run.maxTransfers()) continue;
            int label = scratch.labels().add(trip, boardIdx, event - firstEvent, transfers, durationMinutes,
                    previous, days);
            if (candidate) candidates.add(station, label, scratch.labels());
        }
    }

    /**
     * Chooses the path with this many transfers of each station the round reached, among its candidates. A path that
     * is not faster than the chosen one with fewer transfers is left out: choosing a slower candidate can make it so.
     */
    private void finishRound(Run run, int transfers, Consumer<Map<String, SearchResult.PathSummary>> roundPaths) {
        long startNanos = System.nanoTime();
        SearchScratch scratch = run.scratch();
        IntList stations = scratch.roundStations();
        requestCandidateDays(scratch);
        scratch.days().resolve(run.store(), graph);
        // Choosing only reads the round's candidates and earlier paths, so stations do it in parallel
        int[] newPaths = IntStream.range(0, stations.size()).parallel()
                .map(i -> newPath(run, stations.get(i)))
                .toArray();
        Map<String, SearchResult.PathSummary> added = new HashMap<>();
        for (int i = 0; i < stations.size(); i++) {
            int station = stations.get(i);
            int label = newPaths[i];
            if (label == LabelArena.NONE) continue;
            int minutes = scratch.labels().durationMinutes(label);
            scratch.pathMinutes()[station] = minutes;
            scratch.chosen().add(station, label);
            added.put(run.store().stations().id(station), new SearchResult.PathSummary(transfers, minutes));
        }
        stations.clear();
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
            for (int label = candidates.first(station); label != LabelArena.NONE; label = candidates.next(label)) {
                scratch.days().request(label);
            }
        }
    }

    /** The label of the station's chosen path with the round's transfers; NONE if it has none or it is not faster. */
    private static int newPath(Run run, int station) {
        SearchScratch scratch = run.scratch();
        StationCandidatesFlat candidates = scratch.candidates();
        List<Integer> rides = new ArrayList<>();
        for (int label = candidates.first(station); label != LabelArena.NONE; label = candidates.next(label)) {
            rides.add(label);
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
