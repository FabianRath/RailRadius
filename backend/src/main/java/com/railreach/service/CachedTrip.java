package com.railreach.service;

import com.railreach.model.Connection;

import java.util.Arrays;
import java.util.List;

/**
 * One trip as stored in the GTFS cache. Leg i runs from {@code stops[i]} to {@code stops[i+1]}. A leg without a
 * connection (a stop missing from stops.txt was skipped there, or both ends were merged into one station) has
 * departure {@link #NO_LEG}. Storing trips instead of per-edge connections keeps the file about 10× smaller.
 * A connection that matches no leg of its trip (B19) is left out: the BFS walks stop lists, so it never reaches one.
 */
public record CachedTrip(String routeName, TripInfo info, List<String> stops, int[] legDepartureSeconds,
                         int[] legArrivalSeconds) {

    static final int NO_LEG = -1;

    /** Encodes a trip from its stop sequence and all of its connections, in any order. */
    static CachedTrip of(List<String> stops, TripInfo info, List<Connection> tripConnections) {
        int[] departures = new int[Math.max(stops.size() - 1, 0)];
        int[] arrivals = new int[departures.length];
        Arrays.fill(departures, NO_LEG);
        String routeName = null;
        for (Connection c : tripConnections) {
            int leg = firstFreeLeg(stops, departures, c);
            if (leg == NO_LEG) continue;
            departures[leg] = c.getDepartureTimeSeconds();
            arrivals[leg] = c.getArrivalTimeSeconds();
            routeName = c.getRouteName();
        }
        return new CachedTrip(routeName, info, stops, departures, arrivals);
    }

    int connectionCount() {
        return (int) Arrays.stream(legDepartureSeconds).filter(seconds -> seconds != NO_LEG).count();
    }

    /** Adds the trip's stop sequence and its connections to the graph. */
    void addTo(String tripId, TransitGraph graph) {
        graph.addTrip(tripId, stops, info);
        for (int leg = 0; leg < legDepartureSeconds.length; leg++) {
            if (legDepartureSeconds[leg] == NO_LEG) continue;
            graph.addConnection(Connection.builder()
                    .tripId(tripId)
                    .routeName(routeName)
                    .fromStopId(stops.get(leg))
                    .toStopId(stops.get(leg + 1))
                    .departureTimeSeconds(legDepartureSeconds[leg])
                    .arrivalTimeSeconds(legArrivalSeconds[leg])
                    .build());
        }
    }

    // A trip can run the same edge twice (loops), so each connection takes the first matching leg still free.
    // Returns NO_LEG if there is none.
    private static int firstFreeLeg(List<String> stops, int[] departures, Connection c) {
        for (int leg = 0; leg < departures.length; leg++) {
            if (departures[leg] == NO_LEG && stops.get(leg).equals(c.getFromStopId())
                    && stops.get(leg + 1).equals(c.getToStopId())) {
                return leg;
            }
        }
        return NO_LEG;
    }
}
