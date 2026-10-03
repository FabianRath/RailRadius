package com.railreach.controller;

import com.railreach.model.Station;
import com.railreach.service.GtfsDataService;
import com.railreach.service.HubRoutingService;
import com.railreach.service.ReachabilityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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

    private final HubRoutingService hubRoutingService;
    private final GtfsDataService gtfsDataService;

    /** Debug flag — toggled by POST/DELETE /api/admin/debug/trigger */
    private final AtomicBoolean debugShowAllStations = new AtomicBoolean(false);
    private final ReachabilityService reachabilityService;

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

    /**
     * Manually trigger hub computation.
     * This can take 5-10 minutes depending on data size.
     * 
     * POST http://localhost:8080/api/admin/compute-hubs
     */
    @PostMapping("/compute-hubs")
    public ResponseEntity<Map<String, Object>> computeHubs() {
        log.info("Manual hub computation triggered via API");
        
        long start = System.currentTimeMillis();
        hubRoutingService.computeHubs();
        long duration = System.currentTimeMillis() - start;
        
        return ResponseEntity.ok(Map.of(
            "message", "Hub computation complete",
            "durationMs", duration,
            "stats", hubRoutingService.getStats()
        ));
    }

    /**
     * Clear hub cache and force recomputation on next request.
     * 
     * DELETE http://localhost:8080/api/admin/hub-cache
     */
    @DeleteMapping("/hub-cache")
    public ResponseEntity<Map<String, String>> clearHubCache() {
        log.info("Hub cache cleared via API");
        hubRoutingService.clearCache();
        
        return ResponseEntity.ok(Map.of(
            "message", "Hub cache cleared. Use POST /api/admin/compute-hubs to recompute."
        ));
    }

    /**
     * Get hub routing statistics.
     * 
     * GET http://localhost:8080/api/admin/hub-stats
     */
    @GetMapping("/hub-stats")
    public ResponseEntity<Map<String, Object>> getHubStats() {
        return ResponseEntity.ok(hubRoutingService.getStats());
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
            "stationCount", gtfsDataService.getAllStations().size()
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
            "stationCount", gtfsDataService.getAllStations().size()
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
        List<Map<String, Object>> stations = gtfsDataService.getAllStations().values().stream()
            // Same criteria as searchStations(): must have coordinates and at least one outgoing edge
            .filter(s -> s.getLatitude() != null && s.getLongitude() != null)
            .filter(s -> {
                Set<String> neighbors = gtfsDataService.getNeighbors(s.getId());
                return neighbors != null && !neighbors.isEmpty();
            })
            .map(s -> {
                int edges = gtfsDataService.getNeighbors(s.getId()).size();
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