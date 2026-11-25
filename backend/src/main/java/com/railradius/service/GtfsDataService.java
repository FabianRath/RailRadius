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
import java.util.concurrent.atomic.AtomicBoolean;

/** Serves a graph at startup (from the binary cache file, else from the zips) and on reload. */
@Service
@Slf4j
@RequiredArgsConstructor
public class GtfsDataService {

    private final TransitGraph graph;
    private final GtfsFeedLoader feedLoader;
    private final StationDeduplicator deduplicator;
    private final BinaryCacheStore binaryCacheStore;
    private final ReachabilityCache reachabilityCache;
    private final GtfsLoadProgress loadProgress;
    private final BrowserDataStore browserDataStore;

    @Value("${railradius.gtfs.directory:./gtfs}")
    private String gtfsDirectory;

    @Value("${railradius.gtfs.auto-load-from-files:false}")
    private boolean autoLoadFromFiles;

    // The network a load from the zips is building, for its progress; null when none runs
    private volatile RailNetwork loadingNetwork;
    // Each load builds a whole graph, so two at once would double the peak memory and mix their progress (#20)
    private final AtomicBoolean loading = new AtomicBoolean();

    @PostConstruct
    public void initialize() {
        log.info("=== RailRadius GTFS Data Loader ===");
        Optional<BinaryCacheStore.BinaryCacheData> cached = binaryCacheStore.load();
        if (cached.isPresent()) {
            serve(cached.get());
            log.info("GTFS data loaded from the binary cache: {} stations, {} graph nodes, {} edges",
                    graph.stationCount(), graph.nodeCount(), graph.edgeCount());
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

    /** Loads the zips and serves what they hold; throws {@link GtfsLoadRunning} while another load runs. */
    public void loadFromFiles() {
        if (!loading.compareAndSet(false, true)) throw new GtfsLoadRunning();
        try {
            loadFromFilesAlone();
        } finally {
            loading.set(false);
        }
    }

    private void loadFromFilesAlone() {
        log.info("=== Loading GTFS from files ===");
        log.info("Scanning directory: {}", gtfsDirectory);
        // The graph from before is served until the new one is built, and stays if the load fails (#17)
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
        BinaryCacheStore.BinaryCacheData data = network.servedData(graph.todayEpochDay());
        binaryCacheStore.save(data, zips);
        serve(data);
    }

    /**
     * Makes the data the served graph, drops the stored results, which were searched on the one from before, and
     * starts building its browser data.
     */
    private void serve(BinaryCacheStore.BinaryCacheData data) {
        graph.serve(data);
        reachabilityCache.clear();
        browserDataStore.buildServed();
    }

    public void clearCache() {
        binaryCacheStore.delete();
    }

    public Map<String, Object> getCacheStats() {
        return Map.of(
            "cacheFile", binaryCacheStore.path(),
            "cacheExists", binaryCacheStore.exists(),
            "cacheSizeMB", binaryCacheStore.sizeMb(),
            "dataLoaded", graph.isDataLoaded(),
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
