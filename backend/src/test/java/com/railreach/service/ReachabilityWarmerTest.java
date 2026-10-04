package com.railreach.service;

import com.railreach.service.ReachabilityCache.SearchKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReachabilityWarmerTest {

    @Test
    void warmingCachesEachHigherTransferCountUpToTheConfiguredMaximum(@TempDir Path dir) throws Exception {
        ReachabilityCache cache = GtfsTestFixtures.reachabilityCache(GtfsTestFixtures.load(dir, "alpha", "beta"));
        ReachabilityWarmer warmer = new ReachabilityWarmer(cache);
        ReflectionTestUtils.setField(warmer, "predictiveMaxTransfers", 2);
        SearchKey queried = new SearchKey("alpha:A1", 0, 0, 180);
        cache.getOrCompute(queried);

        warmer.warmHigherTransferCounts(queried);

        assertThat(cache.contains(queried.withMaxTransfers(1))).isTrue();
        assertThat(cache.contains(queried.withMaxTransfers(2))).isTrue();
        assertThat(cache.contains(queried.withMaxTransfers(3))).isFalse();
        assertThat(warmer.getWarmingInProgress()).isEmpty();
    }

    @Test
    void warmingStopsWhenTheQueriedResultIsNotCached(@TempDir Path dir) throws Exception {
        ReachabilityCache cache = GtfsTestFixtures.reachabilityCache(GtfsTestFixtures.load(dir, "alpha", "beta"));
        ReachabilityWarmer warmer = new ReachabilityWarmer(cache);
        ReflectionTestUtils.setField(warmer, "predictiveMaxTransfers", 2);

        warmer.warmHigherTransferCounts(new SearchKey("alpha:A1", 1, 0, 180));

        assertThat(cache.contains(new SearchKey("alpha:A1", 2, 0, 180))).isFalse();
    }

    // B8
    @Test
    void warmingKeepsTheQueriedMinimumTransferTime(@TempDir Path dir) throws Exception {
        ReachabilityCache cache = GtfsTestFixtures.reachabilityCache(GtfsTestFixtures.load(dir, "alpha", "beta"));
        ReachabilityWarmer warmer = new ReachabilityWarmer(cache);
        ReflectionTestUtils.setField(warmer, "predictiveMaxTransfers", 1);
        SearchKey queried = new SearchKey("alpha:A1", 0, 5, 180);
        cache.getOrCompute(queried);

        warmer.warmHigherTransferCounts(queried);

        assertThat(cache.contains(queried.withMaxTransfers(1))).isTrue();
    }
}
