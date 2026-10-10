package com.railreach.service;

import com.railreach.dto.GtfsLoadStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Fills the {@link TransitGraph} at startup (from the binary cache file with the compact engine, else from the JSON
 * cache file, else from the zips) and on reload.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GtfsDataService {

    private final TransitGraph graph;
    private final GtfsFeedLoader feedLoader;
    private final StationDeduplicator deduplicator;
    private final GtfsCacheStore cacheStore;
    private final BinaryCacheStore binaryCacheStore;
    private final ReachabilityCache reachabilityCache;
    private final SearchEngine searchEngine;
    private final StationSearchIndex stationSearchIndex;
    private final GtfsLoadProgress loadProgress;

    @Value("${railreach.gtfs.directory:./gtfs}")
    private String gtfsDirectory;

    @Value("${railreach.gtfs.auto-load-from-files:false}")
    private boolean autoLoadFromFiles;

    private volatile boolean dataLoaded = false;

    @PostConstruct
    public void initialize() {
        log.info("=== RailReach GTFS Data Loader ===");
        Optional<BinaryCacheStore.BinaryCacheData> binaryCached =
                usesBinaryCache() ? binaryCacheStore.load() : Optional.empty();
        if (binaryCached.isPresent()) {
            graph.restore(binaryCached.get());
            finishCacheLoad("binary cache");
            return;
        }
        var cached = cacheStore.load();
        if (cached.isPresent()) {
            graph.restore(cached.get());
            // Built now rather than by the first search, which would otherwise take twice as long
            searchEngine.prepare();
            saveBinaryCache();
            finishCacheLoad("cache");
            return;
        }
        if (!autoLoadFromFiles) {
            log.info("GTFS auto-load disabled. Use POST /api/admin/reload-gtfs to load from files.");
            return;
        }
        if (!feedLoader.hasFeeds(gtfsDirectory)) {
            log.warn("No GTFS zips in {}. Download the feeds in the admin panel (Feeds tab), then reload GTFS.",
                    gtfsDirectory);
            return;
        }
        loadFromFiles();
    }

    private void finishCacheLoad(String source) {
        stationSearchIndex.rebuild();
        log.info("✓ GTFS data loaded from {}: {} stations, {} graph nodes, {} edges", source,
                graph.stationCount(), graph.nodeCount(), graph.edgeCount());
        dataLoaded = true;
    }

    // The binary cache holds only what the compact engine searches on; the legacy one needs the JSON cache (until S21)
    private boolean usesBinaryCache() {
        return searchEngine instanceof CompactReachabilitySearch;
    }

    private void saveBinaryCache() {
        if (usesBinaryCache()) binaryCacheStore.save(graph, Path.of(cacheStore.path()));
    }

    public void loadFromFiles() {
        log.info("=== Loading GTFS from files ===");
        log.info("Scanning directory: {}", gtfsDirectory);
        graph.clear();
        dataLoaded = false;
        loadProgress.start();
        try {
            buildGraphFromFiles();
        } catch (RuntimeException e) {
            loadProgress.enter(GtfsLoadProgress.Phase.FAILED);
            throw e;
        }
        loadProgress.enter(GtfsLoadProgress.Phase.DONE);
        dataLoaded = true;
    }

    private void buildGraphFromFiles() {
        long startMs = System.currentTimeMillis();
        feedLoader.loadDirectory(gtfsDirectory, graph, loadProgress);
        long durationMs = System.currentTimeMillis() - startMs;
        loadProgress.enter(GtfsLoadProgress.Phase.MERGING);
        deduplicator.deduplicate(graph);

        long connected = graph.connectedStationCount();
        log.info("=== Loading complete in {}ms ===", durationMs);
        log.info("Total stations  : {} ({} connected, {} isolated)",
                graph.stationCount(), connected, graph.stationCount() - connected);
        log.info("Graph nodes     : {}", graph.nodeCount());
        log.info("Total edges     : {}", graph.edgeCount());

        loadProgress.enter(GtfsLoadProgress.Phase.SAVING);
        cacheStore.save(graph.snapshot());
        searchEngine.prepare();
        saveBinaryCache();
        stationSearchIndex.rebuild();
        reachabilityCache.clear();
    }

    public void clearCache() {
        cacheStore.delete();
        binaryCacheStore.delete();
    }

    public Map<String, Object> getCacheStats() {
        return Map.of(
            "cacheFile", cacheStore.path(),
            "cacheExists", cacheStore.exists(),
            "cacheSizeMB", cacheStore.sizeMb(),
            "dataLoaded", dataLoaded,
            "stationCount", graph.stationCount(),
            "graphNodeCount", graph.nodeCount(),
            "connectionCount", graph.edgeCount()
        );
    }

    public GtfsLoadStatus getLoadStatus() {
        return loadProgress.status(graph.stationCount(), graph.edgeCount());
    }
}
