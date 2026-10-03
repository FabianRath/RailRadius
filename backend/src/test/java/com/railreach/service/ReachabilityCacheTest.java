package com.railreach.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReachabilityCacheTest {

    private static final String START_ID = "alpha:A1";
    private static final int MAX_TRANSFERS = 3;

    @TempDir
    static Path tempDir;

    static TransitGraph gtfs;

    @BeforeAll
    static void loadFixture() throws Exception {
        gtfs = GtfsTestFixtures.load(tempDir, "alpha", "beta");
    }

    // B20: a shortcut such as hub routing must never return fewer stations than the full search
    @Test
    void everyTransferCountReachesTheSameStationsAsTheFullSearch() {
        ReachabilitySearch search = new ReachabilitySearch(gtfs);
        // Kept across transfer counts, so each count is seeded from the stored result of the one below
        ReachabilityCache incremental = GtfsTestFixtures.reachabilityCache(gtfs);

        for (int transfers = 0; transfers <= MAX_TRANSFERS; transfers++) {
            var expected = search.fullSearch(START_ID, transfers, 0, 180).keySet();
            ReachabilityCache cold = GtfsTestFixtures.reachabilityCache(gtfs);
            assertThat(cold.getOrCompute(key(transfers)).keySet()).as("cold, %d transfers", transfers)
                    .isEqualTo(expected);
            assertThat(incremental.getOrCompute(key(transfers)).keySet()).as("incremental, %d transfers", transfers)
                    .isEqualTo(expected);
        }
    }

    private static ReachabilityCache.SearchKey key(int transfers) {
        return new ReachabilityCache.SearchKey(START_ID, transfers, 0, 180);
    }
}
