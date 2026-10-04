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
 * Precomputes the configured maximum transfer count after a query. The cache answers every lower count from it,
 * so moving the transfer slider up is instant.
 * A separate bean so that {@code @Async} goes through Spring's proxy and runs off the caller's thread.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ReachabilityWarmer {

    private final ReachabilityCache cache;

    @Value("${railreach.cache.predictive-max-transfers:5}")
    private int predictiveMaxTransfers;

    private final Set<SearchKey> warmingInProgress = ConcurrentHashMap.newKeySet();

    @Async
    public void warmHigherTransferCounts(SearchKey queried) {
        SearchKey key = queried.withMaxTransfers(predictiveMaxTransfers);
        if (queried.maxTransfers() >= predictiveMaxTransfers || cache.contains(key)) return;
        if (!warmingInProgress.add(key)) {
            log.debug("  Predictive cache: {} x{} already warming", key.startId(), key.maxTransfers());
            return;
        }
        try {
            log.info("  Predictive cache warming: {} x{} transfers", key.startId(), key.maxTransfers());
            long startMs = System.currentTimeMillis();
            cache.getOrCompute(key);
            log.info("  Predictive cache warmed: {} x{} transfers in {}ms",
                    key.startId(), key.maxTransfers(), System.currentTimeMillis() - startMs);
        } catch (Exception e) {
            log.warn("  Predictive cache warming failed for {} x{}", key.startId(), key.maxTransfers(), e);
        } finally {
            warmingInProgress.remove(key);
        }
    }

    public List<String> getWarmingInProgress() {
        return warmingInProgress.stream().map(SearchKey::label).toList();
    }
}
