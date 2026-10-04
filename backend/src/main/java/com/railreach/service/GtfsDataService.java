package com.railreach.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.Map;

/** Fills the {@link TransitGraph} at startup (from the cache file, else from the zips) and on reload. */
@Service
@Slf4j
@RequiredArgsConstructor
public class GtfsDataService {

    private final TransitGraph graph;
    private final GtfsFeedLoader feedLoader;
    private final StationDeduplicator deduplicator;
    private final GtfsCacheStore cacheStore;
    private final ReachabilityCache reachabilityCache;
    private final StationSearchIndex stationSearchIndex;

    @Value("${railreach.gtfs.directory:./gtfs}")
    private String gtfsDirectory;

    @Value("${railreach.gtfs.auto-load-from-files:false}")
    private boolean autoLoadFromFiles;

    private volatile boolean dataLoaded = false;

    @PostConstruct
    public void initialize() {
        log.info("=== RailReach GTFS Data Loader ===");
        var cached = cacheStore.load();
        if (cached.isPresent()) {
            graph.restore(cached.get());
            stationSearchIndex.rebuild();
            log.info("✓ GTFS data loaded from cache: {} stations, {} graph nodes, {} connections",
                    graph.stationCount(), graph.nodeCount(), graph.connectionCount());
            dataLoaded = true;
            return;
        }
        if (!autoLoadFromFiles) {
            log.info("GTFS auto-load disabled. Use POST /api/admin/reload-gtfs to load from files.");
            return;
        }
        loadFromFiles();
    }

    public void loadFromFiles() {
        log.info("=== Loading GTFS from files ===");
        log.info("Scanning directory: {}", gtfsDirectory);
        graph.clear();
        dataLoaded = false;

        long startMs = System.currentTimeMillis();
        feedLoader.loadDirectory(gtfsDirectory, graph);
        long durationMs = System.currentTimeMillis() - startMs;
        deduplicator.deduplicate(graph);

        long connected = graph.connectedStationCount();
        log.info("=== Loading complete in {}ms ===", durationMs);
        log.info("Total stations  : {} ({} connected, {} isolated)",
                graph.stationCount(), connected, graph.stationCount() - connected);
        log.info("Graph nodes     : {}", graph.nodeCount());
        log.info("Total edges     : {}", graph.edgeCount());

        cacheStore.save(graph.snapshot());
        stationSearchIndex.rebuild();
        reachabilityCache.clear();
        dataLoaded = true;
    }

    public void clearCache() {
        cacheStore.delete();
    }

    public Map<String, Object> getCacheStats() {
        return Map.of(
            "cacheFile", cacheStore.path(),
            "cacheExists", cacheStore.exists(),
            "cacheSizeMB", cacheStore.sizeMb(),
            "dataLoaded", dataLoaded,
            "stationCount", graph.stationCount(),
            "graphNodeCount", graph.nodeCount(),
            "connectionCount", graph.connectionCount()
        );
    }
}
