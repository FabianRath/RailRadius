package com.railreach.service;

import com.railreach.model.Connection;
import com.railreach.util.GtfsTime;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tells whether two stops of different feeds are the same station because the same trains serve them: a cross-border
 * train is in both countries' feeds, leaving at the same minute to the same next station (or arriving at the same
 * minute from the same previous one). Works whatever the stops are called and however far apart the feeds put them.
 * Reads the raw edges and connections, not the graph's lazy timetables, which merging would leave stale.
 */
final class SharedTrainMatcher {

    // One shared minute could be chance; several trains with the same minute and neighbour are not
    static final int MIN_SHARED_TRAINS = 3;

    private SharedTrainMatcher() {}

    /** Neighbours are compared after the merges in aliasToCanonical, so stations merged by name already count as one. */
    static boolean shareTrains(TransitGraph graph, String stopId1, String stopId2, Map<String, String> aliasToCanonical) {
        Set<String> signatures2 = new HashSet<>();
        trainSignatures(graph, stopId2, aliasToCanonical).values().forEach(signatures2::addAll);
        if (signatures2.isEmpty()) return false;
        long sharedTrains = trainSignatures(graph, stopId1, aliasToCanonical).values().stream()
                .filter(signatures -> signatures.stream().anyMatch(signatures2::contains))
                .limit(MIN_SHARED_TRAINS)
                .count();
        return sharedTrains >= MIN_SHARED_TRAINS;
    }

    /**
     * Trip ID → "dep|minute|next station" and "arr|minute|previous station" of its visits to the stop. Walks each
     * edge's connections once, so hubs with thousands of trips stay cheap.
     */
    private static Map<String, Set<String>> trainSignatures(TransitGraph graph, String stopId,
                                                            Map<String, String> aliasToCanonical) {
        Map<String, Set<String>> signaturesByTrip = new HashMap<>();
        for (String nextId : graph.getNeighbors(stopId)) {
            for (Connection c : graph.getConnections(stopId, nextId)) {
                signaturesByTrip.computeIfAbsent(c.getTripId(), k -> new HashSet<>())
                        .add(signature("dep", c.getDepartureTimeSeconds(), nextId, aliasToCanonical));
            }
        }
        for (String previousId : previousStops(graph, stopId)) {
            for (Connection c : graph.getConnections(previousId, stopId)) {
                signaturesByTrip.computeIfAbsent(c.getTripId(), k -> new HashSet<>())
                        .add(signature("arr", c.getArrivalTimeSeconds(), previousId, aliasToCanonical));
            }
        }
        return signaturesByTrip;
    }

    /** The graph keeps outgoing edges only, so incoming ones are found through the trips that serve the stop. */
    private static Set<String> previousStops(TransitGraph graph, String stopId) {
        Set<String> previous = new HashSet<>();
        for (String tripId : graph.getTripsAtStop(stopId)) {
            List<String> stops = graph.getStopsOnTrip(tripId);
            for (int i = 1; i < stops.size(); i++) {
                if (stops.get(i).equals(stopId)) previous.add(stops.get(i - 1));
            }
        }
        return previous;
    }

    private static String signature(String direction, int timeSeconds, String neighbourId,
                                    Map<String, String> aliasToCanonical) {
        return direction + "|" + GtfsTime.minuteOfDay(timeSeconds) + "|"
                + TransitGraph.canonicalOf(neighbourId, aliasToCanonical);
    }
}
