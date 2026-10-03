package com.railreach.service;

import com.railreach.model.JourneyPath;
import com.railreach.model.Station;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

// The hub cache is only loaded once GtfsDataService has filled the transit graph
@DependsOn("gtfsDataService")
@Service
@Slf4j
public class HubRoutingService {

    // B5: hubs are precomputed with this fixed transfer window, whatever a request asks for
    private static final int PRECOMPUTE_MAX_TRANSFER_MINUTES = 180;

    private final TransitGraph transitGraph;
    private final ReachabilitySearch search;
    private final ObjectMapper objectMapper;
    
    @Value("${railreach.hub.cache-file:./hub-cache.json}")
    private String hubCacheFile;
    
    @Value("${railreach.hub.auto-initialize:false}")
    private boolean autoInitialize;
    
    @Value("${railreach.hub.num-hubs:30}")
    private int numHubs;
    
    @Value("${railreach.hub.precompute-transfers:1}")
    private int precomputeTransfers;
    
    @Value("${railreach.hub.timeout-seconds:300}")
    private int timeoutSeconds;
    
    // Hub data structures
    private Set<String> hubStationIds = new HashSet<>();
    private Map<String, Map<String, JourneyPath>> hubReachability = new ConcurrentHashMap<>();
    
    public HubRoutingService(TransitGraph transitGraph,
                            ReachabilitySearch search) {
        this.transitGraph = transitGraph;
        this.search = search;
        this.objectMapper = new ObjectMapper();
    }
    
    @PostConstruct
    public void initializeHubs() {
        if (!transitGraph.isDataLoaded()) {
            log.warn("GTFS data not loaded, skipping hub initialization");
            return;
        }
        
        // Try to load from cache first
        if (loadHubCache()) {
            log.info("✓ Hub data loaded from cache: {} hubs, {} total paths",
                    hubStationIds.size(),
                    hubReachability.values().stream().mapToInt(Map::size).sum());
            return;
        }
        
        // Only auto-initialize if enabled
        if (!autoInitialize) {
            log.info("Hub auto-initialization disabled. Use POST /api/admin/compute-hubs to initialize manually.");
            return;
        }
        
        computeHubs();
    }
    
    /**
     * Manually trigger hub computation. Call via REST API.
     */
    public void computeHubs() {
        log.info("=== Computing Hub-Based Routing ===");
        long startTime = System.currentTimeMillis();
        
        // Clear existing data
        hubStationIds.clear();
        hubReachability.clear();
        
        // Step 1: Identify hubs
        identifyHubs();
        
        // Step 2: Pre-compute reachability
        precomputeHubReachability();
        
        // Step 3: Save to cache
        saveHubCache();
        
        long duration = System.currentTimeMillis() - startTime;
        log.info("✓ Hub computation complete in {}ms", duration);
        log.info("  {} hubs identified", hubStationIds.size());
        log.info("  {} total pre-computed paths", 
                hubReachability.values().stream().mapToInt(Map::size).sum());
    }
    
    private boolean loadHubCache() {
        File cacheFile = new File(hubCacheFile).getAbsoluteFile();
        log.info("Looking for hub cache at: {}", cacheFile.getAbsolutePath());
        
        if (!cacheFile.exists()) {
            log.info("❌ No hub cache file found");
            return false;
        }
        
        try {
            log.info("✓ Cache file exists ({} MB)", cacheFile.length() / 1024 / 1024);
            log.info("  Loading hub cache...");
            
            HubCacheData data = objectMapper.readValue(cacheFile, HubCacheData.class);
            
            this.hubStationIds = new HashSet<>(data.hubIds);
            this.hubReachability = new ConcurrentHashMap<>(data.hubPaths);
            
            log.info("✓ Successfully loaded {} hubs, {} paths", data.numHubs, data.totalPaths);
            return true;
        } catch (Exception e) {
            log.error("❌ FAILED to load hub cache!", e);
            return false;
        }
    }
    
