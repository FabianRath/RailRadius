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
 * Raw search results per search, computed on demand. A stored result also answers every lower transfer count: it
 * holds each station's best path per transfer count.
 */
@Component
@Slf4j
public class ReachabilityCache {

    private final CompactReachabilitySearch search;
    private final SearchGate gate;
    private final InFlightSearches inFlight = new InFlightSearches();
    // One full-data result holds paths to every reachable station, so the count must stay small
    private final Cache<SearchKey, CompactResult> rawPaths;

    public ReachabilityCache(CompactReachabilitySearch search, SearchGate gate,
                             @Value("${railradius.cache.raw-max-entries:30}") int rawMaxEntries) {
        this.search = search;
        this.gate = gate;
        this.rawPaths = Caffeine.newBuilder().maximumSize(rawMaxEntries).build();
    }

    public record SearchKey(String startId, int maxTransfers, int minTransferMins, int maxTransferMins) {

        SearchKey withMaxTransfers(int transfers) {
            return new SearchKey(startId, transfers, minTransferMins, maxTransferMins);
        }

        String label() {
            return startId + "-" + maxTransfers + "-" + minTransferMins + "-" + maxTransferMins;
        }
    }

    /** What a caller hears of a search it waits for. */
    public interface SearchListener {

        SearchListener NONE = new SearchListener() {};

        /**
         * Runs about every second while the search waits for a {@link SearchGate} permit or for an equal search;
         * may throw to give up.
         */
        default void queued() {}

        /** Each round's new path per station, as the search goes. */
        default void roundPaths(Map<String, CompactResult.PathSummary> paths) {}
    }

    /**
     * The search's result (see {@link CompactReachabilitySearch#search}). Taken from the stored
     * result, else cut down from a stored result with more transfers, else from the equal search running now (see
     * {@link InFlightSearches#joinOrRun}), else searched and stored.
     *
     * @throws SearchGate.BusyException if it would have to search and too many searches wait already
     */
    public CompactResult getOrCompute(SearchKey key) {
        return getOrCompute(key, SearchListener.NONE);
    }

    /** Like {@link #getOrCompute(SearchKey)}, telling the listener about the queue and the rounds of a search. */
    public CompactResult getOrCompute(SearchKey key, SearchListener listener) {
        Optional<CompactResult> stored = stored(key);
        if (stored.isPresent()) return stored.get();
        return inFlight.joinOrRun(key, () -> gate.run(() -> storedOrSearched(key, listener), listener::queued),
                listener::queued);
    }

    /**
     * Searches and stores the result only if a search permit is free right now and no equal search runs; true if the
     * result is stored.
     */
    public boolean computeIfPermitFree(SearchKey key) {
        return inFlight.runUnlessRunning(key, () -> gate.runIfFree(() -> storedOrSearched(key, SearchListener.NONE)))
                .isPresent();
    }

    // Checked again with the permit: an equal search may have finished while this one waited
    private CompactResult storedOrSearched(SearchKey key, SearchListener listener) {
        return stored(key).orElseGet(() -> {
            log.info("  Search: {} x{}", key.startId(), key.maxTransfers());
            CompactResult result = search.search(key.startId(), key.maxTransfers(),
                    key.minTransferMins(), key.maxTransferMins(), listener::roundPaths);
            rawPaths.put(key, result);
            log.info("  Cached raw result: {} x{} ({} stations)", key.startId(), key.maxTransfers(),
                    result.stationCount());
            return result;
        });
    }

    private Optional<CompactResult> stored(SearchKey key) {
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

    /** True if {@link #getOrCompute} would not have to search. */
    public boolean contains(SearchKey key) {
        return rawPaths.getIfPresent(key) != null || storedWithMoreTransfers(key).isPresent();
    }

    private Optional<Map.Entry<SearchKey, CompactResult>> storedWithMoreTransfers(SearchKey key) {
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
        stats.put("rawPathCacheKeys", rawPaths.asMap().keySet().stream().map(SearchKey::label).toList());
        return stats;
    }
}
