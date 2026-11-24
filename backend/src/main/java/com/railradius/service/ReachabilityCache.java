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
    private final TransitGraph graph;

    public ReachabilityCache(TransitGraph graph, @Value("${railradius.cache.raw-max-entries:30}") int rawMaxEntries) {
        this.graph = graph;
        this.rawPaths = Caffeine.newBuilder().maximumSize(rawMaxEntries).build();
    }

    /** The stored result, else one cut down from a stored result with more transfers. */
    Optional<CompactResult> get(SearchOptions key) {
        Optional<CompactResult> cached = storedExactly(key);
        if (cached.isPresent()) {
            log.info("  Raw cache HIT: {} x{} ({} stations)", key.startId(), key.maxTransfers(),
                    cached.get().stationCount());
            return cached;
        }
        return storedWithMoreTransfers(key).map(higher -> {
            log.info("  Raw cache HIT: {} x{} cut down from x{}",
                    key.startId(), key.maxTransfers(), higher.getKey().maxTransfers());
            return higher.getValue().withMaxTransfers(key.maxTransfers());
        });
    }

    void put(SearchOptions key, CompactResult result) {
        if (!isServed(result)) return;
        rawPaths.put(key, result);
        log.info("  Cached raw result: {} x{} ({} stations)", key.startId(), key.maxTransfers(),
                result.stationCount());
    }

    /** True if {@link #get} would find a result. */
    public boolean contains(SearchOptions key) {
        return storedExactly(key).isPresent() || storedWithMoreTransfers(key).isPresent();
    }

    private Optional<CompactResult> storedExactly(SearchOptions key) {
        return Optional.ofNullable(rawPaths.getIfPresent(key)).filter(this::isServed);
    }

    private Optional<Map.Entry<SearchOptions, CompactResult>> storedWithMoreTransfers(SearchOptions key) {
        return rawPaths.asMap().entrySet().stream()
                .filter(entry -> entry.getKey().maxTransfers() > key.maxTransfers()
                        && entry.getKey().withMaxTransfers(key.maxTransfers()).equals(key)
                        && isServed(entry.getValue()))
                .findAny();
    }

    /**
     * A search that started before a load can finish after it, on the graph from before. Its result must not answer
     * for the new graph, even if it is stored after {@link #clear} (#19).
     */
    private boolean isServed(CompactResult result) {
        return result.served() == graph.served();
    }

    /** Drops every stored result, to free the memory of results searched on a graph that is no longer served. */
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
