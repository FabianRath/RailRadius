package com.railreach.service;

import com.railreach.service.RailTripReader.RailTrip;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Drops trains that run on only a few days of their feed: one-off specials and leftovers of a timetable change, like
 * a night train that ran its first three days under another number. Being a bit faster than the regular train, they
 * would win the search, and the popup would show a train that hardly ever runs (B26).
 */
@Slf4j
final class RareTrainFilter {

    // Seasonal and weekly trains stay; the shortest feed spans about a month
    private static final int MIN_RUNNING_DAYS = 8;

    /** Feeds publish one train as several trips with different days, so the days are counted per train. */
    private record Train(String routeId, String number) {

        static Train of(RailTrip trip) {
            return new Train(trip.routeId(), trip.info().trainNumber());
        }
    }

    private RareTrainFilter() {}

    /**
     * Removes the trips of every train (route and train number) that runs on fewer than {@link #MIN_RUNNING_DAYS}
     * days. Trips without a train number are kept, and so is a train with a trip that has no calendar entry.
     * {@code servicesByRawId} is the feed's calendar.
     */
    static void dropRareTrains(Map<String, RailTrip> trips, Map<String, ServiceDays> servicesByRawId) {
        Map<Train, ServiceDays> daysByTrain = new HashMap<>();
        for (RailTrip trip : trips.values()) {
            if (!hasNumber(trip)) continue;
            ServiceDays days = trip.serviceId() != null ? servicesByRawId.get(trip.serviceId()) : null;
            daysByTrain.merge(Train.of(trip), days != null ? days : ServiceDays.EVERY_DAY, ServiceDays::union);
        }
        Set<Train> rareTrains = daysByTrain.entrySet().stream()
                .filter(entry -> entry.getValue() != ServiceDays.EVERY_DAY
                        && entry.getValue().dayCount() < MIN_RUNNING_DAYS)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        if (rareTrains.isEmpty()) return;
        int tripCount = trips.size();
        trips.values().removeIf(trip -> hasNumber(trip) && rareTrains.contains(Train.of(trip)));
        log.info("  Dropped {} trips of {} trains that run on fewer than {} days",
                tripCount - trips.size(), rareTrains.size(), MIN_RUNNING_DAYS);
    }

    private static boolean hasNumber(RailTrip trip) {
        return trip.info().trainNumber() != null;
    }
}
