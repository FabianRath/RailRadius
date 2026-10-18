package com.railradius.service;

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

    // B20, B21: a shortcut must return exactly what the search returns
    @Test
    void lowerTransferCountsCutFromAStoredResultEqualTheirOwnSearch() {
        CompactReachabilitySearch search = GtfsTestFixtures.search(gtfs);
        ReachabilityCache cache = GtfsTestFixtures.reachabilityCache(gtfs);
        cache.getOrCompute(key(MAX_TRANSFERS));

        for (int transfers = 0; transfers < MAX_TRANSFERS; transfers++) {
            CompactResult cutDown = cache.getOrCompute(key(transfers));
            CompactResult searched = search.search(START_ID, transfers, 0, 180, roundPaths -> {});
            assertThat(cache.contains(key(transfers))).isTrue();
            assertThat(GtfsTestFixtures.journeys(cutDown)).as("%d transfers", transfers)
                    .isEqualTo(GtfsTestFixtures.journeys(searched));
            assertThat(cutDown.stationCount()).isEqualTo(searched.stationCount());
        }
        assertThat(cache.getStats()).containsEntry("rawPathCacheEntries", 1L);
    }

    // B9
    @Test
    void storedResultsBeyondTheMaximumAreEvicted() {
        ReachabilityCache cache = GtfsTestFixtures.reachabilityCache(gtfs, 1);

        cache.getOrCompute(key(0));
        cache.getOrCompute(key(1));

        assertThat(cache.getStats()).containsEntry("rawPathCacheEntries", 1L);
    }

    // B9
    @Test
    void reloadingGtfsDropsStoredResults(@TempDir Path dir) throws Exception {
        TransitGraph graph = new TransitGraph();
        ReachabilityCache cache = GtfsTestFixtures.reachabilityCache(graph);
        GtfsDataService dataService = GtfsTestFixtures.dataService(dir, graph, cache, new StationSearchIndex(graph),
                "alpha", "beta");
        dataService.loadFromFiles();
        cache.getOrCompute(key(1));

        dataService.loadFromFiles();

        assertThat(cache.contains(key(1))).isFalse();
    }

    private static ReachabilityCache.SearchKey key(int transfers) {
        return new ReachabilityCache.SearchKey(START_ID, transfers, 0, 180);
    }
}
