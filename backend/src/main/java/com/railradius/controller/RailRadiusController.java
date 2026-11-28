package com.railradius.controller;

import com.railradius.config.AdminAccess;
import com.railradius.dto.BrowserDataInfo;
import com.railradius.dto.Coverage;
import com.railradius.dto.ReachableStation;
import com.railradius.dto.RideListJourneyRequest;
import com.railradius.dto.StationLabel;
import com.railradius.model.Station;
import com.railradius.service.BrowserDataManifest;
import com.railradius.service.CoverageService;
import com.railradius.service.ReachabilityService;
import com.railradius.service.RideListJourneys;
import com.railradius.service.SearchAdmission;
import com.railradius.service.SearchCounts;
import com.railradius.service.SearchRequestValidator.SearchRequest;
import com.railradius.service.StationLabels;
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
import org.springframework.http.HttpStatus;
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
    // A version's file never changes, so browsers may keep it as long as caches allow
    private static final Duration BROWSER_DATA_MAX_AGE = Duration.ofDays(365);
    private static final String GZIP = "gzip";

    private final ReachabilityService reachabilityService;
    private final TransitGraph transitGraph;
    private final StationSearchIndex stationSearchIndex;
    private final CoverageService coverageService;
    private final AsyncTaskExecutor taskExecutor;
    private final AdminAccess adminAccess;
    private final BrowserDataManifest browserDataManifest;
    private final RideListJourneys rideListJourneys;
    private final SearchCounts searchCounts;
    private final long streamTimeoutMs;

    // With @EnableScheduling the task scheduler is an AsyncTaskExecutor too; SSE streams use the task executor
    public RailRadiusController(ReachabilityService reachabilityService, TransitGraph transitGraph,
                               StationSearchIndex stationSearchIndex, CoverageService coverageService,
                               @Qualifier("applicationTaskExecutor") AsyncTaskExecutor taskExecutor,
                               AdminAccess adminAccess, SearchAdmission admission,
                               BrowserDataManifest browserDataManifest, RideListJourneys rideListJourneys,
                               SearchCounts searchCounts) {
        this.reachabilityService = reachabilityService;
        this.transitGraph = transitGraph;
        this.stationSearchIndex = stationSearchIndex;
        this.coverageService = coverageService;
        this.taskExecutor = taskExecutor;
        this.adminAccess = adminAccess;
        this.browserDataManifest = browserDataManifest;
        this.rideListJourneys = rideListJourneys;
        this.searchCounts = searchCounts;
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

    /** Counts a search a browser ran on its own copy of the data (#12), next to the server's streams. */
    @PostMapping("/browser-searches")
    public ResponseEntity<Void> browserSearch() {
        searchCounts.countBrowserSearch();
        return ResponseEntity.noContent().build();
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
    
    /** The stations the map names, for browsers whose engine doesn't run or failed (#108). */
    @GetMapping("/station-labels")
    public List<StationLabel> stationLabels() {
        return StationLabels.of(transitGraph);
    }

    @GetMapping("/coverage")
    public Coverage coverage() {
        return coverageService.coverage();
    }

    /**
     * What a browser searches with (#9, #12); 404 while the data file is built or without a built engine, so the
     * browser searches on the server.
     */
    @GetMapping("/browser-data")
    public ResponseEntity<BrowserDataInfo> browserData() {
        return ResponseEntity.of(browserDataManifest.current());
    }

    /** The gzipped file of that version; 404 for any version but the served graph's. */
    @GetMapping("/browser-data/{version}" + BrowserDataManifest.DATA_SUFFIX)
    public ResponseEntity<Resource> browserDataFile(@PathVariable String version) {
        return browserDataManifest.file(version)
                .map(published -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_ENCODING, GZIP)
                        .cacheControl(CacheControl.maxAge(BROWSER_DATA_MAX_AGE).cachePublic().immutable())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .contentLength(published.sizeBytes())
                        .body((Resource) new FileSystemResource(published.file())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 503 while no data is loaded, so an uptime check on the status code alone notices (#2). */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean dataLoaded = transitGraph.isDataLoaded();
        return ResponseEntity.status(dataLoaded ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "status", dataLoaded ? "UP" : "DOWN",
                "dataLoaded", dataLoaded
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