    private void saveHubCache() {
        File cacheFile = new File(hubCacheFile).getAbsoluteFile();
        try {
            log.info("Attempting to save hub cache...");
            log.info("  Target file: {}", cacheFile.getAbsolutePath());
            
            // Ensure parent directory exists
            if (cacheFile.getParentFile() != null) {
                cacheFile.getParentFile().mkdirs();
            }
            
            HubCacheData data = new HubCacheData();
            data.hubIds = new ArrayList<>(hubStationIds);
            data.hubPaths = new HashMap<>(hubReachability);
            data.timestamp = System.currentTimeMillis();
            data.numHubs = hubStationIds.size();
            data.totalPaths = hubReachability.values().stream().mapToInt(Map::size).sum();
            
            log.info("  Serializing {} hubs, {} paths...", data.numHubs, data.totalPaths);
            
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(cacheFile, data);
            
            log.info("✓ Hub cache saved successfully!");
            log.info("  File: {}", cacheFile.getAbsolutePath());
            log.info("  Size: {} MB", cacheFile.length() / 1024 / 1024);
        } catch (Exception e) {
            log.error("❌ FAILED to save hub cache!", e);
            log.error("  Target was: {}", cacheFile.getAbsolutePath());
        }
    }
    
    public void clearCache() {
        File cacheFile = new File(hubCacheFile);
        if (cacheFile.exists()) {
            cacheFile.delete();
            log.info("✓ Hub cache deleted");
        }
        hubStationIds.clear();
        hubReachability.clear();
    }
    
    // Rest of the methods remain the same...
    
    private void identifyHubs() {
        Map<String, Station> allStations = transitGraph.getAllStations();
        
        List<Map.Entry<String, Integer>> stationsByDegree = allStations.keySet().stream()
            .map(stationId -> {
                Set<String> neighbors = transitGraph.getNeighbors(stationId);
                return Map.entry(stationId, neighbors != null ? neighbors.size() : 0);
            })
            .filter(e -> e.getValue() > 0)
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(numHubs)
            .collect(Collectors.toList());
        
        hubStationIds = stationsByDegree.stream()
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
        
        log.info("  Top hubs identified:");
        stationsByDegree.stream().limit(10).forEach(e -> {
            Station s = transitGraph.getStation(e.getKey());
            log.info("    {} - {} connections", 
                    s != null ? s.getName() : e.getKey(), e.getValue());
        });
    }
    
    private void precomputeHubReachability() {
        log.info("  Pre-computing reachability from {} hubs (max {} transfers, {}s timeout per hub)...", 
                hubStationIds.size(), precomputeTransfers, timeoutSeconds);
        
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(4, Runtime.getRuntime().availableProcessors()));
        
        List<Future<?>> futures = new ArrayList<>();
        int processed = 0;
        
        for (String hubId : hubStationIds) {
            final int hubIndex = processed++;
            final String hubIdFinal = hubId;
            
            futures.add(executor.submit(() -> {
                try {
                    long hubStart = System.currentTimeMillis();
                    
                    Map<String, JourneyPath> reachable = search.fullSearch(
                            hubIdFinal, precomputeTransfers, 0, PRECOMPUTE_MAX_TRANSFER_MINUTES);
                    
                    long hubDuration = System.currentTimeMillis() - hubStart;
                    
                    hubReachability.put(hubIdFinal, reachable);
                    
                    Station hub = transitGraph.getStation(hubIdFinal);
                    String hubName = hub != null ? hub.getName() : hubIdFinal;
                    
                    if ((hubIndex + 1) % 10 == 0 || hubDuration > 10000) {
                        log.info("    [{}/{}] {} → {} stations in {}ms", 
                                hubIndex + 1, hubStationIds.size(), hubName, reachable.size(), hubDuration);
                    }
                } catch (Exception e) {
                    log.error("Failed to pre-compute hub {}: {}", hubIdFinal, e.getMessage());
                }
            }));
        }
        
        for (int i = 0; i < futures.size(); i++) {
            try {
                futures.get(i).get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                log.warn("Hub {} timed out after {}s, skipping", i, timeoutSeconds);
                futures.get(i).cancel(true);
            } catch (Exception e) {
                log.error("Hub pre-computation error: {}", e.getMessage());
            }
        }
        
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            log.warn("Executor shutdown interrupted");
        }
    }
    
    public boolean isHubRoutingAvailable() {
        return !hubReachability.isEmpty();
    }
    
    public Set<String> getHubIds() {
        return Collections.unmodifiableSet(hubStationIds);
    }
    
    public Map<String, Object> getStats() {
        return Map.of(
            "hubsCount", hubStationIds.size(),
            "totalPrecomputedPaths", hubReachability.values().stream().mapToInt(Map::size).sum(),
            "cacheFile", hubCacheFile,
            "isAvailable", isHubRoutingAvailable()
        );
    }
    
    // Cache data structure
    public static class HubCacheData {
        public List<String> hubIds;
        public Map<String, Map<String, JourneyPath>> hubPaths;
        public long timestamp;
        public int numHubs;
        public int totalPaths;
    }
}
