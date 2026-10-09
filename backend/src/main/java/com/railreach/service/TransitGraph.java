package com.railreach.service;

import com.railreach.model.Connection;
import com.railreach.model.Station;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    // trip ID -> train number, headsign and operator
    private final Map<String, TripInfo> tripInfos = new ConcurrentHashMap<>();
    // service ID -> the days it runs; only services of rail trips
    private final Map<String, ServiceDays> services = new ConcurrentHashMap<>();
    // trip ID -> the days it runs, shared per service; trips without a known service are missing (every day)
    private final Map<String, ServiceDays> tripServiceDays = new ConcurrentHashMap<>();
    // stop ID -> IDs of the trips that serve it
    private final Map<String, Set<String>> stopTrips = new ConcurrentHashMap<>();
    // Built lazily: "fromId->toId" -> (trip ID -> connection)
    private final Map<String, Map<String, Connection>> connectionByTrip = new ConcurrentHashMap<>();
    // Built in one pass on first use after a load; dropped when the graph is cleared
    private volatile SearchTimetables searchTimetables;
    private final Clock clock;

    public TransitGraph() {
        this(Clock.systemDefaultZone());
    }

    /** {@code clock} says which days are past (B31). */
    private TransitGraph(Clock clock) {
        this.clock = clock;
    }

    /** A graph whose today is fixed, so a search gives the same result on every day it runs. */
    static TransitGraph withToday(LocalDate today) {
        return new TransitGraph(Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
    }

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

    public Optional<TripInfo> getTripInfo(String tripId) {
        return Optional.ofNullable(tripInfos.get(tripId));
    }

    /** The days the trip runs; {@link ServiceDays#EVERY_DAY} if its feed has no calendar entry for it. */
    ServiceDays getServiceDays(String tripId) {
        return tripServiceDays.getOrDefault(tripId, ServiceDays.EVERY_DAY);
    }

    /**
     * The days from today on (B31). A journey's dates start with its first train's, so a train whose days are all
     * past, such as one of an expired feed, is not ridden until its feed is downloaded again.
     */
    ServiceDays upcomingDays(ServiceDays days) {
        return days.from(todayEpochDay());
    }

    /** Today by the graph's clock, which says which days are past (B31). */
    int todayEpochDay() {
        return (int) LocalDate.now(clock).toEpochDay();
    }

    /** The trips laid out for the search; built on the first call after a load. */
    SearchTimetables searchTimetables() {
        SearchTimetables built = searchTimetables;
        if (built != null) return built;
        synchronized (this) {
            if (searchTimetables == null) searchTimetables = SearchTimetables.build(this);
            return searchTimetables;
        }
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
        tripInfos.clear();
        services.clear();
        tripServiceDays.clear();
        stopTrips.clear();
        connectionByTrip.clear();
        searchTimetables = null;
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

    GtfsCacheStore.GtfsCacheData snapshot() {
        Map<String, List<Connection>> connectionsByTrip = connectionsByTrip();
        // Trips hold their service's shared ServiceDays, not its ID
        Map<ServiceDays, String> serviceIds = new IdentityHashMap<>(services.size() * 2);
        services.forEach((serviceId, days) -> serviceIds.put(days, serviceId));
        Map<String, CachedTrip> trips = new HashMap<>(tripStops.size() * 2);
        tripStops.forEach((tripId, stops) ->
                trips.put(tripId, CachedTrip.of(stops, tripInfos.get(tripId), serviceIds.get(getServiceDays(tripId)),
                        connectionsByTrip.getOrDefault(tripId, List.of()))));
        long droppedCount = connections.values().stream().mapToLong(List::size).sum()
                - trips.values().stream().mapToLong(CachedTrip::connectionCount).sum();
        if (droppedCount > 0) {
            log.warn("{} connections match no leg of their trip's stop list and are left out of the cache (B19)",
                    droppedCount);
        }
        return new GtfsCacheStore.GtfsCacheData(GtfsCacheStore.FORMAT_VERSION, new HashMap<>(stations),
                new HashMap<>(translatedNames), new HashMap<>(services), trips);
    }

    /** Edges, connections and the stop→trips index are not stored; they are rebuilt from the trips. */
    void restore(GtfsCacheStore.GtfsCacheData data) {
        clear();
        data.stations().values().forEach(station -> stations.put(station.getId(), station));
        data.translatedNames().forEach((stopId, names) -> translatedNames.put(sharedStopId(stopId), names));
        services.putAll(data.services());
        data.trips().forEach((tripId, trip) -> {
            trip.stops().replaceAll(this::sharedStopId);
            trip.addTo(tripId, this);
        });
    }

    // The JSON reader makes a new String for every stop of every trip; on the full data those copies were a quarter
    // of the heap (S16). Everything built from the trips then holds the station's own ID instead.
    private String sharedStopId(String stopId) {
        Station station = stations.get(stopId);
        return station != null ? station.getId() : stopId;
    }

    /**
     * Folds every alias stop into its canonical stop: edges, connections, trips, translated names; then drops the
     * alias. A canonical stop may itself be an alias (A→B, B→C); A then ends up at C.
     */
    void mergeStations(Map<String, String> aliasToCanonicalOrAlias) {
        Map<String, String> aliasToCanonical = resolveChains(aliasToCanonicalOrAlias);
        searchTimetables = null;
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
