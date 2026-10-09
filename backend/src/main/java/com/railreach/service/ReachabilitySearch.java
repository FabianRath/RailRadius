package com.railreach.service;

import com.railreach.model.Connection;
import com.railreach.model.JourneyPath;
import com.railreach.util.GtfsTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * Transfer-bounded search over the {@link TransitGraph}, one round per transfer (like RAPTOR, but without a start
 * time: every trip leaving the start station can begin a journey).
 * <p>
 * Pruning is per trip, not per station: for each stop of a trip it remembers the shortest duration that reached it
 * there. A ride that cannot beat that is cut off if earlier rides there also ran on all its days, because they
 * arrived at the same time with no more transfers, and stay ahead for the rest of the trip. Keeping only the fastest
 * path per station would drop slower arrivals that catch a train the fastest one misses (B2), and ignoring the days
 * would drop a slower ride that runs on the days a later train needs (B33).
 * <p>
 * There is no travel date, but a journey only counts its first train's days from today on (B31), and a transfer
 * needs a day on which every train of the journey so far runs together with the next one, that one a day later for
 * a wait past midnight (B25). Checking only consecutive trains let three trains A, B, C pass if A and B shared one
 * day and B and C another (B33). Days are compared by weekday and 6-week period ({@link WeekPeriodDays}), not by
 * date: exact dates made the check too costly. The shown running days are exact dates ({@link JourneyRunningDays}),
 * so a found journey can show none.
 * <p>
 * A station's result is not always the fastest journey: one up to {@link StationCandidates#MAX_SLOWER_MINUTES}
 * slower is shown instead if it runs on many more days ({@link RegularJourneyChooser}). Transfers still continue from
 * the fastest arrival.
 * <p>
 * A station's path with k transfers is chosen as soon as round k ends: later rounds only add rides with more
 * transfers. So each round's new paths can be handed out before the search goes on.
 * <p>
 * Within a round, stations choose what to board in parallel: that only reads what earlier rounds wrote. Riding the
 * boarded trips writes the shared per-trip state, so it runs afterwards in station order, and the result is the
 * same as on one thread. The work is CPU-bound, so it uses the common fork-join pool (one thread per core), not the
 * virtual threads Spring runs requests on.
 */
@Slf4j
@RequiredArgsConstructor
public class ReachabilitySearch implements SearchEngine {

    private static final int SAME_DAY = 0;
    private static final int NEXT_DAY = 1;

    private final TransitGraph transitGraph;

    /** A departure boarded from an arrival: the days of the departing trip it gives, and the journey time so far. */
    private record Boarding(TripTimetable.Departure departure, Ride from, long days, int boardMinutes) {}

    /** What one station boards in a round, in departure order, with the windows that hold its fallback counts. */
    private record StationBoardings(List<Boarding> boardings, int departureCount, TransferWindow sameDay,
                                    TransferWindow overnight) {}

    /** The previous round's arrivals at one station, sorted by arrival time, with their transfer windows. */
    private record StationArrivals(List<Ride> rides, TransferWindow sameDay, TransferWindow overnight) {

        Ride at(int index) {
            return index >= 0 ? rides.get(index) : null;
        }
    }

    /**
     * tripDays are the trips' {@link WeekPeriodDays} and progressByTrip their progress, both by
     * {@link TripTimetable#index()}; progress is null for a trip no ride reached yet. roundStations holds the
     * stations that got a ride in the running round, paths the chosen paths so far.
     */
    private record SearchState(SearchTimetables timetables, String startId, int startIndex, int minTransferMins,
                               int maxTransferMins, long[] tripDays, TripProgress[] progressByTrip,
                               StationCandidates[] candidatesByStation, List<StationCandidates> roundStations,
                               Map<String, List<JourneyPath>> paths, Map<RideKey, JourneyRunningDays.SoFar> daysByRide,
                               SearchCounters counters) {}

