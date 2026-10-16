package com.railradius.service;

import com.railradius.model.Connection;
import com.railradius.model.Station;
import lombok.extern.slf4j.Slf4j;

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

/**
 * The merged rail network of all feeds while they are loaded from the zips: stations, edges, every trip's stops and
 * connections. The station merging needs those; the search needs only the {@link TimetableStore} built from them at
 * the end ({@link #servedData}), after which this is dropped. All IDs are canonical stop/trip IDs.
 */
@Slf4j
final class RailNetwork {

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
    // trip ID -> train number, headsign and operator
    private final Map<String, TripInfo> tripInfos = new ConcurrentHashMap<>();
    // service ID -> the days it runs; only services of rail trips
    private final Map<String, ServiceDays> services = new ConcurrentHashMap<>();
    // source -> how long its trains run
    private final Map<String, FeedTimespan> feedTimespans = new ConcurrentHashMap<>();
    // trip ID -> the days it runs, shared per service; trips without a known service are missing (every day)
    private final Map<String, ServiceDays> tripServiceDays = new ConcurrentHashMap<>();
    // stop ID -> IDs of the trips that serve it
    private final Map<String, Set<String>> stopTrips = new ConcurrentHashMap<>();

    Station getStation(String stopId) {
        return stations.get(stopId);
    }

    Collection<Station> stations() {
        return stations.values();
    }

    Set<String> getNeighbors(String stopId) {
        return neighbors.getOrDefault(stopId, Collections.emptySet());
    }

    List<Connection> getConnections(String fromId, String toId) {
        return connections.getOrDefault(edgeKey(fromId, toId), Collections.emptyList());
    }

    Set<String> getTripsAtStop(String stopId) {
        return stopTrips.getOrDefault(stopId, Collections.emptySet());
    }

    List<String> getStopsOnTrip(String tripId) {
        return tripStops.getOrDefault(tripId, Collections.emptyList());
    }

    /** The days the trip runs; {@link ServiceDays#EVERY_DAY} if its feed has no calendar entry for it. */
    ServiceDays getServiceDays(String tripId) {
        return tripServiceDays.getOrDefault(tripId, ServiceDays.EVERY_DAY);
    }

    Set<String> tripIds() {
        return Collections.unmodifiableSet(tripStops.keySet());
    }

    /** Trip ID -> its connections, in no particular order. */
    Map<String, List<Connection>> connectionsByTrip() {
        Map<String, List<Connection>> byTrip = new HashMap<>(tripStops.size() * 2);
        for (List<Connection> edgeConnections : connections.values()) {
            for (Connection c : edgeConnections) {
                byTrip.computeIfAbsent(c.getTripId(), k -> new ArrayList<>()).add(c);
            }
        }
        return byTrip;
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

    long connectedStationCount() {
        return stations.keySet().stream().filter(id -> !getNeighbors(id).isEmpty()).count();
    }

    /**
     * What the app serves (S21): stations, translated names, the {@link TimetableStore} built from the trips and the
     * {@link TripInfo} of each store trip.
     */
    BinaryCacheStore.BinaryCacheData servedData() {
        TimetableStore store = TimetableStore.build(this);
        List<TripInfo> storeTripInfos = new ArrayList<>(store.tripCount());
        for (int trip = 0; trip < store.tripCount(); trip++) {
            String tripId = store.tripId(trip);
            TripInfo info = tripInfos.get(tripId);
            if (info == null) throw new IllegalStateException("No trip info for " + tripId);
            storeTripInfos.add(info);
        }
        List<FeedTimespan> feeds = feedTimespans.values().stream()
                .sorted(Comparator.comparing(FeedTimespan::source))
                .toList();
        return new BinaryCacheStore.BinaryCacheData(List.copyOf(stations.values()),
                Collections.unmodifiableMap(translatedNames), storeTripInfos, feeds, store);
    }

    void addFeedTimespan(FeedTimespan timespan) {
        feedTimespans.put(timespan.source(), timespan);
    }

    void addStationIfAbsent(Station station) {
        stations.putIfAbsent(station.getId(), station);
    }

    void addTranslatedNames(String stopId, Collection<String> names) {
        translatedNames.computeIfAbsent(stopId, k -> new HashSet<>()).addAll(names);
    }

    void addServices(Map<String, ServiceDays> serviceDaysById) {
        services.putAll(serviceDaysById);
    }

    /** serviceId may be null or unknown; the trip then runs every day. Add its service first. */
    void addTrip(String tripId, List<String> stopIds, TripInfo info, String serviceId) {
        tripStops.put(tripId, stopIds);
        tripInfos.put(tripId, info);
        ServiceDays days = serviceId != null ? services.get(serviceId) : null;
        if (days != null) tripServiceDays.put(tripId, days);
        for (String stopId : stopIds) {
            stopTrips.computeIfAbsent(stopId, k -> new HashSet<>()).add(tripId);
        }
    }

    void addConnection(Connection connection) {
        neighbors.computeIfAbsent(connection.getFromStopId(), k -> new HashSet<>()).add(connection.getToStopId());
        connections.computeIfAbsent(edgeKey(connection.getFromStopId(), connection.getToStopId()),
                k -> new ArrayList<>()).add(connection);
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
