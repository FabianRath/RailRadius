package com.railreach.service;

import com.railreach.model.Connection;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every trip as a {@link TripTimetable}, every stop's departures by time and where each trip may be ridden on
 * ({@link RareTrainFilter#regularDepartures}), built in one pass after a load. The search used to build them lazily
 * per trip and stop, through per-edge string lookups; on the full data that was a third of the first search. Trips
 * are numbered in trip ID order, stations in order of first appearance, so the search can keep its per-trip state in
 * arrays. The trips' {@link WeekPeriodDays} depend on today, so they are worked out once per day.
 * <p>
 * Trips with the same stations and times become one timetable that runs on all their days. Feeds publish one train
 * as several trips that differ only in their days, and some trains are in two feeds; on the full data that was
 * almost half of all trips, each searched separately. Under the whole-journey day check (B33) this is exact: a date
 * has the train if any of the trips runs then. The timetable keeps the first trip's ID, so its connections, and that
 * trip's train number and operator, stand for the train.
 */
@Slf4j
final class SearchTimetables {

    private final TripTimetable[] trips;
    private final Map<String, Integer> stationIndexes;
    private final Map<String, List<TripTimetable.Departure>> departuresByStop;
    private final boolean[][] regularDepartures;
    private volatile DayMasks dayMasks = new DayMasks(Integer.MIN_VALUE, new long[0]);

    /** The trips' WeekPeriodDays by trip index, as of one epoch day. */
    private record DayMasks(int epochDay, long[] byTrip) {}

    private SearchTimetables(TripTimetable[] trips, Map<String, Integer> stationIndexes,
                             Map<String, List<TripTimetable.Departure>> departuresByStop) {
        this.trips = trips;
        this.stationIndexes = stationIndexes;
        this.departuresByStop = departuresByStop;
        this.regularDepartures = new boolean[trips.length][];
        for (TripTimetable trip : trips) {
            regularDepartures[trip.index()] = RareTrainFilter.regularDepartures(trip, this::departuresAt);
        }
    }

    static SearchTimetables build(TransitGraph graph) {
        long startMs = System.currentTimeMillis();
        Map<String, List<Connection>> connectionsByTrip = graph.connectionsByTrip();
        List<String> tripIds = graph.tripIds().stream().sorted().toList();
        Map<String, Integer> stationIndexes = new HashMap<>();
        // Insertion order keeps the trips numbered in the order of their first trip ID
        Map<TripKey, TripToMerge> tripsByTimes = new LinkedHashMap<>();
        for (String tripId : tripIds) {
            List<String> stops = graph.getStopsOnTrip(tripId);
            TripToMerge trip = new TripToMerge(tripId, stops, indexesOf(stops, stationIndexes),
                    legConnections(stops, connectionsByTrip.getOrDefault(tripId, List.of())),
                    graph.getServiceDays(tripId));
            tripsByTimes.merge(TripKey.of(trip), trip, (kept, same) -> kept.withDays(kept.days().union(same.days())));
        }
        TripTimetable[] trips = new TripTimetable[tripsByTimes.size()];
        int index = 0;
        for (TripToMerge trip : tripsByTimes.values()) {
            trips[index] = TripTimetable.of(index, trip.tripId(), trip.stops(), trip.stationIndexes(), trip.days(),
                    trip.legConnections());
            index++;
        }
        SearchTimetables timetables = new SearchTimetables(trips, stationIndexes, departuresByStop(trips));
        log.info("Search timetables built in {} ms: {} trips ({} with the same stations and times merged), "
                        + "{} stations", System.currentTimeMillis() - startMs, trips.length,
                tripIds.size() - trips.length, stationIndexes.size());
        return timetables;
    }

    /** A trip before trips with the same stations and times are merged into it. */
    private record TripToMerge(String tripId, List<String> stops, int[] stationIndexes, Connection[] legConnections,
                               ServiceDays days) {

        TripToMerge withDays(ServiceDays mergedDays) {
            return new TripToMerge(tripId, stops, stationIndexes, legConnections, mergedDays);
        }
    }

    /** The stations and leg times of a trip, compared by content. */
    private record TripKey(int[] stationIndexes, int[] legSeconds) {

        static TripKey of(TripToMerge trip) {
            Connection[] legs = trip.legConnections();
            int[] legSeconds = new int[legs.length * 2];
            for (int leg = 0; leg < legs.length; leg++) {
                legSeconds[2 * leg] = legs[leg] != null ? legs[leg].getDepartureTimeSeconds() : CachedTrip.NO_LEG;
                legSeconds[2 * leg + 1] = legs[leg] != null ? legs[leg].getArrivalTimeSeconds() : CachedTrip.NO_LEG;
            }
            return new TripKey(trip.stationIndexes(), legSeconds);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TripKey key && Arrays.equals(stationIndexes, key.stationIndexes)
                    && Arrays.equals(legSeconds, key.legSeconds);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(stationIndexes) + Arrays.hashCode(legSeconds);
        }
    }

    int tripCount() {
        return trips.length;
    }

    /** The stop's position in {@link TripTimetable#stationIndexes()}; -1 if no trip serves it. */
    int stationIndex(String stopId) {
        return stationIndexes.getOrDefault(stopId, -1);
    }

    /** Every trip that leaves the stop towards a next stop, by departure time (then by trip). */
    List<TripTimetable.Departure> departuresAt(String stopId) {
        return departuresByStop.getOrDefault(stopId, List.of());
    }

    /** Per stop index of the trip: whether it may be ridden away from that stop. */
    boolean[] regularDepartures(TripTimetable trip) {
        return regularDepartures[trip.index()];
    }

    /** The trips' {@link WeekPeriodDays} as of epochDay, by trip index. */
    long[] weekPeriodDays(int epochDay) {
        DayMasks masks = dayMasks;
        if (masks.epochDay() == epochDay) return masks.byTrip();
        // Trips share their service's days, so each service is worked out once
        Map<ServiceDays, Long> byService = new IdentityHashMap<>();
        long[] byTrip = new long[trips.length];
        for (TripTimetable trip : trips) {
            byTrip[trip.index()] = byService.computeIfAbsent(trip.serviceDays(),
                    days -> WeekPeriodDays.of(days, epochDay));
        }
        dayMasks = new DayMasks(epochDay, byTrip);
        return byTrip;
    }

    private static int[] indexesOf(List<String> stops, Map<String, Integer> stationIndexes) {
        int[] indexes = new int[stops.size()];
        for (int i = 0; i < indexes.length; i++) {
            indexes[i] = stationIndexes.computeIfAbsent(stops.get(i), id -> stationIndexes.size());
        }
        return indexes;
    }

    // A trip can run the same edge twice (loops); like the per-edge lookup this replaces, each such leg gets the
    // trip's last connection on that edge
    private static Connection[] legConnections(List<String> stops, List<Connection> tripConnections) {
        Connection[] legs = new Connection[Math.max(stops.size() - 1, 0)];
        for (Connection c : tripConnections) {
            for (int leg = 0; leg < legs.length; leg++) {
                if (stops.get(leg).equals(c.getFromStopId()) && stops.get(leg + 1).equals(c.getToStopId())) {
                    legs[leg] = c;
                }
            }
        }
        return legs;
    }

    private static Map<String, List<TripTimetable.Departure>> departuresByStop(TripTimetable[] trips) {
        Map<String, List<TripTimetable.Departure>> byStop = new HashMap<>();
        for (TripTimetable trip : trips) {
            for (int leg = 0; leg < trip.legDepartureSeconds().length; leg++) {
                if (!trip.hasLeg(leg)) continue;
                byStop.computeIfAbsent(trip.stops().get(leg), id -> new ArrayList<>())
                        .add(new TripTimetable.Departure(trip, leg));
            }
        }
        // Stable: equal times stay in trip order
        byStop.values().forEach(departures ->
                departures.sort(Comparator.comparingInt(TripTimetable.Departure::departureSeconds)));
        return byStop;
    }
}