    /**
     * A ride as a map key by identity: candidates share their earlier rides, and a Ride's record hash would walk its
     * whole previous chain.
     */
    private record RideKey(Ride ride) {

        @Override
        public boolean equals(Object other) {
            return other instanceof RideKey key && key.ride == ride;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(ride);
        }
    }

    @Override
    public void prepare() {
        transitGraph.searchTimetables();
    }

    @Override
    public SearchResult search(String startId, int maxTransfers, int minTransferMins, int maxTransferMins,
                               Consumer<Map<String, SearchResult.PathSummary>> roundPaths) {
        SearchCounters counters = new SearchCounters();
        SearchTimetables timetables = transitGraph.searchTimetables();
        SearchState state = new SearchState(timetables, startId, timetables.stationIndex(startId), minTransferMins,
                maxTransferMins, timetables.weekPeriodDays(transitGraph.todayEpochDay()),
                new TripProgress[timetables.tripCount()], new StationCandidates[timetables.stationCount()],
                new ArrayList<>(), new HashMap<>(), new ConcurrentHashMap<>(), counters);
        long startNanos = System.nanoTime();
        List<Ride> arrivals = new ArrayList<>();
        for (TripTimetable.Departure departure : timetables.departuresAt(startId)) {
            long days = state.tripDays()[departure.trip().index()];
            if (days != 0) ride(state, departure, 0, 0, null, days, arrivals);
        }
        long arrivalCount = arrivals.size();
        finishRound(state, 0, roundPaths);
        logRound(0, arrivals.size(), state, startNanos);
        for (int transfers = 1; transfers <= maxTransfers && !arrivals.isEmpty(); transfers++) {
            long roundStartNanos = System.nanoTime();
            arrivals = transferRound(state, arrivals, transfers);
            arrivalCount += arrivals.size();
            finishRound(state, transfers, roundPaths);
            logRound(transfers, arrivals.size(), state, roundStartNanos);
        }

        log.info("  Search stats: {} stop arrivals on {} trips, {} stations; {} ms, of which choosing paths {} ms; "
                        + "{} departures scanned, {} fallback scans over {} arrivals",
                arrivalCount, reachedTripCount(state), counters.reachedStations + 1, millisSince(startNanos),
                TimeUnit.NANOSECONDS.toMillis(counters.pathsNanos), counters.departuresScanned,
                counters.fallbackScans, counters.fallbackScannedArrivals);
        Map<String, List<JourneyPath>> result = state.paths();
        result.put(startId, List.of(JourneyPath.builder()
                .stationIds(List.of(startId))
                .connections(List.of())
                .transfers(0)
                .totalDurationMinutes(0)
                .build()));
        return new LegacySearchResult(result, transitGraph);
    }

    /**
     * Chooses the path with this many transfers of each station the round reached, among its candidates. A path that
     * is not faster than the chosen one with fewer transfers is left out: choosing a slower candidate can make it so.
     */
    private void finishRound(SearchState state, int transfers,
                             Consumer<Map<String, SearchResult.PathSummary>> roundPaths) {
        long startNanos = System.nanoTime();
        // Choosing only reads the round's candidates and earlier paths, so stations do it in parallel
        List<StationCandidates> stations = state.roundStations();
        List<Optional<JourneyPath>> newPaths = IntStream.range(0, stations.size()).parallel()
                .mapToObj(i -> newPath(state, stations.get(i), transfers))
                .toList();
        Map<String, SearchResult.PathSummary> added = new HashMap<>();
        for (int i = 0; i < stations.size(); i++) {
            String stationId = stations.get(i).stationId;
            newPaths.get(i).ifPresent(path -> {
                state.paths().computeIfAbsent(stationId, id -> new ArrayList<>()).add(path);
                added.put(stationId, LegacySearchResult.summary(path));
            });
        }
        stations.clear();
        state.counters().pathsNanos += System.nanoTime() - startNanos;
        if (!added.isEmpty()) roundPaths.accept(added);
    }

