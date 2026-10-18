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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompactReachabilitySearchTest {

    private static final String START_ID = "alpha:A1";
    private static final int A_DAY_MINUTES = 24 * 60;
    private static final int LONGEST_MIN_WAIT_MINUTES = 1300;

    @Test
    void pathsHandedOutAfterEachRoundAddUpToTheResult(@TempDir Path tempDir) throws IOException {
        CompactReachabilitySearch search =
                GtfsTestFixtures.search(GtfsTestFixtures.load(tempDir, "alpha", "beta"));
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

    // V1 reaches D1 at 08:30, W1 leaves it at 08:00: under an hour earlier is no 23.5 h wait past midnight. The
    // minimum wait rules out V2 (10:30) → W1 (21.5 h) but not V2 → W2 at 09:00 (22.5 h). Waits this long are beyond
    // the API's ceiling (SearchRequestValidator), so this calls the search directly.
    @Test
    void trainLeavingLessThanAnHourBeforeTheArrivalIsNotCaughtTheNextDay(@TempDir Path tempDir) throws IOException {
        CompactReachabilitySearch search =
                GtfsTestFixtures.search(GtfsTestFixtures.load(tempDir, "gamma", "delta"));

        CompactResult result = search.search("gamma:G3", 1, LONGEST_MIN_WAIT_MINUTES, A_DAY_MINUTES, round -> { });

        assertThat(result.paths("delta:D3")).isNotEmpty();
        assertThat(result.paths("delta:D2")).isEmpty();
    }

    @Test
    void searchPastItsDeadlineStopsBeforeItsNextRound(@TempDir Path tempDir) throws IOException {
        CompactReachabilitySearch search =
                new CompactReachabilitySearch(GtfsTestFixtures.load(tempDir, "alpha", "beta"), 0);
        List<Map<String, CompactResult.PathSummary>> rounds = new ArrayList<>();

        assertThatThrownBy(() -> search.search(START_ID, 3, 0, 180, rounds::add))
                .isInstanceOf(CompactReachabilitySearch.DeadlineExceededException.class);
        assertThat(rounds).allMatch(round -> round.values().stream().allMatch(path -> path.transfers() == 0));
    }

    // OT2 runs back into O1, but the start is no arrival to transfer from or to hand out
    @Test
    void startStationKeepsOnlyItsEmptyPathThoughATrainReturnsToIt(@TempDir Path tempDir) throws IOException {
        CompactReachabilitySearch search = GtfsTestFixtures.search(GtfsTestFixtures.load(tempDir, "omega"));
        List<Map<String, CompactResult.PathSummary>> rounds = new ArrayList<>();

        CompactResult result = search.search("omega:O1", 2, 0, 180, rounds::add);

        assertThat(rounds).noneMatch(round -> round.containsKey("omega:O1"));
        assertThat(result.paths("omega:O1")).containsExactly(new CompactResult.PathSummary(0, 0));
        assertThat(result.journey("omega:O1", 0).path().getConnections()).isEmpty();
    }
}
