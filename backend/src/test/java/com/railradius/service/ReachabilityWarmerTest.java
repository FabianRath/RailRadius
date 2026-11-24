package com.railradius.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReachabilityWarmerTest {

    @Test
    void warmingCachesEachHigherTransferCountUpToTheConfiguredMaximum(@TempDir Path dir) throws Exception {
        TransitGraph graph = GtfsTestFixtures.load(dir, "alpha", "beta");
        ReachabilityCache cache = GtfsTestFixtures.reachabilityCache();
        SearchAdmission admission = GtfsTestFixtures.admission(graph, cache);
        ReachabilityWarmer warmer = new ReachabilityWarmer(cache, admission);
        ReflectionTestUtils.setField(warmer, "predictiveMaxTransfers", 2);
        SearchOptions queried = new SearchOptions("alpha:A1", 0, 0, 180);
        admission.admit(GtfsTestFixtures.VISITOR, queried, SearchAdmission.SearchListener.NONE);

        warmer.warmHigherTransferCounts(queried);

        assertThat(cache.contains(queried.withMaxTransfers(1))).isTrue();
        assertThat(cache.contains(queried.withMaxTransfers(2))).isTrue();
        assertThat(cache.contains(queried.withMaxTransfers(3))).isFalse();
        assertThat(warmer.getWarmingInProgress()).isEmpty();
    }

    @Test
    void warmingStoresOnlyTheMaximumTransferCount(@TempDir Path dir) throws Exception {
        TransitGraph graph = GtfsTestFixtures.load(dir, "alpha", "beta");
        ReachabilityCache cache = GtfsTestFixtures.reachabilityCache();
        SearchAdmission admission = GtfsTestFixtures.admission(graph, cache);
        ReachabilityWarmer warmer = new ReachabilityWarmer(cache, admission);
        ReflectionTestUtils.setField(warmer, "predictiveMaxTransfers", 3);

        warmer.warmHigherTransferCounts(new SearchOptions("alpha:A1", 0, 0, 180));

        assertThat(cache.getStats()).containsEntry("rawPathCacheEntries", 1L);
        assertThat(cache.contains(new SearchOptions("alpha:A1", 3, 0, 180))).isTrue();
    }

    // B8
    @Test
    void warmingKeepsTheQueriedMinimumTransferTime(@TempDir Path dir) throws Exception {
        TransitGraph graph = GtfsTestFixtures.load(dir, "alpha", "beta");
        ReachabilityCache cache = GtfsTestFixtures.reachabilityCache();
        SearchAdmission admission = GtfsTestFixtures.admission(graph, cache);
        ReachabilityWarmer warmer = new ReachabilityWarmer(cache, admission);
        ReflectionTestUtils.setField(warmer, "predictiveMaxTransfers", 1);
        SearchOptions queried = new SearchOptions("alpha:A1", 0, 5, 180);
        admission.admit(GtfsTestFixtures.VISITOR, queried, SearchAdmission.SearchListener.NONE);

        warmer.warmHigherTransferCounts(queried);

        assertThat(cache.contains(queried.withMaxTransfers(1))).isTrue();
    }

    @Test
    void warmingIsSkippedWhileNoSearchPermitIsFree(@TempDir Path dir) throws Exception {
        SearchGate gate = GtfsTestFixtures.gate(1, 1);
        ReachabilityCache cache = GtfsTestFixtures.reachabilityCache();
        SearchAdmission admission =
                GtfsTestFixtures.admission(GtfsTestFixtures.load(dir, "alpha", "beta"), cache, gate);
        ReachabilityWarmer warmer = new ReachabilityWarmer(cache, admission);
        ReflectionTestUtils.setField(warmer, "predictiveMaxTransfers", 1);
        SearchOptions queried = new SearchOptions("alpha:A1", 0, 0, 180);

        try (HeldPermit ignored = new HeldPermit(gate)) {
            warmer.warmHigherTransferCounts(queried);
        }

        assertThat(cache.contains(queried.withMaxTransfers(1))).isFalse();
        assertThat(warmer.getWarmingInProgress()).isEmpty();
    }
}