    /** The station's chosen path with this many transfers, unless it has none or it is not faster. */
    private Optional<JourneyPath> newPath(SearchState state, StationCandidates candidates, int transfers) {
        List<Ride> rides = candidates.rides.stream().filter(ride -> ride.transfers() == transfers).toList();
        if (rides.isEmpty()) return Optional.empty();
        Ride chosen = RegularJourneyChooser.choose(rides, ride -> runningDays(ride, state.daysByRide()).days(),
                Ride::durationMinutes);
        List<JourneyPath> paths = state.paths().get(candidates.stationId);
        boolean faster = paths == null || chosen.durationMinutes() < paths.getLast().getTotalDurationMinutes();
        return faster ? Optional.of(toJourneyPath(state.startId(), chosen)) : Optional.empty();
    }

    // Counters are cumulative, so a round's share is the difference to the previous log line
    private static void logRound(int transfers, int arrivalCount, SearchState state, long startNanos) {
        SearchCounters counters = state.counters();
        log.debug("  Round {}: {} stop arrivals, {} stations, {} ms; so far {} departures scanned, "
                        + "{} fallback scans over {} arrivals", transfers, arrivalCount, counters.reachedStations,
                millisSince(startNanos), counters.departuresScanned, counters.fallbackScans,
                counters.fallbackScannedArrivals);
    }

    private static long reachedTripCount(SearchState state) {
        return Arrays.stream(state.progressByTrip()).filter(Objects::nonNull).count();
    }

