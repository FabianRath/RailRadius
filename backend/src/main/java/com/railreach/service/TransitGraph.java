package com.railreach.service;

import com.railreach.model.Connection;
import com.railreach.model.Station;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** The merged rail network of all loaded feeds. All IDs are canonical stop/trip IDs. */
@Component
@Slf4j
public class TransitGraph {

    private static final String EDGE_SEPARATOR = "->";
    private static final int MERGE_PROGRESS_LOG_INTERVAL = 1000;

    private final Map<String, Station> stations = new ConcurrentHashMap<>();
    // stop ID -> stop IDs reachable without an intermediate stop
    private final Map<String, Set<String>> neighbors = new ConcurrentHashMap<>();
    // "fromId->toId" -> every trip's connection along that edge
    private final Map<String, List<Connection>> connections = new ConcurrentHashMap<>();
    // trip ID -> ordered stop IDs
    private final Map<String, List<String>> tripStops = new ConcurrentHashMap<>();
    // stop ID -> IDs of the trips that serve it
    private final Map<String, Set<String>> stopTrips = new ConcurrentHashMap<>();
    // Built lazily on first BFS access, so loading stays lean: trip ID -> (stop ID -> position)
    private final Map<String, Map<String, Integer>> tripStopIndex = new ConcurrentHashMap<>();
    // Built lazily: "fromId->toId" -> (trip ID -> connection)
    private final Map<String, Map<String, Connection>> connectionByTrip = new ConcurrentHashMap<>();

    public Station getStation(String stopId) {
        return stations.get(stopId);
    }

    public Map<String, Station> getAllStations() {
        return Collections.unmodifiableMap(stations);
    }

    public Set<String> getNeighbors(String stopId) {
        return neighbors.getOrDefault(stopId, Collections.emptySet());
    }

    public List<Connection> getConnections(String fromId, String toId) {
        return connections.getOrDefault(edgeKey(fromId, toId), Collections.emptyList());
    }

    public Set<String> getTripsAtStop(String stopId) {
        return stopTrips.getOrDefault(stopId, Collections.emptySet());
    }

    public List<String> getStopsOnTrip(String tripId) {
        return tripStops.getOrDefault(tripId, Collections.emptyList());
    }

    /** Position of the stop within the trip's stop sequence, or -1. O(1) via a lazy reverse index. */
    public int getStopIndexOnTrip(String tripId, String stopId) {
        Map<String, Integer> index = tripStopIndex.computeIfAbsent(tripId, id -> {
            List<String> stops = getStopsOnTrip(id);
            Map<String, Integer> positions = new HashMap<>(stops.size() * 2);
            for (int i = 0; i < stops.size(); i++) {
                positions.put(stops.get(i), i);
            }
            return positions;
        });
        return index.getOrDefault(stopId, -1);
    }

    /** The trip's connection between two adjacent stops, or null. O(1) via a lazy per-edge index. */
    public Connection getConnectionByTrip(String fromId, String toId, String tripId) {
        return connectionByTrip.computeIfAbsent(edgeKey(fromId, toId), key -> {
            List<Connection> edgeConnections = connections.getOrDefault(key, Collections.emptyList());
            Map<String, Connection> byTrip = new HashMap<>(edgeConnections.size() * 2);
            for (Connection c : edgeConnections) {
                byTrip.put(c.getTripId(), c);
            }
            return byTrip;
        }).get(tripId);
    }

    public boolean isDataLoaded() {
        return !stations.isEmpty() && !neighbors.isEmpty();
    }

    public Map<String, Object> getStats() {
        return Map.of(
                "stations", stations.size(),
                "graphNodes", neighbors.size(),
                "totalEdges", edgeCount()
        );
    }

    /** Stations whose name contains the query and that have outgoing edges, best connected first. */
    public List<Station> searchStations(String query, int limit) {
        String q = query.toLowerCase();
        return stations.values().stream()
                .filter(s -> s.getName() != null && s.getName().toLowerCase().contains(q))
                .filter(s -> !getNeighbors(s.getId()).isEmpty())
                .sorted(Comparator.comparingInt((Station s) -> getNeighbors(s.getId()).size()).reversed())
                .limit(limit)
                .toList();
    }

    int stationCount() {
        return stations.size();
    }

    int nodeCount() {
        return neighbors.size();
    }

    int edgeCount() {
        return neighbors.values().stream().mapToInt(Set::size).sum();
    }

    int connectionCount() {
        return connections.size();
    }

    long connectedStationCount() {
        return stations.keySet().stream().filter(id -> !getNeighbors(id).isEmpty()).count();
    }

