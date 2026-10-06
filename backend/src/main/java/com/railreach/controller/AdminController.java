package com.railreach.controller;

import com.railreach.dto.FeedDownloadResult;
import com.railreach.dto.FeedStatus;
import com.railreach.dto.FeedStorage;
import com.railreach.dto.GtfsLoadStatus;
import com.railreach.model.Station;
import com.railreach.service.FeedUpdateCenter;
import com.railreach.service.GtfsDataService;
import com.railreach.service.ReachabilityService;
import com.railreach.service.TransitGraph;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;


@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class AdminController {

    private final GtfsDataService gtfsDataService;
    private final TransitGraph transitGraph;

    /** Debug flag — toggled by POST/DELETE /api/admin/debug/trigger */
    private final AtomicBoolean debugShowAllStations = new AtomicBoolean(false);
    private final ReachabilityService reachabilityService;
    private final FeedUpdateCenter feedUpdateCenter;

    /**
     * Show what is currently in the reachability cache and what is being warmed.
     *
     *   curl http://localhost:8080/api/admin/cache-stats
     */
    @GetMapping("/cache-stats")
    public ResponseEntity<Map<String, Object>> getCacheStats() {
        return ResponseEntity.ok(reachabilityService.getCacheStats());
    }

    /**
     * Reload GTFS data from ZIP files (full reload with deduplication).
     * This can take 5-10 minutes depending on data size.
     * 
     * POST http://localhost:8080/api/admin/reload-gtfs
     */
    @PostMapping("/reload-gtfs")
    public ResponseEntity<Map<String, Object>> reloadGtfs() {
        log.info("Manual GTFS reload triggered via API");
        
        long start = System.currentTimeMillis();
        gtfsDataService.loadFromFiles();
        long duration = System.currentTimeMillis() - start;
        
        return ResponseEntity.ok(Map.of(
            "message", "GTFS data reloaded successfully",
            "durationMs", duration,
            "stats", gtfsDataService.getCacheStats()
        ));
    }

    /**
     * Clear GTFS cache and force reload on next request.
     * 
     * DELETE http://localhost:8080/api/admin/gtfs-cache
     */
    @DeleteMapping("/gtfs-cache")
    public ResponseEntity<Map<String, String>> clearGtfsCache() {
        log.info("GTFS cache cleared via API");
        gtfsDataService.clearCache();
        
        return ResponseEntity.ok(Map.of(
            "message", "GTFS cache cleared. Use POST /api/admin/reload-gtfs to reload."
        ));
    }

    /**
     * Get GTFS cache statistics.
     * 
     * GET http://localhost:8080/api/admin/gtfs-stats
     */
    @GetMapping("/gtfs-stats")
    public ResponseEntity<Map<String, Object>> getGtfsStats() {
        return ResponseEntity.ok(gtfsDataService.getCacheStats());
    }

    /** Progress of the running (or last) GTFS load, polled by the admin panel during a reload. */
    @GetMapping("/gtfs-load-status")
    public GtfsLoadStatus getGtfsLoadStatus() {
        return gtfsDataService.getLoadStatus();
    }

    /** Every GTFS zip with the days its timetable covers. */
    @GetMapping("/feeds")
    public List<FeedStatus> getFeeds() {
        return feedUpdateCenter.statuses();
    }

    /** Disk space of the zips in use and of the replaced ones kept in previous/. */
    @GetMapping("/feeds/storage")
    public FeedStorage getFeedStorage() {
        return feedUpdateCenter.storage();
    }

    /** Downloads a new version of one feed (minutes for the big ones); 404 if it has no download URL. */
    @PostMapping("/feeds/{source}/download")
    public ResponseEntity<FeedDownloadResult> downloadFeed(@PathVariable String source) {
        return feedUpdateCenter.download(source)
                .map(result -> ResponseEntity.status(statusOf(result)).body(result))
                .orElse(ResponseEntity.notFound().build());
    }

    /** Downloads every expired or expiring feed that has a download URL. */
    @PostMapping("/feeds/download-outdated")
    public List<FeedDownloadResult> downloadOutdatedFeeds() {
        return feedUpdateCenter.downloadOutdated();
    }

    private static HttpStatus statusOf(FeedDownloadResult result) {
        return result.getOutcome() == FeedDownloadResult.Outcome.BUSY ? HttpStatus.CONFLICT : HttpStatus.OK;
    }

    // =========================================================================
    // DEBUG ENDPOINTS
    // These allow curl to remotely trigger the in-browser station debug overlay.
    // =========================================================================

    /**
     * Activate debug overlay — shows all stations coloured by connectivity.
     *
     * POST http://localhost:8080/api/admin/debug/trigger
     */
    @PostMapping("/debug/trigger")
    public ResponseEntity<Map<String, Object>> triggerDebug() {
        debugShowAllStations.set(true);
        log.info("🐛 Debug overlay triggered — frontend will render all stations");
        return ResponseEntity.ok(Map.of(
            "message", "Debug overlay activated. The browser will pick this up within 2 seconds.",
            "showAllStations", true,
            "stationCount", transitGraph.getAllStations().size()
        ));
    }

    /**
     * Deactivate debug overlay.
     *
     * DELETE http://localhost:8080/api/admin/debug/trigger
     */
    @DeleteMapping("/debug/trigger")
    public ResponseEntity<Map<String, String>> clearDebug() {
        debugShowAllStations.set(false);
        log.info("🐛 Debug overlay cleared");
        return ResponseEntity.ok(Map.of("message", "Debug overlay cleared."));
    }

    /**
     * Polled by the frontend every 2 seconds to check whether debug mode is active.
     *
     * GET http://localhost:8080/api/admin/debug/status
     */
    @GetMapping("/debug/status")
    public ResponseEntity<Map<String, Object>> debugStatus() {
        return ResponseEntity.ok(Map.of(
            "showAllStations", debugShowAllStations.get(),
            "stationCount", transitGraph.getAllStations().size()
        ));
    }

    /**
     * Returns all stations with their edge counts for the debug overlay.
     * Sorted by connectivity descending so the most-connected stations are listed first.
     *
     * GET http://localhost:8080/api/admin/debug/stations
     */
    @GetMapping("/debug/stations")
    public ResponseEntity<Map<String, Object>> debugStations() {
        List<Map<String, Object>> stations = transitGraph.getAllStations().values().stream()
            // Like StationSearchIndex, only stations with an outgoing edge; the overlay also needs coordinates
            .filter(s -> s.getLatitude() != null && s.getLongitude() != null)
            .filter(s -> {
                Set<String> neighbors = transitGraph.getNeighbors(s.getId());
                return neighbors != null && !neighbors.isEmpty();
            })
            .map(s -> {
                int edges = transitGraph.getNeighbors(s.getId()).size();
                return Map.<String, Object>of(
                    "id",    s.getId(),
                    "name",  s.getName() != null ? s.getName() : s.getId(),
                    "lat",   s.getLatitude(),
                    "lon",   s.getLongitude(),
                    "edges", edges
                );
            })
            .sorted(Comparator.comparingInt((Map<String, Object> m) ->
                    (Integer) m.get("edges")).reversed())
            .collect(Collectors.toList());

        log.info("🐛 Debug stations requested — returning {} connected rail stations", stations.size());
        return ResponseEntity.ok(Map.of("stations", stations));
    }
}