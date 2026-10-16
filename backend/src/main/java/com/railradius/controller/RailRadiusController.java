package com.railradius.controller;

import com.railradius.config.AdminToken;
import com.railradius.dto.Coverage;
import com.railradius.dto.ReachabilityRequest;
import com.railradius.dto.ReachableStation;
import com.railradius.model.Station;
import com.railradius.service.CoverageService;
import com.railradius.service.ReachabilityService;
import com.railradius.service.SearchRateLimiter;
import com.railradius.service.StationSearchIndex;
import com.railradius.service.TransitGraph;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@RestController
@RequestMapping("/api")
@Slf4j
@CrossOrigin(origins = "*")
public class RailRadiusController {
    
    private final ReachabilityService reachabilityService;
    private final TransitGraph transitGraph;
    private final StationSearchIndex stationSearchIndex;
    private final CoverageService coverageService;
    private final AsyncTaskExecutor taskExecutor;
    private final AdminToken adminToken;

    // With @EnableScheduling the task scheduler is an AsyncTaskExecutor too; SSE streams use the task executor
    public RailRadiusController(ReachabilityService reachabilityService, TransitGraph transitGraph,
                               StationSearchIndex stationSearchIndex, CoverageService coverageService,
                               @Qualifier("applicationTaskExecutor") AsyncTaskExecutor taskExecutor,
                               AdminToken adminToken) {
        this.reachabilityService = reachabilityService;
        this.transitGraph = transitGraph;
        this.stationSearchIndex = stationSearchIndex;
        this.coverageService = coverageService;
        this.taskExecutor = taskExecutor;
        this.adminToken = adminToken;
    }
    
    /**
     * Streaming reachability via Server-Sent Events.
     * The frontend connects once and receives station batches as each round of the search finishes.
     *
     * Events emitted:
     *   "stations" — JSON array of ReachableStationSummary objects (batch); the journey comes from
     *                /reachability/journey when a station is clicked
     *   "done"     — {"total": N, "calculationTimeMs": M}
     *   "error"    — error message string, also when the client is over its search budget (SearchRateLimiter)
     *
     * Example:
     *   GET /api/reachability/stream?stationId=de:8011160&maxTransfers=2&minTransferMinutes=5&maxTransferMinutes=180
     */
    @GetMapping(value = "/reachability/stream", produces = "text/event-stream")
    public SseEmitter streamReachability(
            @RequestParam("stationId")           String stationId,
            @RequestParam("maxTransfers")         int maxTransfers,
            @RequestParam(value = "minTransferMinutes", defaultValue = "0")   int minTransferMinutes,
            @RequestParam(value = "maxTransferMinutes", defaultValue = "180") int maxTransferMinutes,
            @RequestParam(value = "maxJourneyHours", required = false) Double maxJourneyHours,
            HttpServletRequest httpRequest) {

        // 30-minute timeout — enough for any realistic BFS
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);

        ReachabilityRequest req = ReachabilityRequest.builder()
                .startStationId(stationId)
                .maxTransfers(maxTransfers)
                .minTransferTimeMinutes(minTransferMinutes)
                .maxTransferTimeMinutes(maxTransferMinutes)
                .maxJourneyTimeHours(maxJourneyHours)
                .build();
        // Read here: the servlet request is recycled before the worker runs
        SearchRateLimiter.Client client = client(httpRequest);

        taskExecutor.execute(() ->
                reachabilityService.calculateReachabilityStreaming(client, req, emitter));

        return emitter;
    }

    /**
     * One reachable station with its journey (path, legs, running days), for the same parameters as the stream.
     * 404 if targetId is not reachable within them, 429 if it would have to search and the client is over its budget.
     */
    @GetMapping("/reachability/journey")
    public ResponseEntity<ReachableStation> journey(
            @RequestParam("stationId")           String stationId,
            @RequestParam("maxTransfers")         int maxTransfers,
            @RequestParam(value = "minTransferMinutes", defaultValue = "0")   int minTransferMinutes,
            @RequestParam(value = "maxTransferMinutes", defaultValue = "180") int maxTransferMinutes,
            @RequestParam(value = "maxJourneyHours", required = false) Double maxJourneyHours,
            @RequestParam("targetId")             String targetId,
            HttpServletRequest httpRequest) {
        ReachabilityRequest req = ReachabilityRequest.builder()
                .startStationId(stationId)
                .maxTransfers(maxTransfers)
                .minTransferTimeMinutes(minTransferMinutes)
                .maxTransferTimeMinutes(maxTransferMinutes)
                .maxJourneyTimeHours(maxJourneyHours)
                .build();
        return ResponseEntity.of(reachabilityService.journey(client(httpRequest), req, targetId));
    }

    private SearchRateLimiter.Client client(HttpServletRequest request) {
        return new SearchRateLimiter.Client(request.getRemoteAddr(), adminToken.sentWith(request));
    }

    @GetMapping("/stations/search")
    public ResponseEntity<List<StationSearchResult>> searchStations(
            @RequestParam("q") String query,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        
        List<Station> stations = stationSearchIndex.search(query, limit);
        
        List<StationSearchResult> results = stations.stream()
                .map(station -> new StationSearchResult(
                        station.getId(),
                        station.getName(),
                        station.getLatitude(),
                        station.getLongitude(),
                        station.getCountry()
                ))
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(results);
    }
    
    @GetMapping("/stations/{id}")
    public ResponseEntity<Station> getStation(@PathVariable String id) {
        Station station = transitGraph.getStation(id);
        if (station == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(station);
    }
    
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(transitGraph.getStats());
    }
    
    @GetMapping("/coverage")
    public Coverage coverage() {
        return coverageService.coverage();
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", transitGraph.isDataLoaded() ? "UP" : "DOWN",
                "dataLoaded", transitGraph.isDataLoaded()
        ));
    }
    
    public record StationSearchResult(
            String id,
            String name,
            Double latitude,
            Double longitude,
            String country
    ) {}
}
