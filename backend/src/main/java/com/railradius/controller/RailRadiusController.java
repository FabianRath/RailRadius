package com.railradius.controller;

import com.railradius.config.AdminAccess;
import com.railradius.dto.BrowserDataInfo;
import com.railradius.dto.Coverage;
import com.railradius.dto.ReachableStation;
import com.railradius.dto.RideListJourneyRequest;
import com.railradius.model.Station;
import com.railradius.service.BrowserDataStore;
import com.railradius.service.CoverageService;
import com.railradius.service.ReachabilityService;
import com.railradius.service.RideListJourneys;
import com.railradius.service.SearchAdmission;
import com.railradius.service.SearchRequestValidator.SearchRequest;
import com.railradius.service.StationSearchIndex;
import com.railradius.service.TransitGraph;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/api")
@Slf4j
public class RailRadiusController {

    // A stream may take this many times the longest a search can wait and run (SearchAdmission.longestWait), for
    // sending the result to a slow client. A timed out stream fails its next send, so its search stops and frees its
    // permit like a gone client's (S36)
    private static final double STREAM_TIMEOUT_FACTOR = 2.5;
    private static final String BROWSER_DATA_PATH = "/api/browser-data/";
    private static final String BROWSER_DATA_SUFFIX = ".bin";
    // A version's file never changes, so browsers may keep it as long as caches allow
    private static final Duration BROWSER_DATA_MAX_AGE = Duration.ofDays(365);
    private static final String GZIP = "gzip";

    private final ReachabilityService reachabilityService;
    private final TransitGraph transitGraph;
    private final StationSearchIndex stationSearchIndex;
    private final CoverageService coverageService;
    private final AsyncTaskExecutor taskExecutor;
    private final AdminAccess adminAccess;
    private final BrowserDataStore browserDataStore;
    private final RideListJourneys rideListJourneys;
    private final long streamTimeoutMs;

    // With @EnableScheduling the task scheduler is an AsyncTaskExecutor too; SSE streams use the task executor
    public RailRadiusController(ReachabilityService reachabilityService, TransitGraph transitGraph,
                               StationSearchIndex stationSearchIndex, CoverageService coverageService,
                               @Qualifier("applicationTaskExecutor") AsyncTaskExecutor taskExecutor,
                               AdminAccess adminAccess, SearchAdmission admission,
                               BrowserDataStore browserDataStore, RideListJourneys rideListJourneys) {
        this.reachabilityService = reachabilityService;
        this.transitGraph = transitGraph;
        this.stationSearchIndex = stationSearchIndex;
        this.coverageService = coverageService;
        this.taskExecutor = taskExecutor;
        this.adminAccess = adminAccess;
        this.browserDataStore = browserDataStore;
        this.rideListJourneys = rideListJourneys;
        this.streamTimeoutMs = (long) (admission.longestWait().toMillis() * STREAM_TIMEOUT_FACTOR);
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
            @RequestParam(value = "minTransferMinutes", required = false) Integer minTransferMinutes,
            @RequestParam(value = "maxTransferMinutes", required = false) Integer maxTransferMinutes,
            @RequestParam(value = "maxJourneyHours", required = false) Double maxJourneyHours,
            HttpServletRequest httpRequest) {

        SseEmitter emitter = new SseEmitter(streamTimeoutMs);

        SearchRequest req = new SearchRequest(stationId, maxTransfers, minTransferMinutes, maxTransferMinutes,
                maxJourneyHours);
        // Read here: the servlet request is recycled before the worker runs
        SearchAdmission.Client client = client(httpRequest);

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
            @RequestParam(value = "minTransferMinutes", required = false) Integer minTransferMinutes,
            @RequestParam(value = "maxTransferMinutes", required = false) Integer maxTransferMinutes,
            @RequestParam(value = "maxJourneyHours", required = false) Double maxJourneyHours,
            @RequestParam("targetId")             String targetId,
            HttpServletRequest httpRequest) {
        SearchRequest req = new SearchRequest(stationId, maxTransfers, minTransferMinutes, maxTransferMinutes,
                maxJourneyHours);
        return ResponseEntity.of(reachabilityService.journey(client(httpRequest), req, targetId));
    }

    /**
     * The journey of the rides a browser's search found (#11), built without a search. 400 if the browser data
     * version is not the served one or the rides are no journey within the wait at changes, 429 over the budget.
     */
    @PostMapping("/reachability/ride-journey")
    public ResponseEntity<ReachableStation> rideListJourney(@RequestBody RideListJourneyRequest request,
                                                            HttpServletRequest httpRequest) {
        return ResponseEntity.of(rideListJourneys.journey(client(httpRequest), request));
    }

    private SearchAdmission.Client client(HttpServletRequest request) {
        return new SearchAdmission.Client(request.getRemoteAddr(), adminAccess.exemptsSearch(request));
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
                        station.getCountry(),
                        transitGraph.stoppingTripCount(station.getId())
                ))
                .toList();
        
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

    /** The browser data of the served graph (#9); 404 while it is built, so the browser searches on the server. */
    @GetMapping("/browser-data")
    public ResponseEntity<BrowserDataInfo> browserData() {
        return browserDataStore.current()
                .map(published -> ResponseEntity.ok(new BrowserDataInfo(published.version(),
                        BROWSER_DATA_PATH + published.version() + BROWSER_DATA_SUFFIX, published.sizeBytes())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The gzipped file of that version; 404 for any version but the served graph's. */
    @GetMapping("/browser-data/{version}" + BROWSER_DATA_SUFFIX)
    public ResponseEntity<Resource> browserDataFile(@PathVariable String version) {
        return browserDataStore.current()
                .filter(published -> published.version().equals(version))
                .map(published -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_ENCODING, GZIP)
                        .cacheControl(CacheControl.maxAge(BROWSER_DATA_MAX_AGE).cachePublic().immutable())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .contentLength(published.sizeBytes())
                        .body((Resource) new FileSystemResource(published.file())))
                .orElseGet(() -> ResponseEntity.notFound().build());
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
            String country,
            // Trips that stop at the station
            int connections
    ) {}
}