    private static long millisSince(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /** Boards each station's departures from the previous round's arrivals there; returns the new arrivals. */
    private List<Ride> transferRound(SearchState state, List<Ride> previousArrivals, int transfers) {
        List<List<Ride>> stations = arrivalsByStation(state, previousArrivals);
        List<StationBoardings> boardings = IntStream.range(0, stations.size()).parallel()
                .mapToObj(i -> boardingsAt(state, stations.get(i).getFirst().stationId(), stations.get(i)))
                .toList();
        List<Ride> arrivals = new ArrayList<>(previousArrivals.size());
        SearchCounters counters = state.counters();
        for (StationBoardings station : boardings) {
            counters.departuresScanned += station.departureCount();
            counters.addFallbacks(station.sameDay());
            counters.addFallbacks(station.overnight());
            for (Boarding boarding : station.boardings()) {
                ride(state, boarding.departure(), transfers, boarding.boardMinutes(), boarding.from(),
                        boarding.days(), arrivals);
            }
        }
        return arrivals;
    }

    /**
     * The arrivals no faster ride of their round overtook, per station in station index order, each in the order they
     * came. Checked before this round lowers any best durations, so only same-round overtaking drops an arrival.
     */
    private static List<List<Ride>> arrivalsByStation(SearchState state, List<Ride> previousArrivals) {
        int[] starts = new int[state.timetables().stationCount() + 1];
        List<Ride> kept = new ArrayList<>(previousArrivals.size());
        for (Ride arrival : previousArrivals) {
            if (isOvertaken(state, arrival)) continue;
            kept.add(arrival);
            starts[arrival.stationIndex() + 1]++;
        }
        for (int station = 1; station < starts.length; station++) {
            starts[station] += starts[station - 1];
        }
        Ride[] grouped = new Ride[kept.size()];
        int[] next = starts.clone();
        for (Ride arrival : kept) {
            grouped[next[arrival.stationIndex()]++] = arrival;
        }
        List<List<Ride>> stations = new ArrayList<>();
        for (int station = 0; station < starts.length - 1; station++) {
            if (starts[station + 1] > starts[station]) {
                stations.add(new ArrayList<>(Arrays.asList(grouped).subList(starts[station], starts[station + 1])));
            }
        }
        return stations;
    }

    /**
     * What every departure at the station boards: the arrivals that catch it with the shortest journey so far (see
     * {@link #board}). Waits past midnight get their own window, with the arrivals moved a day back. Only reads the
     * search state.
     */
    private static StationBoardings boardingsAt(SearchState state, String stationId, List<Ride> stationArrivals) {
        stationArrivals.sort(Comparator.comparingInt(Ride::arrivalSeconds));
        int[] arrivalSeconds = stationArrivals.stream().mapToInt(Ride::arrivalSeconds).toArray();
        int[] durationMinutes = stationArrivals.stream().mapToInt(Ride::durationMinutes).toArray();
        int minWaitSeconds = state.minTransferMins() * GtfsTime.SECONDS_PER_MINUTE;
        int maxWaitSeconds = state.maxTransferMins() * GtfsTime.SECONDS_PER_MINUTE;
        TransferWindow sameDay = new TransferWindow(arrivalSeconds, durationMinutes, 0,
                minWaitSeconds, maxWaitSeconds);
        TransferWindow overnight = new TransferWindow(arrivalSeconds, durationMinutes, -GtfsTime.SECONDS_PER_DAY,
                minWaitSeconds, Math.min(maxWaitSeconds, GtfsTime.MAX_OVERNIGHT_GAP_SECONDS));
        StationArrivals station = new StationArrivals(stationArrivals, sameDay, overnight);

        List<TripTimetable.Departure> departures = state.timetables().departuresAt(stationId);
        List<Boarding> boardings = new ArrayList<>();
        for (TripTimetable.Departure departure : departures) {
            board(state, departure, station, boardings);
        }
        return new StationBoardings(boardings, departures.size(), sameDay, overnight);
    }

    /**
     * Boards the departure from the arrival that catches it with the shortest journey so far, then from slower ones
     * only for days of the departing trip that no faster one runs on (B33). Any other arrival would ride the same
     * trip on the same days with as many transfers but longer, so it is not needed. Only arrivals whose journey runs
     * on a day that fits the departure's trip count.
     */
    private static void board(SearchState state, TripTimetable.Departure departure, StationArrivals station,
                              List<Boarding> boardings) {
        TripTimetable next = departure.trip();
        if (!state.timetables().regularDepartures(next)[departure.stopIdx()]) return;
        long nextDays = state.tripDays()[next.index()];
        long served = 0;
        while ((nextDays & ~served) != 0) {
            long servedSoFar = served;
            Ride sameDayFrom = station.at(station.sameDay().bestFor(departure.departureSeconds(),
                    i -> addsDays(station.rides().get(i), nextDays, SAME_DAY, servedSoFar)));
            Ride overnightFrom = station.at(station.overnight().bestFor(departure.departureSeconds(),
                    i -> addsDays(station.rides().get(i), nextDays, NEXT_DAY, servedSoFar)));
            Ride from = faster(departure, sameDayFrom, overnightFrom);
            if (from == null) return;
            long days = from.nextDays(nextDays, from == sameDayFrom ? SAME_DAY : NEXT_DAY);
            served |= days;
            // Timetables are shared per trip. Staying on this trip beats changing onto it from any arrival.
            if (from.trip() != next) {
                boardings.add(new Boarding(departure, from, days, boardingMinutes(from, departure)));
            }
        }
    }

    /** Whether the next trip, running extraDays after the arrival, runs on a day of the journey that served lacks. */
    private static boolean addsDays(Ride arrival, long nextDays, int extraDays, long served) {
        return (arrival.nextDays(nextDays, extraDays) & ~served) != 0;
    }

    private static Ride faster(TripTimetable.Departure departure, Ride sameDay, Ride overnight) {
        if (sameDay == null || overnight == null) return sameDay != null ? sameDay : overnight;
        return boardingMinutes(overnight, departure) < boardingMinutes(sameDay, departure) ? overnight : sameDay;
    }

    /** Journey time up to the departure, the wait included. */
    private static int boardingMinutes(Ride from, TripTimetable.Departure departure) {
        return from.durationMinutes() + GtfsTime.diffMinutes(from.arrivalSeconds(), departure.departureSeconds());
    }

    /** A faster ride of the same round on all its days reached the same stop of the same trip later on. */
    private static boolean isOvertaken(SearchState state, Ride arrival) {
        return state.progressByTrip()[arrival.trip().index()]
                .reachedFaster(arrival.alightIdx(), arrival.durationMinutes(), arrival.days());
    }

    /**
     * Rides the trip from the departure on, recording each stop until an earlier ride was there no slower on all its
     * days, or the trip leaves a stop at a time that runs on too few days ({@link RareTrainFilter#regularDepartures}).
     * days are the trip's days on which the journey works.
     */
    private void ride(SearchState state, TripTimetable.Departure departure, int transfers, int boardDurationMinutes,
                      Ride previous, long days, List<Ride> arrivals) {
        TripTimetable trip = departure.trip();
        TripProgress[] progressByTrip = state.progressByTrip();
        if (progressByTrip[trip.index()] == null) progressByTrip[trip.index()] = new TripProgress(trip.stopCount());
        TripProgress progress = progressByTrip[trip.index()];
        boolean[] regularDepartures = state.timetables().regularDepartures(trip);
        int boardIdx = departure.stopIdx();
        for (int alightIdx = boardIdx + 1; alightIdx < trip.stopCount() && trip.hasLeg(alightIdx - 1)
                && regularDepartures[alightIdx - 1]; alightIdx++) {
            int durationMinutes = boardDurationMinutes + trip.rideMinutes(boardIdx, alightIdx);
            if (progress.reachedNoSlower(alightIdx, durationMinutes, days)) return;
            progress.record(alightIdx, durationMinutes, days);
            if (trip.stationIndexes()[alightIdx] == state.startIndex()) continue;

            Ride ride = new Ride(trip, boardIdx, alightIdx, transfers, durationMinutes, previous, days);
            arrivals.add(ride);
            StationCandidates candidates = candidatesAt(state, ride);
            if (candidates.transfers != transfers) state.roundStations().add(candidates);
            candidates.offer(ride);
        }
    }

    private static StationCandidates candidatesAt(SearchState state, Ride ride) {
        StationCandidates candidates = state.candidatesByStation()[ride.stationIndex()];
        if (candidates == null) {
            candidates = new StationCandidates(ride.stationId());
            state.candidatesByStation()[ride.stationIndex()] = candidates;
            state.counters().reachedStations++;
        }
        return candidates;
    }

    /** Worked out once per ride (two threads may both work one out): a station's candidates share earlier rides. */
    private JourneyRunningDays.SoFar runningDays(Ride ride, Map<RideKey, JourneyRunningDays.SoFar> daysByRide) {
        JourneyRunningDays.SoFar known = daysByRide.get(new RideKey(ride));
        if (known != null) return known;
        JourneyRunningDays.SoFar before = ride.previous() != null
                ? runningDays(ride.previous(), daysByRide) : JourneyRunningDays.SoFar.START;
        JourneyRunningDays.SoFar soFar = JourneyRunningDays.withRide(before, ride.times(), transitGraph);
        daysByRide.put(new RideKey(ride), soFar);
        return soFar;
    }

    private JourneyPath toJourneyPath(String startId, Ride last) {
        List<Ride> rides = new ArrayList<>();
        for (Ride ride = last; ride != null; ride = ride.previous()) {
            rides.add(ride);
        }
        List<String> stationIds = new ArrayList<>(List.of(startId));
        List<Connection> connections = new ArrayList<>();
        for (Ride ride : rides.reversed()) {
            List<String> stops = ride.trip().stops();
            for (int i = ride.boardIdx(); i < ride.alightIdx(); i++) {
                connections.add(ride.trip().legConnections()[i]);
                stationIds.add(stops.get(i + 1));
            }
        }
        return JourneyPath.builder()
                .stationIds(stationIds)
                .connections(connections)
                .transfers(last.transfers())
                .totalDurationMinutes(last.durationMinutes())
                .build();
    }
}
