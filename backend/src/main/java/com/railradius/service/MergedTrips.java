package com.railradius.service;

import com.railradius.model.Connection;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The network's trips as the search sees them, the input of {@link TimetableStore}. Trips are numbered in trip ID
 * order, stations in order of first appearance.
 * <p>
 * Trips with the same stations and times become one trip that runs on all their days. Feeds publish one train as
 * several trips that differ only in their days, and some trains are in two feeds; on the full data that was almost
 * half of all trips, each searched separately. Under the whole-journey day check (B33) this is exact: a date has the
 * train if any of the trips runs then. The merged trip keeps the first trip's ID, so its connections, and that trip's
 * train number and operator, stand for the train. The other trips' IDs are dropped: nothing reads them.
 */
record MergedTrips(List<Trip> trips, Map<String, Integer> stationIndexes, int sourceTripCount) {

    /**
     * One merged trip. tripId is the first in trip ID order of the trips it stands for.
     * legConnections[i] is leg i's connection, null where the trip has none.
     */
    record Trip(String tripId, List<String> stops, int[] stationIndexes, Connection[] legConnections,
                ServiceDays days) {

        private Trip withMember(Trip same) {
            return new Trip(tripId, stops, stationIndexes, legConnections, days.union(same.days()));
        }
    }

    /** The stations and leg times of a trip, compared by content. */
    private record TripKey(int[] stationIndexes, int[] legSeconds) {

        static TripKey of(Trip trip) {
            Connection[] legs = trip.legConnections();
            int[] legSeconds = new int[legs.length * 2];
            for (int leg = 0; leg < legs.length; leg++) {
                legSeconds[2 * leg] = legs[leg] != null ? legs[leg].getDepartureTimeSeconds() : TimetableStore.NO_LEG;
                legSeconds[2 * leg + 1] = legs[leg] != null ? legs[leg].getArrivalTimeSeconds() : TimetableStore.NO_LEG;
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

    static MergedTrips of(RailNetwork network) {
        Map<String, List<Connection>> connectionsByTrip = network.connectionsByTrip();
        List<String> tripIds = network.tripIds().stream().sorted().toList();
        Map<String, Integer> stationIndexes = new HashMap<>();
        // Insertion order keeps the trips numbered in the order of their first trip ID
        Map<TripKey, Trip> tripsByTimes = new LinkedHashMap<>();
        for (String tripId : tripIds) {
            List<String> stops = network.getStopsOnTrip(tripId);
            Trip trip = new Trip(tripId, stops, indexesOf(stops, stationIndexes),
                    legConnections(stops, connectionsByTrip.getOrDefault(tripId, List.of())),
                    network.getServiceDays(tripId));
            tripsByTimes.merge(TripKey.of(trip), trip, Trip::withMember);
        }
        return new MergedTrips(List.copyOf(tripsByTimes.values()), stationIndexes, tripIds.size());
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
}
