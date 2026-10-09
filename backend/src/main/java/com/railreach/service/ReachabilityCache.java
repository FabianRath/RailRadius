package com.railreach.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Raw search results per search, computed on demand. Kept apart from the Caffeine response cache because a stored
 * result also answers every lower transfer count: it holds each station's best path per transfer count.
 */
@Component
@Slf4j
public class ReachabilityCache {

    static final String RESPONSE_CACHE_NAME = "reachability";

    private final SearchEngine search;
    private final SearchGate gate;
    private final CacheManager cacheManager;
    // One full-data result holds paths to every reachable station, so the count must stay small
    private final Cache<SearchKey, SearchResult> rawPaths;

    public ReachabilityCache(SearchEngine search, SearchGate gate, CacheManager cacheManager,
                             @Value("${railreach.cache.raw-max-entries:30}") int rawMaxEntries) {
        this.search = search;
        this.gate = gate;
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

    /** What a caller hears of a search it waits for. */
    public interface SearchListener {

        SearchListener NONE = new SearchListener() {};

        /** Runs about every second while the search waits for a {@link SearchGate} permit; may throw to give up. */
        default void queued() {}

        /** Each round's new path per station, as the search goes. */
        default void roundPaths(Map<String, SearchResult.PathSummary> paths) {}
    }

    /**
     * The search's result (see {@link SearchEngine#search}). Taken from the stored
     * result, else cut down from a stored result with more transfers, else searched and stored.
     *
     * @throws SearchGate.BusyException if it would have to search and too many searches wait already
     */
    public SearchResult getOrCompute(SearchKey key) {
        return getOrCompute(key, SearchListener.NONE);
    }

    /** Like {@link #getOrCompute(SearchKey)}, telling the listener about the queue and the rounds of a search. */
    public SearchResult getOrCompute(SearchKey key, SearchListener listener) {
        Optional<SearchResult> stored = stored(key);
        if (stored.isPresent()) return stored.get();
        return gate.run(() -> storedOrSearched(key, listener), listener::queued);
    }

    /** Searches and stores the result only if a search permit is free right now; true if the result is stored. */
    public boolean computeIfPermitFree(SearchKey key) {
        return gate.runIfFree(() -> storedOrSearched(key, SearchListener.NONE)).isPresent();
    }

    // Checked again with the permit: an equal search may have finished while this one waited
    private SearchResult storedOrSearched(SearchKey key, SearchListener listener) {
        return stored(key).orElseGet(() -> {
            log.info("  Search: {} x{}", key.startId(), key.maxTransfers());
            SearchResult result = search.search(key.startId(), key.maxTransfers(),
                    key.minTransferMins(), key.maxTransferMins(), listener::roundPaths);
            rawPaths.put(key, result);
            log.info("  Cached raw result: {} x{} ({} stations)", key.startId(), key.maxTransfers(),
                    result.stationCount());
            return result;
        });
    }

    private Optional<SearchResult> stored(SearchKey key) {
        SearchResult cached = rawPaths.getIfPresent(key);
        if (cached != null) {
            log.info("  Raw cache HIT: {} x{} ({} stations)", key.startId(), key.maxTransfers(),
                    cached.stationCount());
            return Optional.of(cached);
        }
        return storedWithMoreTransfers(key).map(higher -> {
            log.info("  Raw cache HIT: {} x{} cut down from x{}",
                    key.startId(), key.maxTransfers(), higher.getKey().maxTransfers());
            return higher.getValue().withMaxTransfers(key.maxTransfers());
        });
    }

    /** True if {@link #getOrCompute} would not have to search. */
    public boolean contains(SearchKey key) {
        return rawPaths.getIfPresent(key) != null || storedWithMoreTransfers(key).isPresent();
    }

    private Optional<Map.Entry<SearchKey, SearchResult>> storedWithMoreTransfers(SearchKey key) {
        return rawPaths.asMap().entrySet().stream()
                .filter(entry -> entry.getKey().maxTransfers() > key.maxTransfers()
                        && entry.getKey().withMaxTransfers(key.maxTransfers()).equals(key))
                .findAny();
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
