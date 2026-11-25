package com.railradius.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class PastDaysTest {

    // Inside the calendar feeds' days (eta: January, zeta and theta: 5 Jan – 29 Mar 2026), and after all of them
    private static final List<LocalDate> LATER_DAYS = List.of(LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 28),
            LocalDate.of(2026, 3, 20), LocalDate.of(2026, 4, 1));
    // HT12 runs daily from 5 to 31 January: on 4 days from here on, fewer than a regular train needs
    private static final LocalDate ETA_LATE_JANUARY = LocalDate.of(2026, 1, 28);

    @TempDir
    Path tempDir;

    static List<List<String>> feedCombinations() {
        return SearchSnapshotTest.feedCombinations();
    }

    /**
     * Same result (#45): searched on a later day, a store loaded on that day, read back from the store file, or read
     * from the browser file, finds what the store with every day finds on that day.
     */
    @ParameterizedTest
    @MethodSource("feedCombinations")
    void aStoreWithoutPastDaysFindsTheSameResultOnEveryLaterDay(List<String> feeds) throws IOException {
        String[] feedFolders = feeds.toArray(String[]::new);
        BinaryCacheStore.BinaryCacheData everyDay = GtfsTestFixtures.network(tempDir.resolve("every-day"),
                feedFolders).servedData((int) GtfsTestFixtures.TODAY.toEpochDay());
        for (LocalDate today : LATER_DAYS) {
            Path dir = tempDir.resolve(today.toString());
            TransitGraph withEveryDay = TransitGraph.withToday(today);
            withEveryDay.serve(everyDay);
            String expected = sameResult(withEveryDay);

            TransitGraph loaded = GtfsTestFixtures.load(dir, today, feedFolders);
            TransitGraph readBack = TransitGraph.withToday(today);
            readBack.serve(GtfsTestFixtures.binaryCacheStore(dir).load().orElseThrow());
            Path browserFile = dir.resolve("browser-data.bin");
            BrowserDataFile.write(loaded.served(), browserFile);
            TransitGraph browser = TransitGraph.withToday(today);
            browser.serve(BrowserDataFile.read(browserFile));

            assertThat(sameResult(loaded)).as("loaded on %s", today).isEqualTo(expected);
            assertThat(sameResult(readBack)).as("read back on %s", today).isEqualTo(expected);
            assertThat(sameResult(browser)).as("browser file on %s", today).isEqualTo(expected);
        }
    }

    @Test
    void tripsWithNoDayFromTodayOnAreGoneAndTheRestKeepOnlyTheirDaysFromToday() throws IOException {
        int today = (int) ETA_LATE_JANUARY.toEpochDay();
        TimetableStore everyDay = GtfsTestFixtures.network(tempDir.resolve("every-day"), "eta", "zeta")
                .servedData((int) GtfsTestFixtures.TODAY.toEpochDay()).store();

        TimetableStore store = GtfsTestFixtures.load(tempDir.resolve("late"), ETA_LATE_JANUARY, "eta", "zeta")
                .timetableStore();

        assertThat(store.tripCount()).isLessThan(everyDay.tripCount());
        for (int trip = 0; trip < store.tripCount(); trip++) {
            ServiceDays days = store.dayPatterns().days(store.dayPattern(trip));
            assertThat(days.isEmpty()).as(store.tripId(trip)).isFalse();
            assertThat(days.epochDays().min().orElseThrow()).as(store.tripId(trip)).isGreaterThanOrEqualTo(today);
        }
        List<String> keptIds = IntStream.range(0, store.tripCount()).mapToObj(store::tripId).toList();
        // SPLITA and SPLITB end on 15 January, ZT1 (weekdays) runs until 27 March
        assertThat(keptIds).doesNotContain("eta:HT6", "eta:HT7", "eta:HT9", "eta:HT10").contains("zeta:ZT1");
    }

    // Counted over its 27 January days, HT12's departure at H1 is regular; counted from the 28th on, it wouldn't be
    @Test
    void aDepartureRegularOnlyWithItsPastDaysStaysRegular() throws IOException {
        TransitGraph graph = GtfsTestFixtures.load(tempDir, ETA_LATE_JANUARY, "eta");

        CompactResult result = GtfsTestFixtures.search(graph).search("eta:H1", 0, 0, 180,
                CompactReachabilitySearch.NO_DEADLINE, round -> { });

        assertThat(result.paths("eta:H7")).containsExactly(new CompactResult.PathSummary(0, 30));
    }

    // All of zeta's days are past on 1 April: every trip goes, but the counts for search ranking and /stats stay
    @Test
    void stationCountsAreTakenBeforeThePastDaysAreDropped() throws IOException {
        TransitGraph everyDay = GtfsTestFixtures.load(tempDir.resolve("every-day"), "zeta");

        TransitGraph allPast = GtfsTestFixtures.load(tempDir.resolve("all-past"), LocalDate.of(2026, 4, 1), "zeta");

        assertThat(allPast.timetableStore().tripCount()).isZero();
        assertThat(allPast.getStats()).isEqualTo(everyDay.getStats());
        for (String stopId : everyDay.getAllStations().keySet()) {
            assertThat(allPast.stoppingTripCount(stopId)).as(stopId).isEqualTo(everyDay.stoppingTripCount(stopId));
            assertThat(allPast.nextStationCount(stopId)).as(stopId).isEqualTo(everyDay.nextStationCount(stopId));
        }
    }

    private static String sameResult(TransitGraph graph) throws IOException {
        return SearchSnapshotTest.dump(graph, SearchSnapshot.RideFormat.STATIONS_AND_TIMES);
    }
}
