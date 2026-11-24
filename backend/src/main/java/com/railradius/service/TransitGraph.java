package com.railradius.service;

import com.railradius.model.Station;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Holds the {@link ServedGraph}: stations, their translated names, the {@link TimetableStore} the search runs on, the
 * {@link TripInfo} of each store trip, how long each feed runs and the station search index. Filled from the binary
 * cache or from a load of the zips ({@link RailNetwork#servedData}). Each getter reads the served graph of that
 * moment; a reader that needs several values from one graph takes {@link #served} once.
 */
@Component
public class TransitGraph {

    private volatile ServedGraph served = ServedGraph.EMPTY;
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

    /** The served graph now; empty before a load. */
    ServedGraph served() {
        return served;
    }

    /** Makes what a load or the binary cache gives the served graph, in one step (#18). */
    void serve(BinaryCacheStore.BinaryCacheData data) {
        served = ServedGraph.of(data);
    }

    /** Serves what a browser reads from its timetable file (#10): the same search, no journeys by trip ID. */
    void serve(BrowserDataFile.BrowserData data) {
        served = ServedGraph.of(data);
    }

    public Station getStation(String stopId) {
        return served.station(stopId);
    }

    public Map<String, Station> getAllStations() {
        return served.stations();
    }

    Set<String> getTranslatedNames(String stopId) {
        return served.translatedNamesOf(stopId);
    }

    public Optional<TripInfo> getTripInfo(String tripId) {
        return served.tripInfo(tripId);
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
        return served.store();
    }

    /** How many stations the station's trips run to without a stop between: its edges. */
    public int nextStationCount(String stopId) {
        return served.store().nextStationCount(stopId);
    }

    /** How many trips stop at the station; 0 for an unknown one. */
    public int stoppingTripCount(String stopId) {
        TimetableStore store = served.store();
        return store.stations().indexOf(stopId).stream().map(store::stoppingTripCount).findFirst().orElse(0);
    }

    public boolean isDataLoaded() {
        ServedGraph graph = served;
        return !graph.stations().isEmpty() && nodeCount(graph.store()) > 0;
    }

    public Map<String, Object> getStats() {
        ServedGraph graph = served;
        return Map.of(
                "stations", graph.stations().size(),
                "graphNodes", nodeCount(graph.store()),
                "totalEdges", edgeCount(graph.store())
        );
    }

    int stationCount() {
        return served.stations().size();
    }

    /** Stations with an edge. */
    int nodeCount() {
        return nodeCount(served.store());
    }

    int edgeCount() {
        return edgeCount(served.store());
    }

    private static int nodeCount(TimetableStore store) {
        return (int) nextStationCounts(store).filter(count -> count > 0).count();
    }

    private static int edgeCount(TimetableStore store) {
        return nextStationCounts(store).sum();
    }

    private static IntStream nextStationCounts(TimetableStore store) {
        return IntStream.range(0, store.stations().size()).map(store::nextStationCount);
    }

    /** How long each served feed runs, sorted by source; empty before a load. */
    List<FeedTimespan> feedTimespans() {
        return served.feeds();
    }
}
