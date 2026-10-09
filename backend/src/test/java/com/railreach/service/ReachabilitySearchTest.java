package com.railreach.service;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Both search engines. */
class ReachabilitySearchTest {

    private static final String START_ID = "alpha:A1";

    @ParameterizedTest
    @ValueSource(strings = {SearchEngine.LEGACY, SearchEngine.COMPACT})
    void pathsHandedOutAfterEachRoundAddUpToTheResult(String engine, @TempDir Path tempDir) throws IOException {
        SearchEngine search = SearchEngine.named(engine, GtfsTestFixtures.load(tempDir, "alpha", "beta"));
        List<Map<String, SearchResult.PathSummary>> rounds = new ArrayList<>();

        SearchResult result = search.search(START_ID, 3, 0, 180, rounds::add);

        Map<String, List<SearchResult.PathSummary>> handedOut = new HashMap<>();
        int previousTransfers = -1;
        for (Map<String, SearchResult.PathSummary> round : rounds) {
            List<Integer> transfers = round.values().stream()
                    .map(SearchResult.PathSummary::transfers).distinct().toList();
            assertThat(transfers).hasSize(1);
            assertThat(transfers.getFirst()).isGreaterThan(previousTransfers);
            previousTransfers = transfers.getFirst();
            round.forEach((stopId, path) -> handedOut.computeIfAbsent(stopId, id -> new ArrayList<>()).add(path));
        }
        Map<String, List<SearchResult.PathSummary>> found = new HashMap<>();
        result.forEachStation(found::put);
        found.remove(START_ID);
        assertThat(handedOut).isEqualTo(found);
    }

    // OT2 runs back into O1, but the start is no arrival to transfer from or to hand out
    @ParameterizedTest
    @ValueSource(strings = {SearchEngine.LEGACY, SearchEngine.COMPACT})
    void startStationKeepsOnlyItsEmptyPathThoughATrainReturnsToIt(String engine, @TempDir Path tempDir)
            throws IOException {
        SearchEngine search = SearchEngine.named(engine, GtfsTestFixtures.load(tempDir, "omega"));
        List<Map<String, SearchResult.PathSummary>> rounds = new ArrayList<>();

        SearchResult result = search.search("omega:O1", 2, 0, 180, rounds::add);

        assertThat(rounds).noneMatch(round -> round.containsKey("omega:O1"));
        assertThat(result.paths("omega:O1")).containsExactly(new SearchResult.PathSummary(0, 0));
        assertThat(result.journey("omega:O1", 0).path().getConnections()).isEmpty();
    }

    // The compact engine builds journeys from the timetable store, the legacy one keeps the graph's connections
    @ParameterizedTest
    @MethodSource("com.railreach.service.SearchSnapshotTest#feedCombinations")
    void bothEnginesGiveTheSameJourneys(List<String> feeds, @TempDir Path tempDir) throws IOException {
        TransitGraph graph = GtfsTestFixtures.load(tempDir, feeds.toArray(String[]::new));
        SearchEngine legacy = SearchEngine.named(SearchEngine.LEGACY, graph);
        SearchEngine compact = SearchEngine.named(SearchEngine.COMPACT, graph);

        for (String startId : graph.getAllStations().keySet()) {
            assertThat(GtfsTestFixtures.journeys(compact.search(startId, 3, 0, 180, round -> {}))).as(startId)
                    .isEqualTo(GtfsTestFixtures.journeys(legacy.search(startId, 3, 0, 180, round -> {})));
        }
    }
}
