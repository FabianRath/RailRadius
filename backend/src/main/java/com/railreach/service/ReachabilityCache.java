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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Raw search results per search, computed on demand. Kept apart from the Caffeine response cache because a stored
 * result also answers every lower transfer count: it holds each station's best path per transfer count.
 */
@Component
@Slf4j
public class ReachabilityCache {

    static final String RESPONSE_CACHE_NAME = "reachability";

    private final ReachabilitySearch search;
    private final CacheManager cacheManager;
    // One full-data result holds paths to every reachable station, so the count must stay small
    private final Cache<SearchKey, Map<String, List<JourneyPath>>> rawPaths;

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
     * Per station, its paths by ascending transfers (see {@link ReachabilitySearch#search}). Taken from the stored
     * result, else cut down from a stored result with more transfers, else searched and stored.
     */
    public Map<String, List<JourneyPath>> getOrCompute(SearchKey key) {
        return getOrCompute(key, roundPaths -> {});
    }

    /** Like {@link #getOrCompute(SearchKey)}; a search hands roundPaths each round's new paths as it goes. */
    public Map<String, List<JourneyPath>> getOrCompute(SearchKey key,
                                                       Consumer<Map<String, List<JourneyPath>>> roundPaths) {
        Map<String, List<JourneyPath>> cached = rawPaths.getIfPresent(key);
        if (cached != null) {
            log.info("  Raw cache HIT: {} x{} ({} stations)", key.startId(), key.maxTransfers(), cached.size());
            return cached;
        }
        Optional<Map.Entry<SearchKey, Map<String, List<JourneyPath>>>> higher = storedWithMoreTransfers(key);
        if (higher.isPresent()) {
            log.info("  Raw cache HIT: {} x{} cut down from x{}",
                    key.startId(), key.maxTransfers(), higher.get().getKey().maxTransfers());
            return limitTransfers(higher.get().getValue(), key.maxTransfers());
        }

        log.info("  Search: {} x{}", key.startId(), key.maxTransfers());
        Map<String, List<JourneyPath>> result = search.search(key.startId(), key.maxTransfers(),
                key.minTransferMins(), key.maxTransferMins(), roundPaths);
        rawPaths.put(key, result);
        log.info("  Cached raw result: {} x{} ({} stations)", key.startId(), key.maxTransfers(), result.size());
        return result;
    }

    /** True if {@link #getOrCompute} would not have to search. */
    public boolean contains(SearchKey key) {
        return rawPaths.getIfPresent(key) != null || storedWithMoreTransfers(key).isPresent();
    }

    private Optional<Map.Entry<SearchKey, Map<String, List<JourneyPath>>>> storedWithMoreTransfers(SearchKey key) {
        return rawPaths.asMap().entrySet().stream()
                .filter(entry -> entry.getKey().maxTransfers() > key.maxTransfers()
                        && entry.getKey().withMaxTransfers(key.maxTransfers()).equals(key))
                .findAny();
    }

    // Rounds of the search never depend on later rounds, so the paths with at most N transfers of a search with
    // more transfers are exactly the result of a search with N
    private static Map<String, List<JourneyPath>> limitTransfers(Map<String, List<JourneyPath>> result,
                                                                 int maxTransfers) {
        Map<String, List<JourneyPath>> limited = new HashMap<>(result.size() * 2);
        result.forEach((stopId, paths) -> {
            List<JourneyPath> within = paths.stream().filter(path -> path.getTransfers() <= maxTransfers).toList();
            if (!within.isEmpty()) limited.put(stopId, within);
        });
        return limited;
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
