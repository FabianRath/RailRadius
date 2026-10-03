package com.railreach.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.railreach.model.JourneyPath;
import com.railreach.model.Station;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class HubRoutingService {

    private final GtfsDataService gtfsDataService;
    private final ReachabilityService reachabilityService;
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
    
    public HubRoutingService(GtfsDataService gtfsDataService,
                            ReachabilityService reachabilityService) {
        this.gtfsDataService = gtfsDataService;
        this.reachabilityService = reachabilityService;
        this.objectMapper = new ObjectMapper();
    }
    
    @PostConstruct
    public void initializeHubs() {
        if (!gtfsDataService.isDataLoaded()) {
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
        Map<String, Station> allStations = gtfsDataService.getAllStations();
        
        List<Map.Entry<String, Integer>> stationsByDegree = allStations.keySet().stream()
            .map(stationId -> {
                Set<String> neighbors = gtfsDataService.getNeighbors(stationId);
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
            Station s = gtfsDataService.getStation(e.getKey());
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
                    
                    Map<String, JourneyPath> reachable = reachabilityService.runBFSInternal(
                            hubIdFinal, precomputeTransfers, 180);
                    
                    long hubDuration = System.currentTimeMillis() - hubStart;
                    
                    hubReachability.put(hubIdFinal, reachable);
                    
                    Station hub = gtfsDataService.getStation(hubIdFinal);
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
    
    public Map<String, JourneyPath> queryViaHubs(String startId, int maxTransfers, int minTransferMins, int maxTransferMins) {
        if (hubReachability.isEmpty()) {
            log.warn("Hub data not available, falling back to regular BFS");
            return reachabilityService.runBFSInternal(startId, maxTransfers, minTransferMins, maxTransferMins, null);
        }
        
        int transfersToHub = Math.min(maxTransfers - 1, 2);
        Map<String, JourneyPath> reachableFromStart = reachabilityService.runBFSInternal(
                startId, transfersToHub, minTransferMins, maxTransferMins, null);
        
        Map<String, JourneyPath> hubsFound = reachableFromStart.entrySet().stream()
            .filter(e -> hubStationIds.contains(e.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        
        log.info("  Hub routing: found {} reachable hubs from start (within {} transfers)", 
                hubsFound.size(), transfersToHub);
        
        Map<String, JourneyPath> allReachable = new HashMap<>(reachableFromStart);
        int pathsViaHubs = 0;
        
        for (Map.Entry<String, JourneyPath> hubEntry : hubsFound.entrySet()) {
            String hubId = hubEntry.getKey();
            JourneyPath pathToHub = hubEntry.getValue();
            
            Map<String, JourneyPath> fromHub = hubReachability.get(hubId);
            if (fromHub == null) continue;
            
            for (Map.Entry<String, JourneyPath> destEntry : fromHub.entrySet()) {
                String destId = destEntry.getKey();
                JourneyPath hubToDest = destEntry.getValue();
                
                if (destId.equals(startId)) continue;
                
                // Validate transfer time at hub
                if (!pathToHub.getConnections().isEmpty() && !hubToDest.getConnections().isEmpty()) {
                    com.railreach.model.Connection arrivalAtHub = pathToHub.getConnections()
                        .get(pathToHub.getConnections().size() - 1);
                    com.railreach.model.Connection departureFromHub = hubToDest.getConnections().get(0);
                    
                    int arrivalTimeSeconds = arrivalAtHub.getArrivalTimeSeconds();
                    int departureTimeSeconds = departureFromHub.getDepartureTimeSeconds();
                    int transferWaitSeconds = departureTimeSeconds - arrivalTimeSeconds;
                    int transferWaitMins = transferWaitSeconds / 60;
                    
                    if (transferWaitMins < -60) transferWaitMins += 24 * 60;
                    
                    if (transferWaitMins < 0 || transferWaitMins > maxTransferMins) {
                        continue;
                    }
                }
                
                int transferAtHub = pathToHub.getConnections().isEmpty() ? 0 : 1;
                int totalTransfers = pathToHub.getTransfers() + transferAtHub + hubToDest.getTransfers();
                if (totalTransfers > maxTransfers) continue;
                
                int totalDuration = pathToHub.getTotalDurationMinutes() + hubToDest.getTotalDurationMinutes();
                
                JourneyPath existing = allReachable.get(destId);
                boolean better = existing == null
                    || totalTransfers < existing.getTransfers()
                    || (totalTransfers == existing.getTransfers() && totalDuration < existing.getTotalDurationMinutes());
                
                if (better) {
                    List<String> combinedStations = new ArrayList<>(pathToHub.getStationIds());
                    combinedStations.addAll(hubToDest.getStationIds().subList(1, hubToDest.getStationIds().size()));
                    
                    List<com.railreach.model.Connection> combinedConns = new ArrayList<>(pathToHub.getConnections());
                    combinedConns.addAll(hubToDest.getConnections());
                    
                    int recalcDuration = totalDuration;
                    if (!pathToHub.getConnections().isEmpty() && !hubToDest.getConnections().isEmpty()) {
                        com.railreach.model.Connection arrivalAtHub = pathToHub.getConnections()
                            .get(pathToHub.getConnections().size() - 1);
                        com.railreach.model.Connection departureFromHub = hubToDest.getConnections().get(0);
                        int transferWaitSeconds = departureFromHub.getDepartureTimeSeconds() - arrivalAtHub.getArrivalTimeSeconds();
                        int transferWaitMins = transferWaitSeconds / 60;
                        if (transferWaitMins < -60) transferWaitMins += 24 * 60;
                        recalcDuration = pathToHub.getTotalDurationMinutes() + transferWaitMins + hubToDest.getTotalDurationMinutes();
                    }
                    
                    JourneyPath mergedPath = JourneyPath.builder()
                        .stationIds(combinedStations)
                        .connections(combinedConns)
                        .transfers(totalTransfers)
                        .totalDurationMinutes(recalcDuration)
                        .build();
                    
                    allReachable.put(destId, mergedPath);
                    pathsViaHubs++;
                    
                    if (pathsViaHubs <= 5) {
                        Station dest = gtfsDataService.getStation(destId);
                        Station hubStation = gtfsDataService.getStation(hubId);
                        log.info("  Hub path example #{}: {} transfers total (start→{}={}, @hub=+{}, {}→dest={}) to {}",
                                pathsViaHubs,
                                totalTransfers,
                                hubStation != null ? hubStation.getName() : hubId,
                                pathToHub.getTransfers(),
                                transferAtHub,
                                hubStation != null ? hubStation.getName() : hubId,
                                hubToDest.getTransfers(),
                                dest != null ? dest.getName() : destId);
                    }
                }
            }
        }
        
        log.info("  Hub routing result: {} total stations ({} via hubs)", 
                allReachable.size(), pathsViaHubs);
        
        return allReachable;
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
