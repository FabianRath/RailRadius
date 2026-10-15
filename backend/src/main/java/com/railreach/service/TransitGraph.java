package com.railreach.service;

import com.railreach.model.Station;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

/**
 * What the app serves of the merged rail network: stations, their translated names, the {@link TimetableStore} the
 * search runs on, the {@link TripInfo} of each store trip and how long each feed runs. Filled from the binary cache
 * or from a load of the zips ({@link RailNetwork#servedData}). All IDs are canonical stop/trip IDs.
 */
@Component
public class TransitGraph {

    private final Map<String, Station> stations = new ConcurrentHashMap<>();
    // stop ID -> its names in other languages, from the feeds' translations.txt
    private final Map<String, Set<String>> translatedNames = new ConcurrentHashMap<>();
    // trip ID of a store trip -> train number, headsign and operator
    private final Map<String, TripInfo> tripInfos = new ConcurrentHashMap<>();
    private volatile TimetableStore timetableStore = TimetableStore.empty();
    private volatile List<FeedTimespan> feedTimespans = List.of();
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

    public Optional<TripInfo> getTripInfo(String tripId) {
        return Optional.ofNullable(tripInfos.get(tripId));
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

    /** The trips in flat arrays for the search; empty before a load. */
    TimetableStore timetableStore() {
        return timetableStore;
    }

    /** How many stations the station's trips run to without a stop between: its edges. */
    public int nextStationCount(String stopId) {
        TimetableStore store = timetableStore;
        return store.stations().indexOf(stopId).stream().map(store::nextStationCount).findFirst().orElse(0);
    }

    public boolean isDataLoaded() {
        return !stations.isEmpty() && nodeCount() > 0;
    }

    public Map<String, Object> getStats() {
        return Map.of(
                "stations", stations.size(),
                "graphNodes", nodeCount(),
                "totalEdges", edgeCount()
        );
    }

    int stationCount() {
        return stations.size();
    }

    /** Stations with an edge. */
    int nodeCount() {
        return (int) nextStationCounts().filter(count -> count > 0).count();
    }

    int edgeCount() {
        return nextStationCounts().sum();
    }

    Collection<Station> stations() {
        return stations.values();
    }

    private IntStream nextStationCounts() {
        TimetableStore store = timetableStore;
        return IntStream.range(0, store.stations().size()).map(store::nextStationCount);
    }

    void clear() {
        feedTimespans = List.of();
        stations.clear();
        translatedNames.clear();
        tripInfos.clear();
        timetableStore = TimetableStore.empty();
    }

    /** Replaces the graph's data with what a load or the binary cache gives. */
    void restore(BinaryCacheStore.BinaryCacheData data) {
        clear();
        data.stations().forEach(station -> stations.put(station.getId(), station));
        translatedNames.putAll(data.translatedNames());
        TimetableStore store = data.store();
        for (int trip = 0; trip < store.tripCount(); trip++) {
            tripInfos.put(store.tripId(trip), data.tripInfos().get(trip));
        }
        timetableStore = store;
        feedTimespans = data.feeds();
    }

    /** How long each served feed runs, sorted by source; empty before a load. */
    List<FeedTimespan> feedTimespans() {
        return feedTimespans;
    }
}
