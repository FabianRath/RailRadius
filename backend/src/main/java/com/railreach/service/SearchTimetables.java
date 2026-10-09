package com.railreach.service;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every trip as a {@link TripTimetable}, every stop's departures by time and where each trip may be ridden on
 * ({@link RareTrainFilter#regularDepartures}), built in one pass after a load. The search used to build them lazily
 * per trip and stop, through per-edge string lookups; on the full data that was a third of the first search. Trips
 * and stations are numbered as in {@link MergedTrips}, so the search can keep its per-trip state in arrays. The trips'
 * {@link WeekPeriodDays} depend on today, so they are worked out once per day.
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
        MergedTrips merged = MergedTrips.of(graph);
        TripTimetable[] trips = new TripTimetable[merged.trips().size()];
        for (int index = 0; index < trips.length; index++) {
            MergedTrips.Trip trip = merged.trips().get(index);
            trips[index] = TripTimetable.of(index, trip.tripId(), trip.stops(), trip.stationIndexes(), trip.days(),
                    trip.legConnections());
        }
        SearchTimetables timetables = new SearchTimetables(trips, merged.stationIndexes(), departuresByStop(trips));
        log.info("Search timetables built in {} ms: {} trips ({} with the same stations and times merged), "
                        + "{} stations", System.currentTimeMillis() - startMs, trips.length,
                merged.sourceTripCount() - trips.length, merged.stationIndexes().size());
        return timetables;
    }

    int tripCount() {
        return trips.length;
    }

    TripTimetable trip(int index) {
        return trips[index];
    }

    int stationCount() {
        return stationIndexes.size();
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
