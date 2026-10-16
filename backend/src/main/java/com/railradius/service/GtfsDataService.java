package com.railradius.service;

import com.railradius.dto.GtfsLoadStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Fills the {@link TransitGraph} at startup (from the binary cache file, else from the zips) and on reload. */
@Service
@Slf4j
@RequiredArgsConstructor
public class GtfsDataService {

    private final TransitGraph graph;
    private final GtfsFeedLoader feedLoader;
    private final StationDeduplicator deduplicator;
    private final BinaryCacheStore binaryCacheStore;
    private final ReachabilityCache reachabilityCache;
    private final StationSearchIndex stationSearchIndex;
    private final GtfsLoadProgress loadProgress;

    @Value("${railradius.gtfs.directory:./gtfs}")
    private String gtfsDirectory;

    @Value("${railradius.gtfs.auto-load-from-files:false}")
    private boolean autoLoadFromFiles;

    private volatile boolean dataLoaded = false;
    // The network a load from the zips is building, for its progress; null when none runs
    private volatile RailNetwork loadingNetwork;

    @PostConstruct
    public void initialize() {
        log.info("=== RailRadius GTFS Data Loader ===");
        Optional<BinaryCacheStore.BinaryCacheData> cached = binaryCacheStore.load();
        if (cached.isPresent()) {
            graph.restore(cached.get());
            stationSearchIndex.rebuild();
            log.info("✓ GTFS data loaded from the binary cache: {} stations, {} graph nodes, {} edges",
                    graph.stationCount(), graph.nodeCount(), graph.edgeCount());
            dataLoaded = true;
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
        } finally {
            loadingNetwork = null;
        }
        loadProgress.enter(GtfsLoadProgress.Phase.DONE);
        dataLoaded = true;
    }

    private void buildGraphFromFiles() {
        long startMs = System.currentTimeMillis();
        RailNetwork network = new RailNetwork();
        loadingNetwork = network;
        List<Path> zips = feedLoader.loadDirectory(gtfsDirectory, network, loadProgress);
        long durationMs = System.currentTimeMillis() - startMs;
        loadProgress.enter(GtfsLoadProgress.Phase.MERGING);
        deduplicator.deduplicate(network);

        long connected = network.connectedStationCount();
        log.info("=== Loading complete in {}ms ===", durationMs);
        log.info("Total stations  : {} ({} connected, {} isolated)",
                network.stationCount(), connected, network.stationCount() - connected);
        log.info("Graph nodes     : {}", network.nodeCount());
        log.info("Total edges     : {}", network.edgeCount());

        loadProgress.enter(GtfsLoadProgress.Phase.SAVING);
        BinaryCacheStore.BinaryCacheData data = network.servedData();
        binaryCacheStore.save(data, zips);
        graph.restore(data);
        stationSearchIndex.rebuild();
        reachabilityCache.clear();
    }

    public void clearCache() {
        binaryCacheStore.delete();
    }

    public Map<String, Object> getCacheStats() {
        return Map.of(
            "cacheFile", binaryCacheStore.path(),
            "cacheExists", binaryCacheStore.exists(),
            "cacheSizeMB", binaryCacheStore.sizeMb(),
            "dataLoaded", dataLoaded,
            "stationCount", graph.stationCount(),
            "graphNodeCount", graph.nodeCount(),
            "connectionCount", graph.edgeCount()
        );
    }

    public GtfsLoadStatus getLoadStatus() {
        RailNetwork network = loadingNetwork;
        return network != null ? loadProgress.status(network.stationCount(), network.edgeCount())
                : loadProgress.status(graph.stationCount(), graph.edgeCount());
    }
}
