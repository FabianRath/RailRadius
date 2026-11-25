package com.railradius.service;

import com.railradius.model.Station;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompactReachabilitySearchTest {

    private static final String START_ID = "alpha:A1";
    private static final int A_DAY_MINUTES = 24 * 60;
    private static final int LONGEST_MIN_WAIT_MINUTES = 1300;
    // One more than a 16-bit station index holds, and one past that for the trip's last stop
    private static final int WIDE_STATION_COUNT = Character.MAX_VALUE + 3;
    private static final int NO_LEG = TimetableStore.NO_LEG;

    @Test
    void pathsHandedOutAfterEachRoundAddUpToTheResult(@TempDir Path tempDir) throws IOException {
        CompactReachabilitySearch search =
                GtfsTestFixtures.search(GtfsTestFixtures.load(tempDir, "alpha", "beta"));
        List<Map<String, CompactResult.PathSummary>> rounds = new ArrayList<>();

        CompactResult result = search.search(START_ID, 3, 0, 180,
                CompactReachabilitySearch.NO_DEADLINE, rounds::add);

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

        CompactResult result = search.search("gamma:G3", 1, LONGEST_MIN_WAIT_MINUTES, A_DAY_MINUTES,
                CompactReachabilitySearch.NO_DEADLINE, round -> { });

        assertThat(result.paths("delta:D3")).isNotEmpty();
        assertThat(result.paths("delta:D2")).isEmpty();
    }

    @Test
    void searchPastItsDeadlineStopsBeforeItsNextRound(@TempDir Path tempDir) throws IOException {
        CompactReachabilitySearch search =
                GtfsTestFixtures.search(GtfsTestFixtures.load(tempDir, "alpha", "beta"));
        List<Map<String, CompactResult.PathSummary>> rounds = new ArrayList<>();

        assertThatThrownBy(() -> search.search(START_ID, 3, 0, 180, Duration.ZERO, rounds::add))
                .isInstanceOfSatisfying(SearchRefusal.class,
                        refusal -> assertThat(refusal.refusalReason()).isEqualTo(SearchRefusal.Reason.TOO_SLOW));
        assertThat(rounds).allMatch(round -> round.values().stream().allMatch(path -> path.transfers() == 0));
    }

    // OT2 runs back into O1, but the start is no arrival to transfer from or to hand out
    @Test
    void startStationKeepsOnlyItsEmptyPathThoughATrainReturnsToIt(@TempDir Path tempDir) throws IOException {
        CompactReachabilitySearch search = GtfsTestFixtures.search(GtfsTestFixtures.load(tempDir, "omega"));
        List<Map<String, CompactResult.PathSummary>> rounds = new ArrayList<>();

        CompactResult result = search.search("omega:O1", 2, 0, 180,
                CompactReachabilitySearch.NO_DEADLINE, rounds::add);

        assertThat(rounds).noneMatch(round -> round.containsKey("omega:O1"));
        assertThat(result.paths("omega:O1")).containsExactly(new CompactResult.PathSummary(0, 0));
        assertThat(result.journey("omega:O1", 0).path().getConnections()).isEmpty();
    }

    // Stations 65536 and 65537 don't fit 16 bits: cut to them, they would be stations 0 and 1 (#37)
    @Test
    void aTripToStationsBeyondSixteenBitsReachesThemThere() {
        List<String> ids = IntStream.range(0, WIDE_STATION_COUNT).mapToObj(index -> "x:S" + index).toList();
        DayPatterns.Builder dayPatterns = new DayPatterns.Builder();
        int everyDay = dayPatterns.intern(ServiceDays.EVERY_DAY);
        TimetableStore store = TimetableStore.of(TimetableStoreBuilder.withoutTripNames(StationTable.of(ids),
                new int[] {0, 3}, new int[] {65535, 65536, 65537}, new int[] {3600, 4260, NO_LEG},
                new int[] {NO_LEG, 4200, 4800}, dayPatterns.build(), new int[] {everyDay}));
        TransitGraph graph = TransitGraph.withToday(GtfsTestFixtures.TODAY);
        graph.serve(new BrowserDataFile.BrowserData(
                ids.stream().map(id -> Station.builder().id(id).build()).toList(), store, ""));

        CompactResult result = GtfsTestFixtures.search(graph).search("x:S65535", 0, 0, 180,
                CompactReachabilitySearch.NO_DEADLINE, round -> { });

        assertThat(result.paths("x:S65536")).containsExactly(new CompactResult.PathSummary(0, 10));
        assertThat(result.paths("x:S65537")).containsExactly(new CompactResult.PathSummary(0, 20));
        assertThat(result.paths("x:S0")).isEmpty();
        assertThat(result.paths("x:S1")).isEmpty();
    }
}
