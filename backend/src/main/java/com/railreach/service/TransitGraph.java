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
    // stop ID -> its names in other languages, from the feeds' translations.txt
    private final Map<String, Set<String>> translatedNames = new ConcurrentHashMap<>();
    // stop ID -> stop IDs reachable without an intermediate stop
    private final Map<String, Set<String>> neighbors = new ConcurrentHashMap<>();
    // "fromId->toId" -> every trip's connection along that edge
    private final Map<String, List<Connection>> connections = new ConcurrentHashMap<>();
    // trip ID -> ordered stop IDs
    private final Map<String, List<String>> tripStops = new ConcurrentHashMap<>();
    // stop ID -> IDs of the trips that serve it
    private final Map<String, Set<String>> stopTrips = new ConcurrentHashMap<>();
    // Built lazily on first search access, so loading stays lean
    private final Map<String, TripTimetable> timetables = new ConcurrentHashMap<>();
    private final Map<String, List<TripTimetable.Departure>> departuresByStop = new ConcurrentHashMap<>();
    // Built lazily: "fromId->toId" -> (trip ID -> connection)
    private final Map<String, Map<String, Connection>> connectionByTrip = new ConcurrentHashMap<>();

    public Station getStation(String stopId) {
        return stations.get(stopId);
    }

    public Map<String, Station> getAllStations() {
        return Collections.unmodifiableMap(stations);
    }

    Set<String> getTranslatedNames(String stopId) {
        return translatedNames.getOrDefault(stopId, Collections.emptySet());
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

    TripTimetable getTimetable(String tripId) {
        return timetables.computeIfAbsent(tripId, id -> TripTimetable.of(id, this));
    }

    /** Every trip that leaves the stop towards a next stop, by departure time. */
    List<TripTimetable.Departure> getDeparturesAt(String stopId) {
        return departuresByStop.computeIfAbsent(stopId, id -> getTripsAtStop(id).stream()
                .flatMap(tripId -> getTimetable(tripId).departuresFrom(id).stream())
                .sorted(Comparator.comparingInt(TripTimetable.Departure::departureSeconds))
                .toList());
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
        translatedNames.clear();
        neighbors.clear();
        connections.clear();
        tripStops.clear();
        stopTrips.clear();
        timetables.clear();
        departuresByStop.clear();
        connectionByTrip.clear();
    }

    void addStationIfAbsent(Station station) {
        stations.putIfAbsent(station.getId(), station);
    }

    void addTranslatedNames(String stopId, Collection<String> names) {
        translatedNames.computeIfAbsent(stopId, k -> new HashSet<>()).addAll(names);
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
        Map<String, List<Connection>> connectionsByTrip = new HashMap<>(tripStops.size() * 2);
        for (List<Connection> edgeConnections : connections.values()) {
            for (Connection c : edgeConnections) {
                connectionsByTrip.computeIfAbsent(c.getTripId(), k -> new ArrayList<>()).add(c);
            }
        }
        Map<String, CachedTrip> trips = new HashMap<>(tripStops.size() * 2);
        tripStops.forEach((tripId, stops) ->
                trips.put(tripId, CachedTrip.of(stops, connectionsByTrip.getOrDefault(tripId, List.of()))));
        long droppedCount = connections.values().stream().mapToLong(List::size).sum()
                - trips.values().stream().mapToLong(CachedTrip::connectionCount).sum();
        if (droppedCount > 0) {
            log.warn("{} connections match no leg of their trip's stop list and are left out of the cache (B19)",
                    droppedCount);
        }
        return new GtfsCacheStore.GtfsCacheData(GtfsCacheStore.FORMAT_VERSION, new HashMap<>(stations),
                new HashMap<>(translatedNames), trips);
    }

    /** Edges, connections and the stop→trips index are not stored; they are rebuilt from the trips. */
    void restore(GtfsCacheStore.GtfsCacheData data) {
        clear();
        stations.putAll(data.stations());
        translatedNames.putAll(data.translatedNames());
        data.trips().forEach((tripId, trip) -> trip.addTo(tripId, this));
    }

    /**
     * Folds every alias stop into its canonical stop: edges, connections, trips, translated names; then drops the
     * alias. A canonical stop may itself be an alias (A→B, B→C); A then ends up at C.
     */
    void mergeStations(Map<String, String> aliasToCanonicalOrAlias) {
        Map<String, String> aliasToCanonical = resolveChains(aliasToCanonicalOrAlias);
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
            Set<String> aliasTranslatedNames = translatedNames.remove(alias);
            if (aliasTranslatedNames != null) addTranslatedNames(canonical, aliasTranslatedNames);
            stations.remove(alias);
        }
    }

    // Each merge step below remaps one hop only, so every alias must point straight at the end of its chain.
    // Otherwise, depending on the merge order, trips and connections are left on removed or different stations (B19).
    private static Map<String, String> resolveChains(Map<String, String> aliasToCanonical) {
        Map<String, String> resolved = new HashMap<>(aliasToCanonical.size() * 2);
        for (String alias : aliasToCanonical.keySet()) {
            resolved.put(alias, canonicalOf(alias, aliasToCanonical));
        }
        return resolved;
    }

    /** The stop at the end of the stop's alias chain; the stop itself if it is no alias. */
    static String canonicalOf(String stopId, Map<String, String> aliasToCanonical) {
        String target = stopId;
        for (int hops = 0; aliasToCanonical.containsKey(target); hops++) {
            if (hops > aliasToCanonical.size()) throw new IllegalStateException("Station merge cycle at " + stopId);
            target = aliasToCanonical.get(target);
        }
        return target;
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
            if (newFrom.equals(newTo)) continue;
            for (Connection c : edgeConnections) {
                c.setFromStopId(newFrom);
                c.setToStopId(newTo);
            }
            connections.computeIfAbsent(edgeKey(newFrom, newTo), k -> new ArrayList<>()).addAll(edgeConnections);
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
