package com.railreach.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

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
    // F16: rules from train-categories.txt
    @Test
    void bareTrainNumbersGetTheCategoryOfTheirRouteType(@TempDir Path dir) throws Exception {
        TransitGraph graph = GtfsTestFixtures.load(dir, "germany");

        assertThat(trainNumber(graph, "germany:GT1")).isEqualTo("ICE 2310");
        assertThat(trainNumber(graph, "germany:GT2")).isEqualTo("IC 2214");
        assertThat(trainNumber(graph, "germany:GT3")).isEqualTo("ECE 9");
        assertThat(trainNumber(graph, "germany:GT4")).isEqualTo("4711");
    }

    // F15: columns from train-category-columns.txt
    @Test
    void trainNumbersGetTheCategoryFromTheFeedsCategoryColumn(@TempDir Path dir) throws Exception {
        TransitGraph graph = GtfsTestFixtures.load(dir, "netherlands", "poland");

        assertThat(trainNumber(graph, "netherlands:NT1")).isEqualTo("Intercity 2835");
        assertThat(trainNumber(graph, "netherlands:NT2")).isEqualTo("5512");
        assertThat(trainNumber(graph, "poland:PT1")).isEqualTo("IC 1900/1 Zosia");
        // poland's trip_long_name names the route, not a category, and is not configured for it
        assertThat(trainNumber(graph, "poland:PT2")).isEqualTo("Os 50324");
    }

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
    void platformFarFromItsParentStationIsMergedIntoIt() {
        // T1 departs from platform A5, ~390 m from its parent_station A1: beyond every distance-based merge
        assertThat(gtfs.getStation("alpha:A5")).isNull();
        assertThat(gtfs.getNeighbors("alpha:A1")).contains("alpha:A2");
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

    // B28
    @Test
    void crossFeedNamesThatDifferOnlyInAccentsAreMerged(@TempDir Path dir) throws Exception {
        // gamma's G8 "Séta Halt" and delta's D7 "Seta Halt" are ~110 m apart and share no train
        TransitGraph graph = GtfsTestFixtures.load(dir, "gamma", "delta");

        assertThat(Stream.of("gamma:G8", "delta:D7").filter(id -> graph.getStation(id) != null)).hasSize(1);
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
    void crossFeedStationsSharingTrainsAreMergedWhateverTheirNames(@TempDir Path dir) throws Exception {
        // "Gamma Border" and "Delta Grens" are ~330 m apart; three trains leave both at the same minute for Twin Halt
        TransitGraph graph = GtfsTestFixtures.load(dir, "gamma", "delta");

        assertThat(Stream.of("gamma:G5", "delta:D4").filter(id -> graph.getStation(id) != null)).hasSize(1);
        assertThat(graph.getStopsOnTrip("gamma:V3").getFirst()).isEqualTo(graph.getStopsOnTrip("delta:W3").getFirst());
    }

    @Test
    void crossFeedStationsWithoutSharedTrainsOrSimilarNamesStaySeparate(@TempDir Path dir) throws Exception {
        // "Gamma Sidings" and "Delta Yard" are ~330 m apart; their trains to Twin Halt leave 5 min apart
        TransitGraph graph = GtfsTestFixtures.load(dir, "gamma", "delta");

        assertThat(graph.getStation("gamma:G6")).isNotNull();
        assertThat(graph.getStation("delta:D5")).isNotNull();
    }

    // B22
    @Test
    void stopsAbroadAreMergedIntoTheNearestLocalStation(@TempDir Path dir) throws Exception {
        // kappa's K3 "Lambda Gate" lies in lambda's country, ~700 m from lambda's L1 "Lambdastadt Ost"
        TransitGraph graph = GtfsTestFixtures.load(dir, "kappa", "lambda");

        assertThat(graph.getStation("kappa:K3")).isNull();
        assertThat(graph.getStopsOnTrip("kappa:KT1")).containsExactly("kappa:K1", "kappa:K2", "lambda:L1");
    }

    @Test
    void stopsAbroadWithoutALocalStationNearbyStay(@TempDir Path dir) throws Exception {
        // kappa's K4 "Lambda Field" lies in lambda's country, but ~13 km from any lambda station
        TransitGraph graph = GtfsTestFixtures.load(dir, "kappa", "lambda");

        assertThat(graph.getStation("kappa:K4")).isNotNull();
        assertThat(graph.getStation("kappa:K2")).as("stop at home").isNotNull();
    }

    @Test
    void stationsCarryTheCountryTheirCoordinatesLieIn(@TempDir Path dir) throws Exception {
        TransitGraph graph = GtfsTestFixtures.load(dir, "kappa", "lambda");

        assertThat(graph.getStation("kappa:K1").getCountry()).isEqualTo("KA");
        assertThat(graph.getStation("kappa:K4").getCountry()).as("stop abroad, not snapped").isEqualTo("LA");
        assertThat(graph.getStation("lambda:L1").getCountry()).isEqualTo("LA");
        assertThat(gtfs.getStation("alpha:A1").getCountry()).as("outside every border").isNull();
    }

    @Test
    void connectionsStoreTimesAsSecondsSinceMidnight() {
        var conn = gtfs.getConnectionByTrip("alpha:A1", "alpha:A2", "alpha:T1");
        assertThat(conn.getDepartureTimeSeconds()).isEqualTo(8 * 3600);
        assertThat(conn.getArrivalTimeSeconds()).isEqualTo(8 * 3600 + 30 * 60);
        assertThat(conn.getRouteName()).isEqualTo("RE1");
    }

    @Test
    void translatedNamesOfAMergedStationMoveToTheSurvivingStation() {
        assertThat(gtfs.getTranslatedNames("beta:B1")).containsExactly("Grenzstadt");
        assertThat(gtfs.getTranslatedNames("alpha:A3")).isEmpty();
    }

    @Test
    void translationsEqualToTheStationNameAreDropped(@TempDir Path dir) throws Exception {
        assertThat(GtfsTestFixtures.load(dir, "omega").getTranslatedNames("omega:O1"))
                .containsExactly("Cologne Main Station");
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
            assertThat(restored.getTranslatedNames(stopId)).isEqualTo(gtfs.getTranslatedNames(stopId));
            assertThat(restored.getTripsAtStop(stopId)).isEqualTo(gtfs.getTripsAtStop(stopId));
            for (String tripId : gtfs.getTripsAtStop(stopId)) {
                assertThat(restored.getStopsOnTrip(tripId)).isEqualTo(gtfs.getStopsOnTrip(tripId));
                assertThat(restored.getTripInfo(tripId)).isEqualTo(gtfs.getTripInfo(tripId));
            }
        }
    }

    @Test
    void serviceDaysOfTripsSurviveTheCache(@TempDir Path dir) throws Exception {
        TransitGraph parsed = GtfsTestFixtures.load(dir, "zeta");
        TransitGraph restored = new TransitGraph();
        restored.restore(GtfsTestFixtures.cacheStore(dir).load().orElseThrow());

        for (String tripId : parsed.getTripsAtStop("zeta:Z2")) {
            assertThat(restored.getServiceDays(tripId)).usingRecursiveComparison()
                    .isEqualTo(parsed.getServiceDays(tripId));
        }
        assertThat(restored.getServiceDays("zeta:ZT1").sharesDayWith(restored.getServiceDays("zeta:ZT3"), 0))
                .isTrue();
    }

    @Test
    void tripsOfAFeedWithoutCalendarRunEveryDay() {
        assertThat(gtfs.getServiceDays("alpha:T1")).isSameAs(ServiceDays.EVERY_DAY);
    }

    @Test
    void cacheFileInAnOldFormatIsIgnored(@TempDir Path oldCacheDir) throws Exception {
        Files.writeString(oldCacheDir.resolve("gtfs-cache.json"), "{\"stations\":{},\"graph\":{},\"timestamp\":1}");
        assertThat(GtfsTestFixtures.cacheStore(oldCacheDir).load()).isEmpty();
    }

    private static String trainNumber(TransitGraph graph, String tripId) {
        return graph.getTripInfo(tripId).orElseThrow().trainNumber();
    }
}
