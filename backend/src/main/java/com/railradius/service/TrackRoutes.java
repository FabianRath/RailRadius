package com.railradius.service;

import com.railradius.util.Coordinates;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * The way along the tracks between two stations (#110): the shortest path on the {@link TrackNetwork} from the track
 * near one station to the track near the other, searched with A*. A path far longer than the straight line is taken
 * for no match, since a train between two consecutive stops runs near the straight line, and the bound keeps a search
 * that finds no track from walking the whole network.
 */
final class TrackRoutes {

    static final double SNAP_RADIUS_METERS = 400;
    // Reaching the track from the station costs more than riding along it, so the nearest track wins over a
    // shorter ride from a track farther away (a parallel line next to the station)
    private static final double SNAP_COST_FACTOR = 3;
    private static final double MAX_DETOUR_FACTOR = 3;
    private static final double MAX_DETOUR_EXTRA_METERS = 10_000;

    private TrackRoutes() {
    }

    private record Entry(double estimateMeters, double costMeters, int vertex) {
    }

    /** The vertices along the track from the first station to the second; empty without such a path. */
    static Optional<int[]> route(TrackNetwork network, double fromLat, double fromLon, double toLat, double toLon) {
        Map<Integer, Double> targetCosts = snapCosts(network, toLat, toLon);
        Map<Integer, Double> costs = snapCosts(network, fromLat, fromLon);
        if (targetCosts.isEmpty() || costs.isEmpty()) return Optional.empty();
        double maxCostMeters = Coordinates.distanceMeters(fromLat, fromLon, toLat, toLon) * MAX_DETOUR_FACTOR
                + MAX_DETOUR_EXTRA_METERS;
        Map<Integer, Integer> previous = new HashMap<>();
        PriorityQueue<Entry> queue = new PriorityQueue<>((a, b) -> Double.compare(a.estimateMeters, b.estimateMeters));
        costs.forEach((vertex, cost) -> queue.add(
                new Entry(cost + remainingMeters(network, vertex, toLat, toLon), cost, vertex)));
        double bestMeters = Double.POSITIVE_INFINITY;
        int bestVertex = -1;
        while (!queue.isEmpty()) {
            Entry entry = queue.poll();
            if (entry.estimateMeters >= bestMeters) break;
            if (entry.costMeters > costs.get(entry.vertex)) continue;
            Double targetCost = targetCosts.get(entry.vertex);
            if (targetCost != null && entry.costMeters + targetCost < bestMeters) {
                bestMeters = entry.costMeters + targetCost;
                bestVertex = entry.vertex;
            }
            network.forEachNeighbour(entry.vertex, next -> {
                double cost = entry.costMeters + network.distanceMeters(entry.vertex, next);
                if (cost > maxCostMeters || cost >= costs.getOrDefault(next, Double.POSITIVE_INFINITY)) return;
                costs.put(next, cost);
                previous.put(next, entry.vertex);
                queue.add(new Entry(cost + remainingMeters(network, next, toLat, toLon), cost, next));
            });
        }
        return bestVertex < 0 ? Optional.empty() : Optional.of(path(previous, bestVertex));
    }

    /** The vertices near the station, each with the cost of reaching it from the station. */
    private static Map<Integer, Double> snapCosts(TrackNetwork network, double lat, double lon) {
        Map<Integer, Double> costs = new HashMap<>();
        network.forEachVertexNear(lat, lon, SNAP_RADIUS_METERS, vertex -> costs.put(vertex,
                SNAP_COST_FACTOR * Coordinates.distanceMeters(lat, lon, network.lat(vertex), network.lon(vertex))));
        return costs;
    }

    /** Never more than the rest of the way: every target vertex lies within the snap radius of the station. */
    private static double remainingMeters(TrackNetwork network, int vertex, double toLat, double toLon) {
        double straight = Coordinates.distanceMeters(network.lat(vertex), network.lon(vertex), toLat, toLon);
        return Math.max(0, straight - SNAP_RADIUS_METERS);
    }

    private static int[] path(Map<Integer, Integer> previous, int last) {
        int length = 1;
        for (Integer v = previous.get(last); v != null; v = previous.get(v)) length++;
        int[] path = new int[length];
        int vertex = last;
        for (int i = length - 1; i >= 0; i--) {
            path[i] = vertex;
            if (i > 0) vertex = previous.get(vertex);
        }
        return path;
    }
}
