package com.railreach.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.railreach.model.JourneyPath;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Raw BFS results per search, computed on demand. Kept apart from the Caffeine response cache
 * so an N-transfer search can start from the stored N-1 result (incremental BFS).
 */
@Component
@Slf4j
public class ReachabilityCache {

    static final String RESPONSE_CACHE_NAME = "reachability";

    private final ReachabilitySearch search;
    private final CacheManager cacheManager;
    // One full-data result holds a path to every reachable station, so the count must stay small
    private final Cache<SearchKey, Map<String, JourneyPath>> rawPaths;

    public ReachabilityCache(ReachabilitySearch search, CacheManager cacheManager,
                             @Value("${railreach.cache.raw-max-entries:30}") int rawMaxEntries) {
        this.search = search;
        this.cacheManager = cacheManager;
        this.rawPaths = Caffeine.newBuilder().maximumSize(rawMaxEntries).build();
    }

    public record SearchKey(String startId, int maxTransfers, int minTransferMins, int maxTransferMins) {

        SearchKey withMaxTransfers(int transfers) {
            return new SearchKey(startId, transfers, minTransferMins, maxTransferMins);
        }

        /** Same format as the {@code @Cacheable} key of {@code ReachabilityService.calculateReachability}. */
        String label() {
            return startId + "-" + maxTransfers + "-" + minTransferMins + "-" + maxTransferMins;
        }
    }

    /**
     * The stored result, else an incremental BFS from the stored N-1 result, else a full BFS.
     * The computed result is stored.
     */
    public Map<String, JourneyPath> getOrCompute(SearchKey key) {
        Map<String, JourneyPath> cached = rawPaths.getIfPresent(key);
        if (cached != null) {
            log.info("  Raw cache HIT: {} x{} ({} stations) — skipping BFS",
                    key.startId(), key.maxTransfers(), cached.size());
            return cached;
        }

        Map<String, JourneyPath> previous = key.maxTransfers() > 0
                ? rawPaths.getIfPresent(key.withMaxTransfers(key.maxTransfers() - 1)) : null;
        Map<String, JourneyPath> result;
        if (previous != null && !previous.isEmpty()) {
            log.info("  Incremental BFS: seeding from {} x{} ({} stations), adding 1 more transfer",
                    key.startId(), key.maxTransfers() - 1, previous.size());
            result = search.incrementalSearch(key.startId(), previous, key.maxTransfers(),
                    key.minTransferMins(), key.maxTransferMins());
        } else {
            log.info("  Full BFS: {} x{}", key.startId(), key.maxTransfers());
            result = search.fullSearch(key.startId(), key.maxTransfers(),
                    key.minTransferMins(), key.maxTransferMins());
        }

        rawPaths.put(key, result);
        log.info("  Cached raw result: {} x{} ({} stations)", key.startId(), key.maxTransfers(), result.size());
        return result;
    }

    public boolean contains(SearchKey key) {
        return rawPaths.getIfPresent(key) != null;
    }

    /** Drops every stored result and response; they describe a graph that no longer exists. */
    public void clear() {
        rawPaths.invalidateAll();
        var responseCache = cacheManager.getCache(RESPONSE_CACHE_NAME);
        if (responseCache != null) responseCache.clear();
        log.info("Reachability caches cleared");
    }

    /** Drops the Caffeine response for this search, which may predate the stored raw result. */
    public void evictResponse(SearchKey key) {
        var responseCache = cacheManager.getCache(RESPONSE_CACHE_NAME);
        if (responseCache != null) responseCache.evict(key.label());
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        if (cacheManager.getCache(RESPONSE_CACHE_NAME) instanceof CaffeineCache caffeineCache) {
            CacheStats caffeineStats = caffeineCache.getNativeCache().stats();
            stats.put("hitCount", caffeineStats.hitCount());
            stats.put("missCount", caffeineStats.missCount());
            stats.put("hitRate", String.format("%.1f%%", caffeineStats.hitRate() * 100));
            stats.put("evictionCount", caffeineStats.evictionCount());
            stats.put("estimatedSize", caffeineCache.getNativeCache().estimatedSize());
        }
        // Applies pending evictions, so the count is exact
        rawPaths.cleanUp();
        stats.put("rawPathCacheEntries", rawPaths.estimatedSize());
        stats.put("rawPathCacheKeys", rawPaths.asMap().keySet().stream().map(SearchKey::label).toList());
        return stats;
    }
}