    Collection<Station> stations() {
        return stations.values();
    }

    void clear() {
        stations.clear();
        neighbors.clear();
        connections.clear();
        tripStops.clear();
        stopTrips.clear();
        tripStopIndex.clear();
        connectionByTrip.clear();
    }

    void addStationIfAbsent(Station station) {
        stations.putIfAbsent(station.getId(), station);
    }

    void addTrip(String tripId, List<String> stopIds) {
        tripStops.put(tripId, stopIds);
        for (String stopId : stopIds) {
            stopTrips.computeIfAbsent(stopId, k -> new HashSet<>()).add(tripId);
        }
    }

    void addConnection(Connection connection) {
        neighbors.computeIfAbsent(connection.getFromStopId(), k -> new HashSet<>()).add(connection.getToStopId());
        connections.computeIfAbsent(edgeKey(connection.getFromStopId(), connection.getToStopId()),
                k -> new ArrayList<>()).add(connection);
    }

    GtfsCacheStore.GtfsCacheData snapshot() {
        return new GtfsCacheStore.GtfsCacheData(new HashMap<>(stations), new HashMap<>(neighbors),
                new HashMap<>(connections), new HashMap<>(tripStops), new HashMap<>(stopTrips),
                System.currentTimeMillis(), stations.size(), neighbors.size());
    }

    void restore(GtfsCacheStore.GtfsCacheData data) {
        clear();
        stations.putAll(data.stations());
        neighbors.putAll(data.graph());
        connections.putAll(data.connectionInfo());
        tripStops.putAll(data.tripStops());
        stopTrips.putAll(data.stopTrips());
    }

    /** Folds every alias stop into its canonical stop: edges, connections, trips; then drops the alias. */
    void mergeStations(Map<String, String> aliasToCanonical) {
        int merged = 0;
        for (Map.Entry<String, String> mapping : aliasToCanonical.entrySet()) {
            if (++merged % MERGE_PROGRESS_LOG_INTERVAL == 0) {
                log.info("  Applying remapping: {}/{} completed", merged, aliasToCanonical.size());
            }
            String alias = mapping.getKey();
            String canonical = mapping.getValue();
            mergeEdges(alias, canonical, aliasToCanonical);
            mergeConnections(alias, aliasToCanonical);
            mergeTrips(alias, canonical);
            stations.remove(alias);
        }
    }

    private void mergeEdges(String alias, String canonical, Map<String, String> aliasToCanonical) {
        Set<String> aliasNeighbors = neighbors.remove(alias);
        if (aliasNeighbors != null) {
            Set<String> canonicalNeighbors = neighbors.computeIfAbsent(canonical, k -> new HashSet<>());
            for (String neighbor : aliasNeighbors) {
                String remapped = aliasToCanonical.getOrDefault(neighbor, neighbor);
                if (!remapped.equals(canonical)) canonicalNeighbors.add(remapped);
            }
        }
        for (Set<String> targets : neighbors.values()) {
            if (targets.remove(alias)) targets.add(canonical);
        }
    }

    private void mergeConnections(String alias, Map<String, String> aliasToCanonical) {
        List<String> keysToRemap = connections.keySet().stream()
                .filter(key -> key.startsWith(alias + EDGE_SEPARATOR) || key.endsWith(EDGE_SEPARATOR + alias))
                .toList();
        for (String oldKey : keysToRemap) {
            List<Connection> edgeConnections = connections.remove(oldKey);
            String[] ends = oldKey.split(EDGE_SEPARATOR);
            String newFrom = aliasToCanonical.getOrDefault(ends[0], ends[0]);
            String newTo = aliasToCanonical.getOrDefault(ends[1], ends[1]);
            if (!newFrom.equals(newTo)) {
                connections.computeIfAbsent(edgeKey(newFrom, newTo), k -> new ArrayList<>()).addAll(edgeConnections);
            }
        }
    }

    private void mergeTrips(String alias, String canonical) {
        Set<String> aliasTrips = stopTrips.remove(alias);
        if (aliasTrips == null) return;
        stopTrips.computeIfAbsent(canonical, k -> new HashSet<>()).addAll(aliasTrips);
        for (String tripId : aliasTrips) {
            List<String> stops = tripStops.get(tripId);
            if (stops == null) continue;
            stops.replaceAll(stopId -> stopId.equals(alias) ? canonical : stopId);
        }
    }

    private static String edgeKey(String fromId, String toId) {
        return fromId + EDGE_SEPARATOR + toId;
    }
}
