package com.railreach.service;

import com.railreach.model.Connection;
import com.railreach.model.JourneyPath;
import com.railreach.util.GtfsTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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
 * A station's result is not always the fastest journey: one up to {@link #MAX_SLOWER_MINUTES} slower is shown
 * instead if it runs on many more days ({@link RegularJourneyChooser}). Transfers still continue from the fastest
 * arrival.
 * <p>
 * A station's path with k transfers is chosen as soon as round k ends: later rounds only add rides with more
 * transfers. So each round's new paths can be handed out before the search goes on.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ReachabilitySearch {

    private static final int SAME_DAY = 0;
    private static final int NEXT_DAY = 1;
    // A journey this much slower than the fastest can still be shown for a station, if it runs more often
    private static final int MAX_SLOWER_MINUTES = 30;

    private final TransitGraph transitGraph;

    /**
     * One ride from boardIdx to alightIdx; previous is the ride before the transfer, null for the first one. days are
     * the {@link WeekPeriodDays} of the trip on which the journey so far works: every earlier train runs on its day.
     */
    private record Ride(TripTimetable trip, int boardIdx, int alightIdx, int transfers, int durationMinutes,
                        Ride previous, long days) {

        /** The days of a next trip, running on nextTripDays, that come extraDays after a day of this ride. */
        long nextDays(long nextTripDays, int extraDays) {
            return nextTripDays & WeekPeriodDays.movedForward(days, extraDays);
        }

        String stationId() {
            return trip.stops().get(alightIdx);
        }

        int arrivalSeconds() {
            return trip.arrivalSeconds(alightIdx);
        }

        JourneyRunningDays.RideTimes times() {
            return new JourneyRunningDays.RideTimes(trip.tripId(), trip.stops().get(boardIdx),
                    trip.legDepartureSeconds()[boardIdx], stationId(), arrivalSeconds());
        }
    }

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
                               Map<String, StationCandidates> bestByStation, List<StationCandidates> roundStations,
                               Map<String, List<JourneyPath>> paths, Map<Ride, JourneyRunningDays.SoFar> daysByRide,
                               Counters counters) {}

    /** What a search did, for its log: where the time goes on large data. */
    private static final class Counters {

        private long departuresScanned;
        private long fallbackScans;
        private long fallbackScannedArrivals;
        private long pathsNanos;

        void addFallbacks(TransferWindow window) {
            fallbackScans += window.fallbackScanCount();
            fallbackScannedArrivals += window.fallbackScannedArrivals();
        }
    }

    /**
     * Per stop of a trip: the shortest duration that reached it there, the days of that ride, and the days of every
     * ride recorded there. Rides of one trip arrive at a stop at the same time and catch the same trains from there,
     * so a ride is cut off where it is no faster and all its days are already reached. On some of those days the
     * journey shown can then be a bit slower than the fastest possible.
     */
    private static final class TripProgress {

        private final int[] durationMinutes;
        private final long[] fastestDays;
        private final long[] reachedDays;

        TripProgress(int stopCount) {
            durationMinutes = new int[stopCount];
            Arrays.fill(durationMinutes, Integer.MAX_VALUE);
            fastestDays = new long[stopCount];
            reachedDays = new long[stopCount];
        }

        boolean reachedNoSlower(int stopIdx, int rideMinutes, long rideDays) {
            return durationMinutes[stopIdx] <= rideMinutes && (rideDays & ~reachedDays[stopIdx]) == 0;
        }

        boolean reachedFaster(int stopIdx, int rideMinutes, long rideDays) {
            return durationMinutes[stopIdx] < rideMinutes && (rideDays & ~fastestDays[stopIdx]) == 0;
        }

        void record(int stopIdx, int rideMinutes, long rideDays) {
            reachedDays[stopIdx] |= rideDays;
            if (rideMinutes >= durationMinutes[stopIdx]) return;
            durationMinutes[stopIdx] = rideMinutes;
            fastestDays[stopIdx] = rideDays;
        }
    }

    /**
     * The candidate rides of a station's result, by ascending transfers: per transfer count the fastest one and those
     * at most {@link #MAX_SLOWER_MINUTES} slower, all faster than every ride with fewer transfers. Rounds run by
     * ascending transfers, so rides arrive in that order.
     */
    private static final class StationCandidates {

        private final String stationId;
        private final List<Ride> rides = new ArrayList<>();
        private int transfers = -1;
        private int fastestWithFewerMinutes = Integer.MAX_VALUE;
        private int fastestMinutes = Integer.MAX_VALUE;

        StationCandidates(String stationId) {
            this.stationId = stationId;
        }

        void offer(Ride ride) {
            if (ride.transfers() != transfers) {
                fastestWithFewerMinutes = Math.min(fastestWithFewerMinutes, fastestMinutes);
                fastestMinutes = Integer.MAX_VALUE;
                transfers = ride.transfers();
            }
            int durationMinutes = ride.durationMinutes();
            if (durationMinutes >= fastestWithFewerMinutes) return;
            if (durationMinutes - MAX_SLOWER_MINUTES > fastestMinutes) return;
            if (durationMinutes < fastestMinutes) {
                fastestMinutes = durationMinutes;
                rides.removeIf(kept -> kept.transfers() == transfers
                        && kept.durationMinutes() - MAX_SLOWER_MINUTES > durationMinutes);
            }
            rides.add(ride);
        }
    }

    /**
     * Every station reachable from startId with at most maxTransfers. Per station: the fastest path for each
     * transfer count that is faster than every path with fewer transfers, ordered by ascending transfers. So the
     * first path has the fewest transfers and the last one is the fastest. The start station maps to an empty path.
     * After each round, roundPaths gets the paths that round added, per station (never the start station).
     */
    public Map<String, List<JourneyPath>> search(String startId, int maxTransfers, int minTransferMins,
                                                 int maxTransferMins,
                                                 Consumer<Map<String, List<JourneyPath>>> roundPaths) {
        Counters counters = new Counters();
        SearchTimetables timetables = transitGraph.searchTimetables();
        // Candidates share their earlier rides, so each ride's days are worked out once. Identity keys: a Ride's
        // record hash would walk its whole previous chain.
        SearchState state = new SearchState(timetables, startId, timetables.stationIndex(startId), minTransferMins,
                maxTransferMins, timetables.weekPeriodDays(transitGraph.todayEpochDay()),
                new TripProgress[timetables.tripCount()], new HashMap<>(), new ArrayList<>(), new HashMap<>(),
                new IdentityHashMap<>(), counters);
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
                arrivalCount, reachedTripCount(state), state.bestByStation().size() + 1, millisSince(startNanos),
                TimeUnit.NANOSECONDS.toMillis(counters.pathsNanos), counters.departuresScanned,
                counters.fallbackScans, counters.fallbackScannedArrivals);
        Map<String, List<JourneyPath>> result = state.paths();
        result.put(startId, List.of(JourneyPath.builder()
                .stationIds(List.of(startId))
                .connections(List.of())
                .transfers(0)
                .totalDurationMinutes(0)
                .build()));
        return result;
    }

    /**
     * Chooses the path with this many transfers of each station the round reached, among its candidates. A path that
     * is not faster than the chosen one with fewer transfers is left out: choosing a slower candidate can make it so.
     */
    private void finishRound(SearchState state, int transfers, Consumer<Map<String, List<JourneyPath>>> roundPaths) {
        long startNanos = System.nanoTime();
        Map<String, List<JourneyPath>> added = new HashMap<>();
        for (StationCandidates candidates : state.roundStations()) {
            List<Ride> rides = candidates.rides.stream().filter(ride -> ride.transfers() == transfers).toList();
            if (rides.isEmpty()) continue;
            Ride chosen = RegularJourneyChooser.choose(rides, ride -> runningDays(ride, state.daysByRide()).days(),
                    Ride::durationMinutes);
            List<JourneyPath> paths = state.paths().computeIfAbsent(candidates.stationId, id -> new ArrayList<>());
            if (paths.isEmpty() || chosen.durationMinutes() < paths.getLast().getTotalDurationMinutes()) {
                JourneyPath path = toJourneyPath(state.startId(), chosen);
                paths.add(path);
                added.put(candidates.stationId, List.of(path));
            }
        }
        state.roundStations().clear();
        state.counters().pathsNanos += System.nanoTime() - startNanos;
        if (!added.isEmpty()) roundPaths.accept(added);
    }

    // Counters are cumulative, so a round's share is the difference to the previous log line
    private static void logRound(int transfers, int arrivalCount, SearchState state, long startNanos) {
        Counters counters = state.counters();
        log.debug("  Round {}: {} stop arrivals, {} stations, {} ms; so far {} departures scanned, "
                        + "{} fallback scans over {} arrivals", transfers, arrivalCount, state.bestByStation().size(),
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
        // Grouped before this round lowers any best durations, so only same-round overtaking drops an arrival
        Map<String, List<Ride>> arrivalsByStation = new HashMap<>();
        for (Ride arrival : previousArrivals) {
            if (isOvertaken(state, arrival)) continue;
            arrivalsByStation.computeIfAbsent(arrival.stationId(), id -> new ArrayList<>()).add(arrival);
        }
        List<Ride> arrivals = new ArrayList<>();
        arrivalsByStation.forEach((stationId, stationArrivals) ->
                transferAt(state, stationId, stationArrivals, transfers, arrivals));
        return arrivals;
    }

    /**
     * Boards every departure at the station from the arrivals that catch it with the shortest journey so far (see
     * {@link #board}). Waits past midnight get their own window, with the arrivals moved a day back.
     */
    private void transferAt(SearchState state, String stationId, List<Ride> stationArrivals, int transfers,
                            List<Ride> arrivals) {
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
        state.counters().departuresScanned += departures.size();
        for (TripTimetable.Departure departure : departures) {
            board(state, departure, station, transfers, arrivals);
        }
        state.counters().addFallbacks(sameDay);
        state.counters().addFallbacks(overnight);
    }

    /**
     * Boards the departure from the arrival that catches it with the shortest journey so far, then from slower ones
     * only for days of the departing trip that no faster one runs on (B33). Any other arrival would ride the same
     * trip on the same days with as many transfers but longer, so it is not needed. Only arrivals whose journey runs
     * on a day that fits the departure's trip count.
     */
    private void board(SearchState state, TripTimetable.Departure departure, StationArrivals station, int transfers,
                       List<Ride> arrivals) {
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
                ride(state, departure, transfers, boardingMinutes(from, departure), from, days, arrivals);
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
            StationCandidates candidates = state.bestByStation().computeIfAbsent(ride.stationId(),
                    StationCandidates::new);
            if (candidates.transfers != transfers) state.roundStations().add(candidates);
            candidates.offer(ride);
        }
    }

    private JourneyRunningDays.SoFar runningDays(Ride ride, Map<Ride, JourneyRunningDays.SoFar> daysByRide) {
        JourneyRunningDays.SoFar known = daysByRide.get(ride);
        if (known != null) return known;
        JourneyRunningDays.SoFar before = ride.previous() != null
                ? runningDays(ride.previous(), daysByRide) : JourneyRunningDays.SoFar.START;
        JourneyRunningDays.SoFar soFar = JourneyRunningDays.withRide(before, ride.times(), transitGraph);
        daysByRide.put(ride, soFar);
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
