package com.railradius.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static com.railradius.service.GtfsTestFixtures.VISITOR;
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
        ReachabilityCache cache = GtfsTestFixtures.reachabilityCache();
        store(gtfs, cache, key(MAX_TRANSFERS));

        for (int transfers = 0; transfers < MAX_TRANSFERS; transfers++) {
            CompactResult cutDown = cache.get(key(transfers)).orElseThrow();
            CompactResult searched = search.search(START_ID, transfers, 0, 180, CompactReachabilitySearch.NO_DEADLINE,
                    roundPaths -> {});
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
        ReachabilityCache cache = GtfsTestFixtures.reachabilityCache(1);

        store(gtfs, cache, key(0));
        store(gtfs, cache, key(1));

        assertThat(cache.getStats()).containsEntry("rawPathCacheEntries", 1L);
    }

    // B9
    @Test
    void reloadingGtfsDropsStoredResults(@TempDir Path dir) throws Exception {
        TransitGraph graph = new TransitGraph();
        ReachabilityCache cache = GtfsTestFixtures.reachabilityCache();
        GtfsDataService dataService = GtfsTestFixtures.dataService(dir, graph, cache, "alpha", "beta");
        dataService.loadFromFiles();
        store(graph, cache, key(1));

        dataService.loadFromFiles();

        assertThat(cache.contains(key(1))).isFalse();
    }

    private static void store(TransitGraph graph, ReachabilityCache cache, SearchOptions options) {
        GtfsTestFixtures.admission(graph, cache).admit(VISITOR, options, SearchAdmission.SearchListener.NONE);
    }

    private static SearchOptions key(int transfers) {
        return new SearchOptions(START_ID, transfers, 0, 180);
    }
}
