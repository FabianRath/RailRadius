package com.railreach.controller;

import com.railreach.dto.ReachabilityRequest;
import com.railreach.dto.ReachabilityResponse;
import com.railreach.model.Station;
import com.railreach.service.GtfsDataService;
import com.railreach.service.ReachabilityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class RailReachController {
    
    private final ReachabilityService reachabilityService;
    private final GtfsDataService gtfsDataService;
    private final AsyncTaskExecutor taskExecutor;
    
    /**
     * Streaming reachability via Server-Sent Events.
     * The frontend connects once and receives station batches as BFS discovers them.
     *
     * Events emitted:
     *   "stations" — JSON array of ReachableStation objects (batch)
     *   "done"     — {"total": N, "calculationTimeMs": M}
     *   "error"    — error message string
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
            @RequestParam(value = "maxJourneyHours", required = false) Double maxJourneyHours) {

        // 30-minute timeout — enough for any realistic BFS
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);

        ReachabilityRequest req = ReachabilityRequest.builder()
                .startStationId(stationId)
                .maxTransfers(maxTransfers)
                .minTransferTimeMinutes(minTransferMinutes)
                .maxTransferTimeMinutes(maxTransferMinutes)
                .maxJourneyTimeHours(maxJourneyHours)
                .build();

        taskExecutor.execute(() ->
                reachabilityService.calculateReachabilityStreaming(req, emitter));

        return emitter;
    }

    @PostMapping("/reachability")
    public ResponseEntity<ReachabilityResponse> calculateReachability(
            @RequestBody ReachabilityRequest request) {
        
        log.info("Reachability request: startStation={}, maxTransfers={}", 
                request.getStartStationId(), request.getMaxTransfers());
        
        if (request.getStartStationId() == null || request.getStartStationId().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        
        if (gtfsDataService.getStation(request.getStartStationId()) == null) {
            log.warn("Station not found: {}", request.getStartStationId());
            return ResponseEntity.notFound().build();
        }
        
        ReachabilityResponse response = reachabilityService.calculateReachability(request);
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/stations/search")
    public ResponseEntity<List<StationSearchResult>> searchStations(
            @RequestParam("q") String query,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        
        // Use the GtfsDataService.searchStations() method which filters isolated stations
        List<Station> stations = gtfsDataService.searchStations(query, limit);
        
        List<StationSearchResult> results = stations.stream()
                .map(station -> new StationSearchResult(
                        station.getId(),
                        station.getName(),
                        station.getLatitude(),
                        station.getLongitude()
                ))
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(results);
    }
    
    @GetMapping("/stations/{id}")
    public ResponseEntity<Station> getStation(@PathVariable String id) {
        Station station = gtfsDataService.getStation(id);
        if (station == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(station);
    }
    
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(gtfsDataService.getStats());
    }
    
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", gtfsDataService.isDataLoaded() ? "UP" : "DOWN",
                "dataLoaded", gtfsDataService.isDataLoaded()
        ));
    }
    
    public record StationSearchResult(
            String id,
            String name,
            Double latitude,
            Double longitude
    ) {}
}
