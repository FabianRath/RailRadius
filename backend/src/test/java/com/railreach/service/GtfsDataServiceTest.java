package com.railreach.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GtfsDataServiceTest {

    @TempDir
    static Path tempDir;

    static TransitGraph gtfs;

    @BeforeAll
    static void loadFixture() throws Exception {
        gtfs = GtfsTestFixtures.load(tempDir, "alpha", "beta");
    }

    @Test
    void stopIdsArePrefixedWithFeedSource() {
        assertThat(gtfs.getStation("alpha:A1").getName()).isEqualTo("Alpha Central");
        assertThat(gtfs.getStation("beta:B2").getName()).isEqualTo("Beta City");
    }

    @Test
    void feedFilesStartingWithAByteOrderMarkAreParsed() {
        // every beta file starts with a UTF-8 BOM
        assertThat(gtfs.getNeighbors("beta:B1")).containsExactly("beta:B2");
        assertThat(gtfs.getStopsOnTrip("beta:U1")).containsExactly("beta:B1", "beta:B2");
    }

    // B6
    @Test
    void suburbanRailwayTripsAreLoaded(@TempDir Path dir) throws Exception {
        TransitGraph graph = GtfsTestFixtures.load(dir, "epsilon");

        assertThat(graph.getStopsOnTrip("epsilon:S1")).containsExactly("epsilon:E4", "epsilon:E7");
        assertThat(graph.getStation("epsilon:E7")).isNotNull();
    }

    @Test
    void tripsStayWholeWhenStopTimesAreReadInSmallBatches(@TempDir Path dir) throws Exception {
        TransitGraph graph = GtfsTestFixtures.load(dir, 1, "alpha");

        assertThat(graph.getStopsOnTrip("alpha:T1")).containsExactly("alpha:A1", "alpha:A2", "alpha:A3");
        assertThat(graph.getConnectionByTrip("alpha:A2", "alpha:A3", "alpha:T1")).isNotNull();
    }

    @Test
    void interleavedStopTimesRowsSwitchOffBatching(@TempDir Path dir) throws Exception {
        TransitGraph graph = GtfsTestFixtures.load(dir, 2, "beta");

        assertThat(graph.getStopsOnTrip("beta:U1")).containsExactly("beta:B1", "beta:B2");
        assertThat(graph.getStopsOnTrip("beta:U2")).containsExactly("beta:B1", "beta:B2");
        assertThat(graph.getConnections("beta:B1", "beta:B2")).hasSize(3);
    }

    @Test
    void rowsOfATripAlreadyHandedToTheGraphAreDropped(@TempDir Path dir) throws Exception {
        // U1 is flushed with one stop before its second row turns up, so it never becomes a trip
        TransitGraph graph = GtfsTestFixtures.load(dir, 1, "beta");

        assertThat(graph.getStopsOnTrip("beta:U1")).isEmpty();
        assertThat(graph.getStopsOnTrip("beta:U2")).containsExactly("beta:B1", "beta:B2");
        assertThat(graph.getConnectionByTrip("beta:B1", "beta:B2", "beta:U1")).isNull();
    }

    @Test
    void nonRailRoutesProduceNoEdges() {
        assertThat(gtfs.getNeighbors("alpha:A1")).doesNotContain("alpha:A9");
        assertThat(gtfs.getNeighbors("alpha:A9")).isEmpty();
    }

    @Test
    void onlyStopsServedByRailTripsBecomeStations() {
        assertThat(gtfs.getStation("alpha:A1")).isNotNull();
        assertThat(gtfs.getStation("alpha:A9")).as("bus-only stop").isNull();
        assertThat(gtfs.getStation("alpha:AP")).as("unserved parent station").isNull();
        assertThat(gtfs.getStation("alpha:AE")).as("entrance").isNull();
    }

    @Test
    void sameNameNearbyStopsWithinFeedAreMerged() {
        // T1 serves A2b, which is merged into A2 (same name, ~50 m apart)
        assertThat(gtfs.getNeighbors("alpha:A1")).contains("alpha:A2");
        assertThat(gtfs.getStation("alpha:A2b")).isNull();
    }

    @Test
    void similarNearbyStationsAcrossFeedsAreMerged() {
        // "Border Town" (alpha, no outgoing edges) merges into "Border Town Station" (beta)
        assertThat(gtfs.getStation("alpha:A3")).isNull();
        assertThat(gtfs.getNeighbors("alpha:A2")).containsExactly("beta:B1");
        assertThat(gtfs.getNeighbors("beta:B1")).containsExactly("beta:B2");
        assertThat(gtfs.getStopsOnTrip("alpha:T1"))
                .containsExactly("alpha:A1", "alpha:A2", "beta:B1");
    }

    @Test
    void chainedMergesEndAtTheLastStation(@TempDir Path dir) throws Exception {
        // G2 merges into GX within gamma, then GX merges into delta's D1 across the border
        TransitGraph graph = GtfsTestFixtures.load(dir, "gamma", "delta");

        assertThat(graph.getStation("gamma:GX")).isNull();
        assertThat(graph.getStation("gamma:G2")).isNull();
        assertThat(graph.getTripsAtStop("gamma:GX")).isEmpty();
        assertThat(graph.getStopsOnTrip("gamma:V1")).containsExactly("gamma:G3", "delta:D1", "gamma:G4");
        assertThat(graph.getStopsOnTrip("gamma:V2")).containsExactly("gamma:G3", "delta:D1");
        assertThat(graph.getConnectionByTrip("gamma:G3", "delta:D1", "gamma:V2")).isNotNull();
        assertThat(graph.getNeighbors("gamma:G3")).containsExactly("delta:D1");
    }

    // B22
    @Test
    void crossFeedStationsVeryCloseTogetherAreMergedWhateverTheirNames(@TempDir Path dir) throws Exception {
        // "Gamma Border" and "Delta Grens" share no word but are ~40 m apart
        TransitGraph graph = GtfsTestFixtures.load(dir, "gamma", "delta");

        assertThat(graph.getStation("gamma:G5")).isNull();
        assertThat(graph.getStopsOnTrip("gamma:V3")).containsExactly("gamma:G4", "delta:D4", "gamma:G6");
    }

    @Test
    void crossFeedStationsFurtherApartStaySeparateWithoutSimilarNames(@TempDir Path dir) throws Exception {
        // "Gamma Sidings" and "Delta Yard" are ~330 m apart
        TransitGraph graph = GtfsTestFixtures.load(dir, "gamma", "delta");

        assertThat(graph.getStation("gamma:G6")).isNotNull();
        assertThat(graph.getStation("delta:D5")).isNotNull();
    }

    @Test
    void connectionsStoreTimesAsSecondsSinceMidnight() {
        var conn = gtfs.getConnectionByTrip("alpha:A1", "alpha:A2", "alpha:T1");
        assertThat(conn.getDepartureTimeSeconds()).isEqualTo(8 * 3600);
        assertThat(conn.getArrivalTimeSeconds()).isEqualTo(8 * 3600 + 30 * 60);
        assertThat(conn.getRouteName()).isEqualTo("RE1");
    }

    @Test
    void graphRestoredFromCacheEqualsTheParsedGraph() {
        TransitGraph restored = new TransitGraph();
        restored.restore(GtfsTestFixtures.cacheStore(tempDir).load().orElseThrow());

        assertThat(restored.getAllStations()).isEqualTo(gtfs.getAllStations());
        assertThat(restored.connectionCount()).isEqualTo(gtfs.connectionCount());
        for (String stopId : gtfs.getAllStations().keySet()) {
            assertThat(restored.getNeighbors(stopId)).isEqualTo(gtfs.getNeighbors(stopId));
            for (String neighbor : gtfs.getNeighbors(stopId)) {
                assertThat(restored.getConnections(stopId, neighbor))
                        .containsExactlyInAnyOrderElementsOf(gtfs.getConnections(stopId, neighbor));
            }
            assertThat(restored.getTripsAtStop(stopId)).isEqualTo(gtfs.getTripsAtStop(stopId));
            for (String tripId : gtfs.getTripsAtStop(stopId)) {
                assertThat(restored.getStopsOnTrip(tripId)).isEqualTo(gtfs.getStopsOnTrip(tripId));
            }
        }
    }

    @Test
    void cacheFileInAnOldFormatIsIgnored(@TempDir Path oldCacheDir) throws Exception {
        Files.writeString(oldCacheDir.resolve("gtfs-cache.json"), "{\"stations\":{},\"graph\":{},\"timestamp\":1}");
        assertThat(GtfsTestFixtures.cacheStore(oldCacheDir).load()).isEmpty();
    }
}
