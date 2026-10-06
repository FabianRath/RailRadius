package com.railreach.service;

import com.railreach.service.RailTripReader.RailTrip;
import com.railreach.util.GtfsTime;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

import static com.railreach.service.GtfsCsvReader.field;
import static com.railreach.service.GtfsCsvReader.intField;

/**
 * Drops trains that run on only a few days of their feed: one-off specials and leftovers of a timetable change, like
 * a night train that ran its first three days under another number. Being a bit faster than the regular train, they
 * would win the search, and the popup would show a train that hardly ever runs (B26).
 */
@Slf4j
final class RareTrainFilter {

    // Seasonal and weekly trains stay; the shortest feed spans about a month
    private static final int MIN_RUNNING_DAYS = 8;

    /**
     * Feeds publish one train as several trips with different days, so the days are counted per train: route and
     * train number, or for a trip without a number (B32) route and its first stop and departure time.
     */
    private record Train(String routeId, String number, String firstStopId, int departureSeconds) {

        static Optional<Train> of(RailTrip trip, FirstDeparture firstDeparture) {
            if (hasNumber(trip)) return Optional.of(new Train(trip.routeId(), trip.info().trainNumber(), null, 0));
            if (firstDeparture == null) return Optional.empty();
            return Optional.of(new Train(trip.routeId(), null, firstDeparture.stopId(),
                    firstDeparture.departureSeconds()));
        }
    }

    private record FirstDeparture(String stopId, int departureSeconds, int sequence) {}

    private RareTrainFilter() {}

    /**
     * Removes the trips of every train that runs on fewer than {@link #MIN_RUNNING_DAYS} days. A train with a trip
     * that has no calendar entry is kept, and so is a trip without a number that has no stop_times.
     * {@code servicesByRawId} is the feed's calendar; stop_times.txt is only read if a trip has no train number.
     */
    static void dropRareTrains(ZipFile zipFile, Map<String, RailTrip> trips, Map<String, ServiceDays> servicesByRawId,
                               Map<String, String> rawToCanonicalStop) throws IOException {
        Map<String, FirstDeparture> firstDepartures = trips.values().stream().allMatch(RareTrainFilter::hasNumber)
                ? Map.of() : firstDepartures(zipFile, trips, rawToCanonicalStop);
        Map<String, Train> trainByTripId = new HashMap<>();
        Map<Train, ServiceDays> daysByTrain = new HashMap<>();
        trips.forEach((tripId, trip) -> Train.of(trip, firstDepartures.get(tripId)).ifPresent(train -> {
            trainByTripId.put(tripId, train);
            ServiceDays days = trip.serviceId() != null ? servicesByRawId.get(trip.serviceId()) : null;
            daysByTrain.merge(train, days != null ? days : ServiceDays.EVERY_DAY, ServiceDays::union);
        }));
        Set<Train> rareTrains = daysByTrain.entrySet().stream()
                .filter(entry -> entry.getValue() != ServiceDays.EVERY_DAY
                        && entry.getValue().dayCount() < MIN_RUNNING_DAYS)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        if (rareTrains.isEmpty()) return;
        int tripCount = trips.size();
        trips.keySet().removeIf(tripId -> rareTrains.contains(trainByTripId.get(tripId)));
        log.info("  Dropped {} trips of {} trains that run on fewer than {} days",
                tripCount - trips.size(), rareTrains.size(), MIN_RUNNING_DAYS);
    }

    /**
     * trip ID -> first stop and departure of each trip without a train number. The stop is canonical where known,
     * so trips leaving from different platforms of one station match.
     */
    private static Map<String, FirstDeparture> firstDepartures(ZipFile zipFile, Map<String, RailTrip> trips,
                                                               Map<String, String> rawToCanonicalStop)
            throws IOException {
        Map<String, FirstDeparture> firstDepartures = new HashMap<>();
        try (GtfsCsvReader csv = GtfsCsvReader.open(zipFile, "stop_times.txt")) {
            int tripCol = csv.column("trip_id");
            int stopCol = csv.column("stop_id");
            int sequenceCol = csv.column("stop_sequence");
            int departureCol = csv.column("departure_time");
            for (String[] row = csv.next(); row != null; row = csv.next()) {
                String tripId = field(row, tripCol);
                String stopId = field(row, stopCol);
                RailTrip trip = tripId != null ? trips.get(tripId) : null;
                if (trip == null || stopId == null || hasNumber(trip)) continue;
                int sequence = intField(row, sequenceCol);
                FirstDeparture known = firstDepartures.get(tripId);
                if (known != null && known.sequence() <= sequence) continue;
                firstDepartures.put(tripId, new FirstDeparture(rawToCanonicalStop.getOrDefault(stopId, stopId),
                        GtfsTime.parseSeconds(field(row, departureCol)), sequence));
            }
        }
        return firstDepartures;
    }

    private static boolean hasNumber(RailTrip trip) {
        return trip.info().trainNumber() != null;
    }
}
