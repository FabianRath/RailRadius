package com.railradius.service;

import com.railradius.model.Connection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class GtfsDataServiceTest {

    @TempDir
    static Path tempDir;

    static TransitGraph gtfs;
    // The same feeds before the store is built: their trips, connections and edges
    static RailNetwork network;

    @BeforeAll
    static void loadFixture() throws Exception {
        gtfs = GtfsTestFixtures.load(tempDir.resolve("graph"), "alpha", "beta");
        network = GtfsTestFixtures.network(tempDir.resolve("network"), "alpha", "beta");
    }

    // F25: a fresh checkout starts without zips, so the feeds can be downloaded in the running app
    @Test
    void startupWithoutZipsLoadsNothingInsteadOfFailing(@TempDir Path dir) throws Exception {
        TransitGraph graph = new TransitGraph();
        GtfsDataService service = GtfsTestFixtures.dataService(dir, graph,
                GtfsTestFixtures.reachabilityCache(), new StationSearchIndex(graph));
        ReflectionTestUtils.setField(service, "gtfsDirectory", dir.resolve("missing").toString());
        ReflectionTestUtils.setField(service, "autoLoadFromFiles", true);

        service.initialize();

        assertThat(graph.stationCount()).isZero();
        assertThat(service.getCacheStats()).containsEntry("dataLoaded", false);
    }

    // S21: the binary cache is all a start needs; with it, the zips are not read
    @Test
    void aLoadFromTheZipsWritesTheBinaryCacheTheNextStartReads(@TempDir Path dir) throws Exception {
        TransitGraph parsed = new TransitGraph();
        GtfsTestFixtures.dataService(dir, parsed, "alpha", "beta").loadFromFiles();
        assertThat(dir.resolve("gtfs-store.bin")).exists();

        TransitGraph fromBinary = new TransitGraph();
        GtfsDataService service = GtfsTestFixtures.dataService(dir, fromBinary);
        ReflectionTestUtils.setField(service, "gtfsDirectory", dir.resolve("missing").toString());
        service.initialize();

        assertThat(fromBinary.getAllStations()).isEqualTo(parsed.getAllStations());
        assertThat(service.getCacheStats()).containsEntry("dataLoaded", true)
                .containsEntry("connectionCount", parsed.edgeCount());
    }

    @Test
    void stopIdsArePrefixedWithFeedSource() {
        assertThat(gtfs.getStation("alpha:A1").getName()).isEqualTo("Alpha Central");
        assertThat(gtfs.getStation("beta:B2").getName()).isEqualTo("Beta City");
    }

    @Test
    void feedFilesStartingWithAByteOrderMarkAreParsed() {
        // every beta file starts with a UTF-8 BOM
        assertThat(network.getNeighbors("beta:B1")).containsExactly("beta:B2");
        assertThat(network.getStopsOnTrip("beta:U1")).containsExactly("beta:B1", "beta:B2");
    }

    // B35: the ÖBB zip keeps its files in a folder GTFS_Fahrplan_2026/
    @Test
    void feedWithItsFilesInAFolderIsLoaded(@TempDir Path dir) throws Exception {
        TransitGraph graph = new TransitGraph();
        GtfsDataService service = GtfsTestFixtures.dataService(dir, graph,
                GtfsTestFixtures.reachabilityCache(), new StationSearchIndex(graph), "zeta");
        GtfsTestFixtures.zipFeedInFolder("zeta", dir.resolve("gtfs").resolve("gtfs_zeta.zip"), "zeta_feed");

        service.loadFromFiles();

        assertThat(graph.getStation("zeta:Z1").getName()).isEqualTo("Zeta Start");
        assertThat(graph.getTripInfo("zeta:ZT1")).isPresent();
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
        RailNetwork epsilon = GtfsTestFixtures.network(dir, "epsilon");

        assertThat(epsilon.getStopsOnTrip("epsilon:S1")).containsExactly("epsilon:E4", "epsilon:E7");
        assertThat(epsilon.getStation("epsilon:E7")).isNotNull();
    }

    @Test
    void trainsThatRunOnFewerThanEightDaysAreNotLoaded(@TempDir Path dir) throws Exception {
        RailNetwork eta = GtfsTestFixtures.network(dir, "eta");

        assertThat(eta.tripIds()).doesNotContain("eta:HT5");
        assertThat(eta.getStation("eta:H4")).isNull();
        assertThat(eta.tripIds()).contains("eta:HT2");
    }

    @Test
    void daysOfATrainSplitIntoSeveralTripsAreCountedTogether(@TempDir Path dir) throws Exception {
        RailNetwork eta = GtfsTestFixtures.network(dir, "eta");

        assertThat(eta.tripIds()).contains("eta:HT6");
        assertThat(eta.tripIds()).contains("eta:HT7");
    }

    @Test
    void trainsWithoutNumberThatRunOnFewerThanEightDaysAreNotLoaded(@TempDir Path dir) throws Exception {
        RailNetwork eta = GtfsTestFixtures.network(dir, "eta");

        assertThat(eta.tripIds()).doesNotContain("eta:HT8");
        assertThat(eta.getStation("eta:H6")).isNull();
    }

    // B33: HT11 has the number of the daily HT1 but leaves at another time on 2 days
    @Test
    void rareTimingVariantOfARegularTrainIsNotLoaded(@TempDir Path dir) throws Exception {
        RailNetwork eta = GtfsTestFixtures.network(dir, "eta");

        assertThat(eta.tripIds()).doesNotContain("eta:HT11");
        assertThat(eta.tripIds()).contains("eta:HT1");
    }

    // B32: without a number, trips of one route leaving the same stop at the same time are one train
    @Test
    void tripsWithoutNumberLeavingTogetherAreCountedAsOneTrain(@TempDir Path dir) throws Exception {
        RailNetwork eta = GtfsTestFixtures.network(dir, "eta");

        assertThat(eta.tripIds()).contains("eta:HT9");
        assertThat(eta.tripIds()).contains("eta:HT10");
    }

    @Test
    void tripsStayWholeWhenStopTimesAreReadInSmallBatches(@TempDir Path dir) throws Exception {
        RailNetwork alpha = GtfsTestFixtures.network(dir, 1, "alpha");

        assertThat(alpha.getStopsOnTrip("alpha:T1")).containsExactly("alpha:A1", "alpha:A2", "alpha:A3");
        assertThat(connection(alpha, "alpha:A2", "alpha:A3", "alpha:T1")).isPresent();
    }

    @Test
    void interleavedStopTimesRowsSwitchOffBatching(@TempDir Path dir) throws Exception {
        RailNetwork beta = GtfsTestFixtures.network(dir, 2, "beta");

        assertThat(beta.getStopsOnTrip("beta:U1")).containsExactly("beta:B1", "beta:B2");
        assertThat(beta.getStopsOnTrip("beta:U2")).containsExactly("beta:B1", "beta:B2");
        assertThat(beta.getConnections("beta:B1", "beta:B2")).hasSize(3);
    }

    @Test
    void rowsOfATripAlreadyHandedToTheGraphAreDropped(@TempDir Path dir) throws Exception {
        // U1 is flushed with one stop before its second row turns up, so it never becomes a trip
        RailNetwork beta = GtfsTestFixtures.network(dir, 1, "beta");

        assertThat(beta.getStopsOnTrip("beta:U1")).isEmpty();
        assertThat(beta.getStopsOnTrip("beta:U2")).containsExactly("beta:B1", "beta:B2");
        assertThat(connection(beta, "beta:B1", "beta:B2", "beta:U1")).isEmpty();
    }

    @Test
    void nonRailRoutesProduceNoEdges() {
        assertThat(network.getNeighbors("alpha:A1")).doesNotContain("alpha:A9");
        assertThat(network.getNeighbors("alpha:A9")).isEmpty();
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
        assertThat(network.getNeighbors("alpha:A1")).contains("alpha:A2");
        assertThat(gtfs.getStation("alpha:A2b")).isNull();
    }

    @Test
    void platformFarFromItsParentStationIsMergedIntoIt() {
        // T1 departs from platform A5, ~390 m from its parent_station A1: beyond every distance-based merge
        assertThat(gtfs.getStation("alpha:A5")).isNull();
        assertThat(network.getNeighbors("alpha:A1")).contains("alpha:A2");
    }

    @Test
    void similarNearbyStationsAcrossFeedsAreMerged() {
        // "Border Town" (alpha, no outgoing edges) merges into "Border Town Station" (beta)
        assertThat(gtfs.getStation("alpha:A3")).isNull();
        assertThat(network.getNeighbors("alpha:A2")).containsExactly("beta:B1");
        assertThat(network.getNeighbors("beta:B1")).containsExactly("beta:B2");
        assertThat(network.getStopsOnTrip("alpha:T1"))
                .containsExactly("alpha:A1", "alpha:A2", "beta:B1");
    }

    // B28
    @Test
    void crossFeedNamesThatDifferOnlyInAccentsAreMerged(@TempDir Path dir) throws Exception {
        // gamma's G8 "Séta Halt" and delta's D7 "Seta Halt" are ~110 m apart and share no train
        TransitGraph graph = GtfsTestFixtures.load(dir, "gamma", "delta");

        assertThat(Stream.of("gamma:G8", "delta:D7").filter(id -> graph.getStation(id) != null)).hasSize(1);
    }

    // B29
    @Test
    void crossFeedStationWhoseNameIsPartOfTheOtherIsMergedWhenVeryNear(@TempDir Path dir) throws Exception {
        // gamma's G9 "Lake" and delta's D8 "Lake Upper Pier Road" are ~55 m apart and share no train
        TransitGraph graph = GtfsTestFixtures.load(dir, "gamma", "delta");

        assertThat(Stream.of("gamma:G9", "delta:D8").filter(id -> graph.getStation(id) != null)).hasSize(1);
    }

    @Test
    void crossFeedStationWhoseNameIsPartOfTheOtherStaysSeparateBeyond100Metres(@TempDir Path dir) throws Exception {
        // gamma's G10 "Hill" and delta's D9 "Hill Upper Park Road" are ~130 m apart
        TransitGraph graph = GtfsTestFixtures.load(dir, "gamma", "delta");

        assertThat(graph.getStation("gamma:G10")).isNotNull();
        assertThat(graph.getStation("delta:D9")).isNotNull();
    }

    @Test
    void chainedMergesEndAtTheLastStation(@TempDir Path dir) throws Exception {
        // G2 merges into GX within gamma, then GX merges into delta's D1 across the border
        RailNetwork merged = GtfsTestFixtures.network(dir, "gamma", "delta");

        assertThat(merged.getStation("gamma:GX")).isNull();
        assertThat(merged.getStation("gamma:G2")).isNull();
        assertThat(merged.getTripsAtStop("gamma:GX")).isEmpty();
        assertThat(merged.getStopsOnTrip("gamma:V1")).containsExactly("gamma:G3", "delta:D1", "gamma:G4");
        assertThat(merged.getStopsOnTrip("gamma:V2")).containsExactly("gamma:G3", "delta:D1");
        assertThat(connection(merged, "gamma:G3", "delta:D1", "gamma:V2")).isPresent();
        assertThat(merged.getNeighbors("gamma:G3")).containsExactly("delta:D1");
    }

    // B22
    @Test
    void crossFeedStationsSharingTrainsAreMergedWhateverTheirNames(@TempDir Path dir) throws Exception {
        // "Gamma Border" and "Delta Grens" are ~330 m apart; three trains leave both at the same minute for Twin Halt
        RailNetwork merged = GtfsTestFixtures.network(dir, "gamma", "delta");

        assertThat(Stream.of("gamma:G5", "delta:D4").filter(id -> merged.getStation(id) != null)).hasSize(1);
        assertThat(merged.getStopsOnTrip("gamma:V3").getFirst())
                .isEqualTo(merged.getStopsOnTrip("delta:W3").getFirst());
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
        RailNetwork merged = GtfsTestFixtures.network(dir, "kappa", "lambda");

        assertThat(merged.getStation("kappa:K3")).isNull();
        assertThat(merged.getStopsOnTrip("kappa:KT1")).containsExactly("kappa:K1", "kappa:K2", "lambda:L1");
    }

    @Test
    void stopsAbroadWithoutALocalStationNearbyStay(@TempDir Path dir) throws Exception {
        // kappa's K4 "Lambda Field" lies in lambda's country, but ~2 km from lambda's nearest station, L4 "Lambda
        // Field Ost", whose name is only similar
        TransitGraph graph = GtfsTestFixtures.load(dir, "kappa", "lambda");

        assertThat(graph.getStation("kappa:K4")).isNotNull();
        assertThat(graph.getStation("kappa:K2")).as("stop at home").isNotNull();
    }

    // B30
    @Test
    void stopsAbroadAreMergedIntoALocalStationOfTheSameNameAFewKilometresAway(@TempDir Path dir) throws Exception {
        // kappa's K6 "Lambda Dorf (LA)" lies in lambda's country, ~2 km from lambda's L3 "Lambda Dorf"
        RailNetwork merged = GtfsTestFixtures.network(dir, "kappa", "lambda");

        assertThat(merged.getStation("kappa:K6")).isNull();
        assertThat(merged.getStopsOnTrip("kappa:KT3")).containsExactly("kappa:K7", "lambda:L3");
    }

    // B30
    @Test
    void stationsWithTheSameStationCodeMergeThoughOverAKilometreApart(@TempDir Path dir) throws Exception {
        // UIC 8714621 in both feeds, the Swiss feed's stop ~1.1 km off
        RailNetwork merged = GtfsTestFixtures.network(dir, "france", "switzerland");

        assertThat(merged.getStation("switzerland:8714621")).isNull();
        assertThat(merged.getStopsOnTrip("switzerland:ST1"))
                .containsExactly("switzerland:8500010", "france:StopArea:OCE87146217");
    }

    @Test
    void stationsWithTheSameStationCodeFarApartStaySeparate(@TempDir Path dir) throws Exception {
        // UIC 8799999 in both feeds, ~11 km apart
        TransitGraph graph = GtfsTestFixtures.load(dir, "france", "switzerland");

        assertThat(graph.getStation("switzerland:8799999")).isNotNull();
        assertThat(graph.getStation("france:StopArea:OCE87999995")).isNotNull();
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
        var conn = connection(network, "alpha:A1", "alpha:A2", "alpha:T1").orElseThrow();
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
    void parsedTripsShareTheStationsIdStrings() {
        for (String tripId : network.tripIds()) {
            for (String stopId : network.getStopsOnTrip(tripId)) {
                assertThat(stopId).isSameAs(network.getStation(stopId).getId());
            }
        }
    }

    @Test
    void tripsOfAFeedWithoutCalendarRunEveryDay() {
        assertThat(network.getServiceDays("alpha:T1")).isSameAs(ServiceDays.EVERY_DAY);
    }

    private static String trainNumber(TransitGraph graph, String tripId) {
        return graph.getTripInfo(tripId).orElseThrow().trainNumber();
    }

    private static Optional<Connection> connection(RailNetwork network, String fromId, String toId, String tripId) {
        return network.getConnections(fromId, toId).stream().filter(c -> c.getTripId().equals(tripId)).findFirst();
    }
}
