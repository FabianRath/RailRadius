package com.railreach.service;

import com.railreach.model.Station;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

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
    void connectionsStoreTimesAsSecondsSinceMidnight() {
        var conn = gtfs.getConnectionByTrip("alpha:A1", "alpha:A2", "alpha:T1");
        assertThat(conn.getDepartureTimeSeconds()).isEqualTo(8 * 3600);
        assertThat(conn.getArrivalTimeSeconds()).isEqualTo(8 * 3600 + 30 * 60);
        assertThat(conn.getRouteName()).isEqualTo("RE1");
    }

    @Test
    void searchSkipsStationsWithoutOutgoingEdges() {
        List<Station> results = gtfs.searchStations("alpha", 10);
        assertThat(results).extracting(Station::getId)
                .contains("alpha:A1", "alpha:A2", "alpha:A4")
                .doesNotContain("alpha:A9");
    }
}
