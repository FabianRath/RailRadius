package com.railradius.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The stored search results, per search. A stored result also answers every lower transfer count: it holds each
 * station's best path per transfer count. {@link SearchAdmission} fills it.
 */
@Component
@Slf4j
public class ReachabilityCache {

    // One full-data result holds paths to every reachable station, so the count must stay small
    private final Cache<SearchOptions, CompactResult> rawPaths;

    public ReachabilityCache(@Value("${railradius.cache.raw-max-entries:30}") int rawMaxEntries) {
        this.rawPaths = Caffeine.newBuilder().maximumSize(rawMaxEntries).build();
    }

    /** The stored result, else one cut down from a stored result with more transfers. */
    Optional<CompactResult> get(SearchOptions key) {
        CompactResult cached = rawPaths.getIfPresent(key);
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

    void put(SearchOptions key, CompactResult result) {
        rawPaths.put(key, result);
        log.info("  Cached raw result: {} x{} ({} stations)", key.startId(), key.maxTransfers(),
                result.stationCount());
    }

    /** True if {@link #get} would find a result. */
    public boolean contains(SearchOptions key) {
        return rawPaths.getIfPresent(key) != null || storedWithMoreTransfers(key).isPresent();
    }

    private Optional<Map.Entry<SearchOptions, CompactResult>> storedWithMoreTransfers(SearchOptions key) {
        return rawPaths.asMap().entrySet().stream()
                .filter(entry -> entry.getKey().maxTransfers() > key.maxTransfers()
                        && entry.getKey().withMaxTransfers(key.maxTransfers()).equals(key))
                .findAny();
    }

    /** Drops every stored result; they describe a graph that no longer exists. */
    public void clear() {
        rawPaths.invalidateAll();
        log.info("Reachability cache cleared");
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        // Applies pending evictions, so the count is exact
        rawPaths.cleanUp();
        stats.put("rawPathCacheEntries", rawPaths.estimatedSize());
        stats.put("rawPathCacheKeys", rawPaths.asMap().keySet().stream().map(SearchOptions::label).toList());
        return stats;
    }
}
