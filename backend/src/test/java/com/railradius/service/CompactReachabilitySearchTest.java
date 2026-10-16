package com.railradius.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompactReachabilitySearchTest {

    private static final String START_ID = "alpha:A1";

    @Test
    void pathsHandedOutAfterEachRoundAddUpToTheResult(@TempDir Path tempDir) throws IOException {
        CompactReachabilitySearch search =
                new CompactReachabilitySearch(GtfsTestFixtures.load(tempDir, "alpha", "beta"));
        List<Map<String, CompactResult.PathSummary>> rounds = new ArrayList<>();

        CompactResult result = search.search(START_ID, 3, 0, 180, rounds::add);

        Map<String, List<CompactResult.PathSummary>> handedOut = new HashMap<>();
        int previousTransfers = -1;
        for (Map<String, CompactResult.PathSummary> round : rounds) {
            List<Integer> transfers = round.values().stream()
                    .map(CompactResult.PathSummary::transfers).distinct().toList();
            assertThat(transfers).hasSize(1);
            assertThat(transfers.getFirst()).isGreaterThan(previousTransfers);
            previousTransfers = transfers.getFirst();
            round.forEach((stopId, path) -> handedOut.computeIfAbsent(stopId, id -> new ArrayList<>()).add(path));
        }
        Map<String, List<CompactResult.PathSummary>> found = new HashMap<>();
        result.forEachStation(found::put);
        found.remove(START_ID);
        assertThat(handedOut).isEqualTo(found);
    }

    // OT2 runs back into O1, but the start is no arrival to transfer from or to hand out
    @Test
    void startStationKeepsOnlyItsEmptyPathThoughATrainReturnsToIt(@TempDir Path tempDir) throws IOException {
        CompactReachabilitySearch search = new CompactReachabilitySearch(GtfsTestFixtures.load(tempDir, "omega"));
        List<Map<String, CompactResult.PathSummary>> rounds = new ArrayList<>();

        CompactResult result = search.search("omega:O1", 2, 0, 180, rounds::add);

        assertThat(rounds).noneMatch(round -> round.containsKey("omega:O1"));
        assertThat(result.paths("omega:O1")).containsExactly(new CompactResult.PathSummary(0, 0));
        assertThat(result.journey("omega:O1", 0).path().getConnections()).isEmpty();
    }
}
