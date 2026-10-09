package com.railreach.service;

import com.railreach.model.JourneyPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReachabilitySearchTest {

    private static final String START_ID = "alpha:A1";

    @Test
    void pathsHandedOutAfterEachRoundAddUpToTheResult(@TempDir Path tempDir) throws IOException {
        ReachabilitySearch search = new ReachabilitySearch(GtfsTestFixtures.load(tempDir, "alpha", "beta"));
        List<Map<String, List<JourneyPath>>> rounds = new ArrayList<>();

        Map<String, List<JourneyPath>> result = search.search(START_ID, 3, 0, 180, rounds::add);

        Map<String, List<JourneyPath>> handedOut = new HashMap<>();
        int previousTransfers = -1;
        for (Map<String, List<JourneyPath>> round : rounds) {
            List<Integer> transfers = round.values().stream().flatMap(List::stream)
                    .map(JourneyPath::getTransfers).distinct().toList();
            assertThat(transfers).hasSize(1);
            assertThat(transfers.getFirst()).isGreaterThan(previousTransfers);
            previousTransfers = transfers.getFirst();
            round.forEach((stopId, paths) -> handedOut.computeIfAbsent(stopId, id -> new ArrayList<>()).addAll(paths));
        }
        result.remove(START_ID);
        assertThat(handedOut).isEqualTo(result);
    }

    // OT2 runs back into O1, but the start is no arrival to transfer from or to hand out
    @Test
    void startStationKeepsOnlyItsEmptyPathThoughATrainReturnsToIt(@TempDir Path tempDir) throws IOException {
        ReachabilitySearch search = new ReachabilitySearch(GtfsTestFixtures.load(tempDir, "omega"));
        List<Map<String, List<JourneyPath>>> rounds = new ArrayList<>();

        Map<String, List<JourneyPath>> result = search.search("omega:O1", 2, 0, 180, rounds::add);

        assertThat(rounds).noneMatch(round -> round.containsKey("omega:O1"));
        assertThat(result.get("omega:O1")).singleElement()
                .satisfies(path -> assertThat(path.getConnections()).isEmpty());
    }
}
