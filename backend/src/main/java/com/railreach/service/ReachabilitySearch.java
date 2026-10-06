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
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Transfer-bounded search over the {@link TransitGraph}, one round per transfer (like RAPTOR, but without a start
 * time: every trip leaving the start station can begin a journey).
 * <p>
 * Pruning is per trip, not per station: for each stop of a trip it remembers the shortest duration that reached it
 * there. A ride that cannot beat that is cut off, because the earlier ride arrived at the same time with no more
 * transfers and no longer journey, and stays ahead for the rest of the trip. Keeping only the fastest path per
 * station would drop slower arrivals that catch a train the fastest one misses (B2).
 * <p>
 * There is no travel date, but a transfer needs both trains to run on a common day, the next train a day later for
 * a wait past midnight (B25). Only consecutive trains are checked, not the whole journey: three trains A, B, C can
 * pass if A and B share a day and B and C share another one.
 * <p>
 * A station's result is not always the fastest journey: one up to {@link #MAX_SLOWER_MINUTES} slower is shown
 * instead if it runs on many more days ({@link RegularJourneyChooser}). Transfers still continue from the fastest
 * arrival.
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

    /** One ride from boardIdx to alightIdx; previous is the ride before the transfer, null for the first one. */
    private record Ride(TripTimetable trip, int boardIdx, int alightIdx, int transfers, int durationMinutes,
                        Ride previous) {

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

    private record SearchState(String startId, int minTransferMins, int maxTransferMins,
                               Map<String, int[]> bestDurationsByTrip,
                               Map<String, StationCandidates> bestByStation) {}

    /**
     * The candidate rides of a station's result, by ascending transfers: per transfer count the fastest one and those
     * at most {@link #MAX_SLOWER_MINUTES} slower, all faster than every ride with fewer transfers. Rounds run by
     * ascending transfers, so rides arrive in that order.
     */
    private static final class StationCandidates {

        private final List<Ride> rides = new ArrayList<>();
        private int transfers = -1;
        private int fastestWithFewerMinutes = Integer.MAX_VALUE;
        private int fastestMinutes = Integer.MAX_VALUE;

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
     */
    public Map<String, List<JourneyPath>> search(String startId, int maxTransfers,
                                                 int minTransferMins, int maxTransferMins) {
        SearchState state = new SearchState(startId, minTransferMins, maxTransferMins,
                new HashMap<>(), new HashMap<>());
        List<Ride> arrivals = new ArrayList<>();
        for (TripTimetable.Departure departure : transitGraph.getDeparturesAt(startId)) {
            ride(state, departure, 0, 0, null, arrivals);
        }
        long arrivalCount = arrivals.size();
        for (int transfers = 1; transfers <= maxTransfers && !arrivals.isEmpty(); transfers++) {
            arrivals = transferRound(state, arrivals, transfers);
            arrivalCount += arrivals.size();
            log.debug("  Round {}: {} stop arrivals, {} stations", transfers, arrivals.size(),
                    state.bestByStation().size());
        }

        log.info("  Search stats: {} stop arrivals on {} trips, {} stations",
                arrivalCount, state.bestDurationsByTrip().size(), state.bestByStation().size() + 1);
        return toJourneyPaths(state);
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
     * Boards every departure at the station from the arrival that catches it with the shortest journey so far.
     * Any other arrival would ride the same trip with as many transfers but longer, so it is not needed. Waits past
     * midnight get their own window, with the arrivals moved a day back. Only arrivals whose trip runs on a day that
     * fits the departure's trip count.
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

        for (TripTimetable.Departure departure : transitGraph.getDeparturesAt(stationId)) {
            int departureSeconds = departure.departureSeconds();
            ServiceDays departureDays = departure.trip().serviceDays();
            Ride sameDayFrom = arrivalAt(stationArrivals, sameDay.bestFor(departureSeconds,
                    i -> stationArrivals.get(i).trip().serviceDays().sharesDayWith(departureDays, SAME_DAY)));
            Ride overnightFrom = arrivalAt(stationArrivals, overnight.bestFor(departureSeconds,
                    i -> stationArrivals.get(i).trip().serviceDays().sharesDayWith(departureDays, NEXT_DAY)));
            Ride from = faster(departure, sameDayFrom, overnightFrom);
            // Timetables are shared per trip. Staying on this trip beats changing onto it from any arrival.
            if (from == null || from.trip() == departure.trip()) continue;
            ride(state, departure, transfers, boardingMinutes(from, departure), from, arrivals);
        }
    }

    private static Ride arrivalAt(List<Ride> stationArrivals, int index) {
        return index >= 0 ? stationArrivals.get(index) : null;
    }

    private static Ride faster(TripTimetable.Departure departure, Ride sameDay, Ride overnight) {
        if (sameDay == null || overnight == null) return sameDay != null ? sameDay : overnight;
        return boardingMinutes(overnight, departure) < boardingMinutes(sameDay, departure) ? overnight : sameDay;
    }

    /** Journey time up to the departure, the wait included. */
    private static int boardingMinutes(Ride from, TripTimetable.Departure departure) {
        return from.durationMinutes() + GtfsTime.diffMinutes(from.arrivalSeconds(), departure.departureSeconds());
    }

    /** A faster ride of the same round reached the same stop of the same trip later on. */
    private static boolean isOvertaken(SearchState state, Ride arrival) {
        int[] bestDurations = state.bestDurationsByTrip().get(arrival.trip().tripId());
        return bestDurations[arrival.alightIdx()] < arrival.durationMinutes();
    }

    /** Rides the trip from the departure on, recording each stop until an earlier ride was there no slower. */
    private void ride(SearchState state, TripTimetable.Departure departure, int transfers, int boardDurationMinutes,
                      Ride previous, List<Ride> arrivals) {
        TripTimetable trip = departure.trip();
        int[] bestDurations = state.bestDurationsByTrip().computeIfAbsent(trip.tripId(), id -> unreached(trip));
        int boardIdx = departure.stopIdx();
        for (int alightIdx = boardIdx + 1; alightIdx < trip.stopCount() && trip.hasLeg(alightIdx - 1); alightIdx++) {
            int durationMinutes = boardDurationMinutes + trip.rideMinutes(boardIdx, alightIdx);
            if (durationMinutes >= bestDurations[alightIdx]) return;
            bestDurations[alightIdx] = durationMinutes;
            if (trip.stops().get(alightIdx).equals(state.startId())) continue;

            Ride ride = new Ride(trip, boardIdx, alightIdx, transfers, durationMinutes, previous);
            arrivals.add(ride);
            state.bestByStation().computeIfAbsent(ride.stationId(), id -> new StationCandidates()).offer(ride);
        }
    }

    private static int[] unreached(TripTimetable trip) {
        int[] durations = new int[trip.stopCount()];
        Arrays.fill(durations, Integer.MAX_VALUE);
        return durations;
    }

    private Map<String, List<JourneyPath>> toJourneyPaths(SearchState state) {
        Map<String, List<JourneyPath>> result = new HashMap<>(state.bestByStation().size() * 2);
        result.put(state.startId(), List.of(JourneyPath.builder()
                .stationIds(List.of(state.startId()))
                .connections(List.of())
                .transfers(0)
                .totalDurationMinutes(0)
                .build()));
        // Candidates share their earlier rides, so each ride's days are worked out once. Identity keys: a Ride's
        // record hash would walk its whole previous chain.
        Map<Ride, JourneyRunningDays.SoFar> daysByRide = new IdentityHashMap<>();
        state.bestByStation().forEach((stationId, candidates) ->
                result.put(stationId, chosenPaths(state.startId(), candidates.rides, daysByRide)));
        return result;
    }

    /**
     * One path per transfer count, chosen among its candidates. A path that is not faster than the chosen one with
     * fewer transfers is left out: choosing a slower candidate can make it so.
     */
    private List<JourneyPath> chosenPaths(String startId, List<Ride> candidates,
                                          Map<Ride, JourneyRunningDays.SoFar> daysByRide) {
        Map<Integer, List<Ride>> candidatesByTransfers = candidates.stream()
                .collect(Collectors.groupingBy(Ride::transfers, TreeMap::new, Collectors.toList()));
        List<JourneyPath> paths = new ArrayList<>(candidatesByTransfers.size());
        for (List<Ride> rides : candidatesByTransfers.values()) {
            Ride chosen = RegularJourneyChooser.choose(rides, ride -> runningDays(ride, daysByRide).days(),
                    Ride::durationMinutes);
            if (paths.isEmpty() || chosen.durationMinutes() < paths.getLast().getTotalDurationMinutes()) {
                paths.add(toJourneyPath(startId, chosen));
            }
        }
        return paths;
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
                connections.add(transitGraph.getConnectionByTrip(stops.get(i), stops.get(i + 1), ride.trip().tripId()));
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
