package com.railreach.service;

import com.railreach.service.ReachabilityCache.SearchKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Precomputes N+1, N+2, … transfers after a query, so moving the transfer slider up is instant.
 * A separate bean so that {@code @Async} goes through Spring's proxy and runs off the caller's thread.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ReachabilityWarmer {

    // B8: warming always uses no minimum transfer time, whatever the request asked for
    private static final int WARM_MIN_TRANSFER_MINS = 0;

    private final ReachabilityCache cache;

    @Value("${railreach.cache.predictive-max-transfers:5}")
    private int predictiveMaxTransfers;

    private final Set<SearchKey> warmingInProgress = ConcurrentHashMap.newKeySet();

    @Async
    public void warmHigherTransferCounts(String stationId, int currentMaxTransfers, int maxTransferMins) {
        for (int t = currentMaxTransfers + 1; t <= predictiveMaxTransfers; t++) {
            SearchKey key = new SearchKey(stationId, t, WARM_MIN_TRANSFER_MINS, maxTransferMins);
            if (cache.contains(key)) {
                log.debug("  Predictive cache: {} x{} already cached", stationId, t);
                continue;
            }
            // Without the N-1 result this would be a full BFS, which is not worth doing speculatively
            if (t > 1 && !cache.contains(key.withMaxTransfers(t - 1))) {
                log.debug("  Predictive cache: skipping {} x{} — x{} not cached yet", stationId, t, t - 1);
                break;
            }
            if (!warmingInProgress.add(key)) {
                log.debug("  Predictive cache: {} x{} already warming", stationId, t);
                continue;
            }
            try {
                log.info("  Predictive cache warming: {} x{} transfers (incremental from x{})", stationId, t, t - 1);
                long startMs = System.currentTimeMillis();
                cache.getOrCompute(key);
                log.info("  Predictive cache warmed: {} x{} transfers in {}ms",
                        stationId, t, System.currentTimeMillis() - startMs);
            } catch (Exception e) {
                log.warn("  Predictive cache warming failed for {} x{}", stationId, t, e);
            } finally {
                warmingInProgress.remove(key);
            }
        }
    }

    public List<String> getWarmingInProgress() {
        return warmingInProgress.stream().map(SearchKey::label).toList();
    }
}
