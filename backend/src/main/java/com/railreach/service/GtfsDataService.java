package com.railreach.service;

import com.railreach.model.Connection;
import com.railreach.model.Station;
import com.railreach.util.GtfsTime;
import com.railreach.util.StopIds;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
public class GtfsDataService {

    @Value("${railreach.gtfs.directory:./gtfs}")
    private String gtfsDirectory;
    
    @Value("${railreach.gtfs.cache-file:gtfs-cache.json}")
    private String gtfsCacheFile;
    
    @Value("${railreach.gtfs.auto-load-from-files:false}")
    private boolean autoLoadFromFiles;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private boolean dataLoaded = false;

    // Canonical stop ID -> Station
    private final Map<String, Station> stations = new ConcurrentHashMap<>();
    // Canonical stop ID -> set of canonical neighbor stop IDs
    private final Map<String, Set<String>> graph = new ConcurrentHashMap<>();
    // "fromId->toId" -> list of connections
    private final Map<String, List<Connection>> connectionInfo = new ConcurrentHashMap<>();
    // tripId -> ordered list of canonical stop IDs on that trip
    private final Map<String, List<String>> tripStops = new ConcurrentHashMap<>();
    // canonical stopId -> set of tripIds that serve it
    private final Map<String, Set<String>> stopTrips = new ConcurrentHashMap<>();
    // Lazy reverse index: tripId -> (stopId -> position). Built on first access.
    private final Map<String, Map<String, Integer>> tripStopIndex = new ConcurrentHashMap<>();
    // Lazy per-edge trip index: "fromId->toId" -> (tripId -> Connection). Built on first access.
    private final Map<String, Map<String, Connection>> connectionByTrip = new ConcurrentHashMap<>();

    // Patterns that indicate arrival/departure split stops
    private static final List<Pattern> ARRIVAL_DEPARTURE_PATTERNS = List.of(
        Pattern.compile("_ARRIVAL$",    Pattern.CASE_INSENSITIVE),
        Pattern.compile("_DEPARTURE$",  Pattern.CASE_INSENSITIVE),
        Pattern.compile("_ARR$",        Pattern.CASE_INSENSITIVE),
        Pattern.compile("_DEP$",        Pattern.CASE_INSENSITIVE),
        Pattern.compile("^ARRIVAL_",    Pattern.CASE_INSENSITIVE),
        Pattern.compile("^DEPARTURE_",  Pattern.CASE_INSENSITIVE),
        Pattern.compile("^ARR_",        Pattern.CASE_INSENSITIVE),
        Pattern.compile("^DEP_",        Pattern.CASE_INSENSITIVE)
    );

    @PostConstruct
    public void initialize() {
        log.info("=== RailReach GTFS Data Loader ===");
        
        // Try to load from cache first
        if (loadFromCache()) {
            log.info("✓ GTFS data loaded from cache");
            dataLoaded = true;
            return;
        }
        
        // Only load from files if auto-load enabled or no cache exists
        if (!autoLoadFromFiles) {
            log.info("GTFS auto-load disabled. Use POST /api/admin/reload-gtfs to load from files.");
            return;
        }
        
        loadFromFiles();
    }
    
    public void loadFromFiles() {
        log.info("=== Loading GTFS from files ===");
        log.info("Scanning directory: {}", gtfsDirectory);
        
        // Clear existing data
        stations.clear();
        graph.clear();
        connectionInfo.clear();
        tripStops.clear();
        stopTrips.clear();
        tripStopIndex.clear();
        connectionByTrip.clear();
        dataLoaded = false;

        File dir = new File(gtfsDirectory);
        if (!dir.exists() || !dir.isDirectory()) {
            throw new RuntimeException("GTFS directory not found: " + gtfsDirectory);
        }

        File[] zipFiles = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".zip"));

        if (zipFiles == null || zipFiles.length == 0) {
            throw new RuntimeException("No .zip files found in: " + gtfsDirectory);
        }

        Arrays.sort(zipFiles, Comparator.comparing(File::getName));
        log.info("Found {} GTFS file(s):", zipFiles.length);
        for (File f : zipFiles) {
            log.info("  - {}", f.getName());
        }

        long startTime = System.currentTimeMillis();
        int fileIndex = 0;
        for (File zipFile : zipFiles) {
            fileIndex++;
            String source = zipFile.getName()
                    .replaceFirst("(?i)\\.zip$", "")
                    .replaceFirst("(?i)^gtfs_", "");
            log.info("---------------------------------------------------------"
                    + "-----------------------------");
            log.info("[{}/{}] Loading: {} (source={})",
                    fileIndex, zipFiles.length, zipFile.getName(), source);
            try {
                loadSingleFeed(zipFile.getAbsolutePath(), source);
            } catch (Exception e) {
                log.error("Failed to load {}: {}", zipFile.getName(), e.getMessage(), e);
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        
        // Cross-border deduplication: merge stations across countries with same name + location
        log.info("=== Cross-border station deduplication ===");
        deduplicateAcrossFeeds();
        
        long connectedStations = stations.values().stream()
                .filter(s -> graph.containsKey(s.getId()) && !graph.get(s.getId()).isEmpty())
                .count();
        long isolatedStations = stations.size() - connectedStations;
        
        log.info("=== Loading complete in {}ms ===", duration);
        log.info("Total stations  : {} ({} connected, {} isolated)", 
                stations.size(), connectedStations, isolatedStations);
        log.info("Graph nodes     : {}", graph.size());
        log.info("Total edges     : {}", graph.values().stream().mapToInt(Set::size).sum());
        
        // Save to cache
        saveToCache();
        dataLoaded = true;
    }

    private void deduplicateAcrossFeeds() {
        log.info("=== Station deduplication (within + cross-border) ===");
        
        // Phase 1: Within-country exact duplicates (strict)
        Map<String, String> aliasToCanonical = deduplicateWithinCountry();
        
        // Phase 2: Cross-border fuzzy matching
        deduplicateCrossBorder(aliasToCanonical);
        
        // Apply all remappings
        applyDeduplication(aliasToCanonical);
        
        log.info("  Deduplication complete: {} total stations merged", aliasToCanonical.size());
    }

    private Map<String, String> deduplicateWithinCountry() {
        Map<String, String> aliasToCanonical = new HashMap<>();
        
        // Group by exact name
        Map<String, List<Station>> byName = new HashMap<>();
        for (Station station : stations.values()) {
            if (station.getName() != null) {
                byName.computeIfAbsent(station.getName().trim(), k -> new ArrayList<>()).add(station);
            }
        }
        
        int withinCountryMerges = 0;
        for (Map.Entry<String, List<Station>> entry : byName.entrySet()) {
            List<Station> group = entry.getValue();
            if (group.size() < 2) continue;
            
            // Group by source country
            Map<String, List<Station>> bySource = new HashMap<>();
            for (Station s : group) {
                String source = StopIds.source(s.getId());
                bySource.computeIfAbsent(source, k -> new ArrayList<>()).add(s);
            }
            
            // Within each source, merge stations with exact name and nearby location
            for (List<Station> sourceGroup : bySource.values()) {
                if (sourceGroup.size() < 2) continue;
                
                // Merge nearby stations (within 200m)
                for (int i = 0; i < sourceGroup.size(); i++) {
                    for (int j = i + 1; j < sourceGroup.size(); j++) {
                        Station s1 = sourceGroup.get(i);
                        Station s2 = sourceGroup.get(j);
                        
                        if (aliasToCanonical.containsKey(s1.getId()) || 
                            aliasToCanonical.containsKey(s2.getId())) continue;
                        
                        if (isNearby(s1.getLatitude(), s1.getLongitude(),
                                    s2.getLatitude(), s2.getLongitude(), 0.002)) { // ~200m
                            
                            int s1Connections = graph.getOrDefault(s1.getId(), Set.of()).size();
                            int s2Connections = graph.getOrDefault(s2.getId(), Set.of()).size();
                            
                            String canonical = s1Connections >= s2Connections ? s1.getId() : s2.getId();
                            String alias = canonical.equals(s1.getId()) ? s2.getId() : s1.getId();
                            
                            aliasToCanonical.put(alias, canonical);
                            withinCountryMerges++;
                        }
                    }
                }
            }
        }
        
        log.info("  Within-country duplicates merged: {}", withinCountryMerges);
        return aliasToCanonical;
    }

    private int deduplicateCrossBorder(Map<String, String> aliasToCanonical) {
        // Spatial bucketing for cross-border stations
        Map<String, List<Station>> byCell = new HashMap<>();
        double CELL_SIZE = 0.01; // ~1km cells
        
        for (Station station : stations.values()) {
            // Skip if already aliased
            if (aliasToCanonical.containsKey(station.getId())) continue;
            
            int cellX = (int) Math.floor(station.getLatitude() / CELL_SIZE);
            int cellY = (int) Math.floor(station.getLongitude() / CELL_SIZE);
            String cellKey = cellX + ":" + cellY;
            byCell.computeIfAbsent(cellKey, k -> new ArrayList<>()).add(station);
        }
        
        int crossBorderMerges = 0;
        Set<String> processedPairs = new HashSet<>();
        
        for (String cellKey : byCell.keySet()) {
            String[] parts = cellKey.split(":");
            int baseX = Integer.parseInt(parts[0]);
            int baseY = Integer.parseInt(parts[1]);
            
            // Check neighboring cells
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    String neighbourKey = (baseX + dx) + ":" + (baseY + dy);
                    List<Station> neighbours = byCell.get(neighbourKey);
                    if (neighbours == null) continue;
                    
                    List<Station> currentCell = byCell.get(cellKey);
                    for (Station s1 : currentCell) {
                        for (Station s2 : neighbours) {
                            String pairId = s1.getId().compareTo(s2.getId()) < 0 
                                ? s1.getId() + "|" + s2.getId()
                                : s2.getId() + "|" + s1.getId();
                            
                            if (processedPairs.contains(pairId)) continue;
                            processedPairs.add(pairId);
                            
                            if (aliasToCanonical.containsKey(s1.getId()) || 
                                aliasToCanonical.containsKey(s2.getId())) continue;
                            
                            // Only merge DIFFERENT countries
                            if (StopIds.source(s1.getId()).equals(StopIds.source(s2.getId()))) continue;
                            
                            // Geographic proximity (~500m)
                            if (isNearby(s1.getLatitude(), s1.getLongitude(),
                                        s2.getLatitude(), s2.getLongitude(), 0.005)) {
                                
                                // Fuzzy name matching with lower threshold
                                double sim = tokenSimilarity(s1.getName(), s2.getName());
                                
                                // Debug logging for Paris and other major stations
                                if ((s1.getName().toLowerCase().contains("paris") || 
                                     s2.getName().toLowerCase().contains("paris") ||
                                     s1.getName().toLowerCase().contains("köln") ||
                                     s2.getName().toLowerCase().contains("köln")) &&
                                    sim > 0.3) {
                                    log.info("  Cross-border candidate: '{}' <-> '{}' (sim={})",
                                            s1.getName(), s2.getName(), String.format("%.2f", sim));
                                }
                                
                                // Accept if similarity >= 0.4 (was 0.5)
                                // This catches "Paris Gare du Nord" + "Paris Nord (FR)" = 0.4
                                if (sim >= 0.4) {
                                    int s1Connections = graph.getOrDefault(s1.getId(), Set.of()).size();
                                    int s2Connections = graph.getOrDefault(s2.getId(), Set.of()).size();
                                    
                                    String canonical = s1Connections >= s2Connections ? s1.getId() : s2.getId();
                                    String alias = canonical.equals(s1.getId()) ? s2.getId() : s1.getId();
                                    
                                    aliasToCanonical.put(alias, canonical);
                                    crossBorderMerges++;
                                    
                                    log.info("  MERGED: '{}' ({}) -> '{}' ({}) [sim={}]",
                                            alias, s1.getName().equals(alias) ? s1.getName() : s2.getName(),
                                            canonical, s1.getName().equals(canonical) ? s1.getName() : s2.getName(),
                                            String.format("%.2f", sim));
                                }
                            }
                        }
                    }
                }
            }
        }
        
        log.info("  Cross-border stations merged: {}", crossBorderMerges);
        return crossBorderMerges;
    }

    private void applyDeduplication(Map<String, String> aliasToCanonical) {
        if (aliasToCanonical.isEmpty()) return;

        int processed = 0;
        // Remap graph edges: alias -> canonical
        for (Map.Entry<String, String> mapping : aliasToCanonical.entrySet()) {
            processed++;
            if(processed % 1000 == 0){
                log.info("  Applying remapping: {}/{} completed", processed, aliasToCanonical.size());
            }
            String alias = mapping.getKey();
            String canonical = mapping.getValue();

            // Merge outgoing edges from alias into canonical
            Set<String> aliasNeighbors = graph.remove(alias);
            if (aliasNeighbors != null) {
                Set<String> canonicalNeighbors = graph.computeIfAbsent(canonical, k -> new HashSet<>());
                for (String neighbor : aliasNeighbors) {
                    String remappedNeighbor = aliasToCanonical.getOrDefault(neighbor, neighbor);
                    if (!remappedNeighbor.equals(canonical)) { // avoid self‑loops
                        canonicalNeighbors.add(remappedNeighbor);
                    }
                }
            }

            // Remap incoming edges: any edge pointing to alias should point to canonical
            for (Set<String> neighbors : graph.values()) {
                if (neighbors.remove(alias)) {
                    neighbors.add(canonical);
                }
            }

            // Merge connection info
            List<String> keysToRemap = new ArrayList<>();
            for (String key : connectionInfo.keySet()) {
                if (key.startsWith(alias + "->") || key.endsWith("->" + alias)) {
                    keysToRemap.add(key);
                }
            }

            for (String oldKey : keysToRemap) {
                List<Connection> connections = connectionInfo.remove(oldKey);
                String[] parts = oldKey.split("->");
                String newFrom = aliasToCanonical.getOrDefault(parts[0], parts[0]);
                String newTo = aliasToCanonical.getOrDefault(parts[1], parts[1]);
                if (!newFrom.equals(newTo)) {
                    String newKey = newFrom + "->" + newTo;
                    connectionInfo.computeIfAbsent(newKey, k -> new ArrayList<>()).addAll(connections);
                }
            }

            // Merge tripStops and stopTrips
            Set<String> aliasTrips = stopTrips.remove(alias);
            if (aliasTrips != null) {
                stopTrips.computeIfAbsent(canonical, k -> new HashSet<>()).addAll(aliasTrips);

                // Update trip stop sequences
                for (String tripId : aliasTrips) {
                    List<String> stops = tripStops.get(tripId);
                    if (stops != null) {
                        for (int i = 0; i < stops.size(); i++) {
                            if (stops.get(i).equals(alias)) {
                                stops.set(i, canonical);
                            }
                        }
                    }
                }
            }

            // Remove alias station from stations map
            stations.remove(alias);
        }
    }
    
    private double tokenSimilarity(String name1, String name2) {
        if (name1 == null || name2 == null) return 0.0;
        Set<String> tokens1 = tokenize(name1);
        Set<String> tokens2 = tokenize(name2);
        if (tokens1.isEmpty() || tokens2.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(tokens1);
        intersection.retainAll(tokens2);
        Set<String> union = new HashSet<>(tokens1);
        union.addAll(tokens2);

        return (double) intersection.size() / union.size();
    }

    private Set<String> tokenize(String name) {
        // Lowercase, split on non‑word characters, and collect non‑empty tokens
        return Arrays.stream(name.toLowerCase().split("[\\s\\p{Punct}]+"))
                     .filter(token -> !token.isEmpty())
                     .collect(Collectors.toSet());
    }

    private void loadSingleFeed(String path, String source) throws Exception {
        // Step 1: Load reference tables (routes, stops, trips)
        Map<String, String> routeNames = new HashMap<>();
        Set<String> railRouteIds = new HashSet<>(); // Track which routes are rail
        Map<String, GtfsStop> stops = new HashMap<>();
        Map<String, String> tripToRoute = new HashMap<>();
        
        try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(path)) {
            loadRoutes(zipFile, routeNames, railRouteIds);
            loadStops(zipFile, stops);
            loadTrips(zipFile, tripToRoute);
            
            log.info("  Loaded {} rail routes, {} stops, {} trips", 
                    routeNames.size(), stops.size(), tripToRoute.size());
            
            if (stops.isEmpty()) {
                log.error("  No stops loaded! Check stops.txt format");
                return;
            }
            
            // Step 2: Deduplicate stops and build ID mapping
            Map<String, String> rawToCanonical = deduplicateStops(stops, source);
            
            log.info("  Stop ID mapping: {} raw IDs -> {} canonical IDs",
                    rawToCanonical.size(), rawToCanonical.values().stream().distinct().count());
            
            if (rawToCanonical.isEmpty()) {
                log.error("  No stop ID mappings created! Cannot build graph");
                return;
            }
            
            // Step 3: Stream-process stop_times incrementally (only for rail routes)
            processStopTimes(zipFile, source, rawToCanonical, tripToRoute, routeNames, railRouteIds);
        }
    }

    private Map<String, String> deduplicateStops(Map<String, GtfsStop> stops, String source) {
        Map<String, String> rawToCanonical = new HashMap<>();
        int stopsSkipped = 0;

        // Filter valid stops (with coordinates)
        Map<String, GtfsStop> validStops = new LinkedHashMap<>();
        for (GtfsStop stop : stops.values()) {
            if (stop.lat() == 0.0 && stop.lon() == 0.0) {
                stopsSkipped++;
                continue;
            }
            validStops.put(stop.stopId(), stop);
        }

        // Group by name for deduplication
        Map<String, List<GtfsStop>> byName = new HashMap<>();
        for (GtfsStop stop : validStops.values()) {
            if (stop.name() != null) {
                byName.computeIfAbsent(stop.name().trim(), k -> new ArrayList<>()).add(stop);
            }
        }

        // Deduplicate within each name group (same name + nearby location)
        Map<String, String> rawToRawCanonical = new HashMap<>();
        for (List<GtfsStop> group : byName.values()) {
            if (group.size() == 1) {
                rawToRawCanonical.put(group.get(0).stopId(), group.get(0).stopId());
                continue;
            }
            
            group.sort(Comparator.comparing(GtfsStop::stopId));
            for (int i = 0; i < group.size(); i++) {
                GtfsStop candidate = group.get(i);
                if (rawToRawCanonical.containsKey(candidate.stopId())) continue;

                boolean mapped = false;
                for (int j = 0; j < i; j++) {
                    GtfsStop existing = group.get(j);
                    if (isNearby(candidate.lat(), candidate.lon(),
                                 existing.lat(), existing.lon(), 0.0015)) {
                        String existingCanonical = rawToRawCanonical.getOrDefault(
                                existing.stopId(), existing.stopId());
                        rawToRawCanonical.put(candidate.stopId(), existingCanonical);
                        mapped = true;
                        break;
                    }
                }
                if (!mapped) {
                    rawToRawCanonical.put(candidate.stopId(), candidate.stopId());
                }
            }
        }

        // Build final mapping and register stations
        for (GtfsStop stop : validStops.values()) {
            String rawCanonical = rawToRawCanonical.getOrDefault(stop.stopId(), stop.stopId());
            String canonical = canonicaliseStopId(rawCanonical, source);
            rawToCanonical.put(stop.stopId(), canonical);

            stations.computeIfAbsent(canonical, id -> {
                GtfsStop rep = validStops.get(rawCanonical);
                if (rep == null) rep = stop;
                return Station.builder()
                        .id(canonical)
                        .name(rep.name() != null ? rep.name() : canonical)
                        .latitude(rep.lat())
                        .longitude(rep.lon())
                        .parentStation(rep.parentStation() != null
                                ? canonicaliseStopId(rep.parentStation(), source) : null)
                        .build();
            });
        }

        log.info("  Stops: {} valid, {} unique after dedup, {} skipped (no coords)",
                validStops.size(), rawToRawCanonical.values().stream().distinct().count(), stopsSkipped);
        
        return rawToCanonical;
    }

    private void processStopTimes(java.util.zip.ZipFile zipFile, String source,
                                   Map<String, String> rawToCanonical,
                                   Map<String, String> tripToRoute,
                                   Map<String, String> routeNames,
                                   Set<String> railRouteIds) throws Exception {
        java.util.zip.ZipEntry entry = zipFile.getEntry("stop_times.txt");
        if (entry == null) {
            log.warn("  No stop_times.txt found");
            return;
        }

        final int BATCH_SIZE = 100000; // Process every 100k trips to avoid OOM
        Map<String, List<GtfsStopTime>> stopTimesByTrip = new HashMap<>();
        int totalTripsProcessed = 0;
        int totalEdgesAdded = 0;
        
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(zipFile.getInputStream(entry), "UTF-8"))) {
            String headerLine = reader.readLine();
            if (headerLine == null) return;
            
            String[] headers = parseCsvLine(headerLine); // Use CSV parser for headers too
            int tripIdx = indexOf(headers, "trip_id");
            int arrIdx = indexOf(headers, "arrival_time");
            int depIdx = indexOf(headers, "departure_time");
            int stopIdx = indexOf(headers, "stop_id");
            int seqIdx = indexOf(headers, "stop_sequence");

            String line;
            int linesRead = 0;
            int linesSkippedNonRail = 0;
            while ((line = reader.readLine()) != null) {
                String[] fields = parseCsvLine(line);
                String tripId = getField(fields, tripIdx);
                String stopId = getField(fields, stopIdx);
                if (tripId == null || stopId == null) continue;

                // Skip trips that aren't on rail routes
                String routeId = tripToRoute.get(tripId);
                if (routeId == null || !railRouteIds.contains(routeId)) {
                    linesSkippedNonRail++;
                    continue;
                }

                GtfsStopTime st = new GtfsStopTime(stopId,
                        parseInt(getField(fields, seqIdx)),
                        GtfsTime.parseSeconds(getField(fields, arrIdx)),
                        GtfsTime.parseSeconds(getField(fields, depIdx)));
                stopTimesByTrip.computeIfAbsent(tripId, k -> new ArrayList<>()).add(st);
                
                linesRead++;
                if (linesRead % 500000 == 0) {
                    log.info("  ... {} rail stop_times read ({} non-rail skipped), {} trips buffered", 
                            linesRead, linesSkippedNonRail, stopTimesByTrip.size());
                }
                
                // Process batch when buffer gets too large
                if (stopTimesByTrip.size() >= BATCH_SIZE) {
                    int edgesAdded = processTripBatch(stopTimesByTrip, source, rawToCanonical, tripToRoute, routeNames);
                    totalEdgesAdded += edgesAdded;
                    totalTripsProcessed += stopTimesByTrip.size();
                    
                    if (totalTripsProcessed % 50000 == 0) {
                        log.info("  ... {} trips processed total", totalTripsProcessed);
                    }
                    
                    stopTimesByTrip.clear(); // Free memory
                }
            }
        }

        // Process remaining trips
        if (!stopTimesByTrip.isEmpty()) {
            int edgesAdded = processTripBatch(stopTimesByTrip, source, rawToCanonical, tripToRoute, routeNames);
            totalEdgesAdded += edgesAdded;
            totalTripsProcessed += stopTimesByTrip.size();
        }

        log.info("  Trips: {}, edges: {}", totalTripsProcessed, totalEdgesAdded);
    }

    private int processTripBatch(Map<String, List<GtfsStopTime>> stopTimesByTrip, String source,
                                  Map<String, String> rawToCanonical,
                                  Map<String, String> tripToRoute,
                                  Map<String, String> routeNames) {
        // Sort each trip's stop times
        stopTimesByTrip.values().forEach(list ->
                list.sort(Comparator.comparingInt(GtfsStopTime::sequence)));

        int edgesAdded = 0;
        int edgesSkippedNoMapping = 0;
        int edgesSkippedSelfLoop = 0;

        for (Map.Entry<String, List<GtfsStopTime>> tripEntry : stopTimesByTrip.entrySet()) {
            String rawTripId = tripEntry.getKey();
            String tripId = source + ":" + rawTripId;
            String routeId = tripToRoute.get(rawTripId);
            
            // Skip if trip has no route or route is not a rail route
            if (routeId == null || !routeNames.containsKey(routeId)) continue;
            
            String routeName = routeNames.get(routeId);

            List<GtfsStopTime> tripStopTimes = tripEntry.getValue();
            if (tripStopTimes.size() < 2) continue;

            // Build trip stop sequence
            List<String> tripStopSeq = new ArrayList<>();
            for (GtfsStopTime st : tripStopTimes) {
                String canonical = rawToCanonical.get(st.stopId());
                if (canonical != null) tripStopSeq.add(canonical);
            }
            
            if (tripStopSeq.size() >= 2) {
                tripStops.put(tripId, tripStopSeq);
                for (String stopId : tripStopSeq) {
                    stopTrips.computeIfAbsent(stopId, k -> new HashSet<>()).add(tripId);
                }
            }

            // Build edges
            for (int i = 0; i < tripStopTimes.size() - 1; i++) {
                GtfsStopTime from = tripStopTimes.get(i);
                GtfsStopTime to = tripStopTimes.get(i + 1);

                String fromCanonical = rawToCanonical.get(from.stopId());
                String toCanonical = rawToCanonical.get(to.stopId());

                if (fromCanonical == null || toCanonical == null) {
                    edgesSkippedNoMapping++;
                    continue;
                }
                if (fromCanonical.equals(toCanonical)) {
                    edgesSkippedSelfLoop++;
                    continue;
                }

                graph.computeIfAbsent(fromCanonical, k -> new HashSet<>()).add(toCanonical);

                String key = fromCanonical + "->" + toCanonical;
                Connection conn = Connection.builder()
                        .tripId(tripId)
                        .routeId(source + ":" + routeId)
                        .routeName(routeName)
                        .fromStopId(fromCanonical)
                        .toStopId(toCanonical)
                        .departureTimeSeconds(from.departureSeconds())  // Store as int, no conversion!
                        .arrivalTimeSeconds(to.arrivalSeconds())        // Store as int, no conversion!
                        .stopsApart(1)
                        .build();

                connectionInfo.computeIfAbsent(key, k -> new ArrayList<>()).add(conn);
                edgesAdded++;
            }
        }

        if (edgesSkippedNoMapping > 0 || edgesSkippedSelfLoop > 0) {
            log.warn("  Batch: {} edges added, {} skipped (no mapping), {} skipped (self-loop)",
                    edgesAdded, edgesSkippedNoMapping, edgesSkippedSelfLoop);
        }

        return edgesAdded;
    }

    // -------------------------------------------------------------------------
    // CSV loaders
    // -------------------------------------------------------------------------
    
    private void loadRoutes(java.util.zip.ZipFile zipFile, Map<String, String> routeNames, Set<String> railRouteIds) throws Exception {
        java.util.zip.ZipEntry entry = zipFile.getEntry("routes.txt");
        if (entry == null) return;
        
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(zipFile.getInputStream(entry), "UTF-8"))) {
            String headerLine = reader.readLine();
            if (headerLine == null) return;
            
            String[] headers = parseCsvLine(headerLine); // Use CSV parser for headers too
            int idIdx = indexOf(headers, "route_id");
            int shortIdx = indexOf(headers, "route_short_name");
            int longIdx = indexOf(headers, "route_long_name");
            int typeIdx = indexOf(headers, "route_type");
            
            String line;
            int totalRoutes = 0;
            int railRoutes = 0;
            Map<Integer, Integer> routeTypeCounts = new java.util.HashMap<>();
            
            while ((line = reader.readLine()) != null) {
                String[] fields = parseCsvLine(line);
                String id = getField(fields, idIdx);
                String shortName = getField(fields, shortIdx);
                String longName = getField(fields, longIdx);
                String routeType = getField(fields, typeIdx);
                
                totalRoutes++;
                
                // Track route type distribution
                if (routeType != null) {
                    int type = parseInt(routeType);
                    routeTypeCounts.put(type, routeTypeCounts.getOrDefault(type, 0) + 1);
                }
                
                // Only include rail routes using strict whitelist
                // Standard GTFS: 2 = Rail
                // Extended GTFS Rail types: 100-109 (various intercity/regional rail)
                // EXCLUDE: 0=tram, 1=subway, 3=bus, 400=urban railway, 700+=buses/trams/etc
                if (routeType != null) {
                    int type = parseInt(routeType);
                    // Whitelist: only standard rail (2) and extended rail types (100-109)
                    // Specifically: 100=Railway, 101=HighSpeed, 102=LongDistance, 
                    //               103=InterRegional, 105=Sleeper, 106=Regional, 
                    //               107=TouristRailway, 109=SuburbanRailway
                    boolean isRail = (type == 2) || (type >= 100 && type <= 106);
                    if (!isRail) continue;
                }
                
                railRoutes++;
                if (id != null) {
                    String name = (shortName != null && !shortName.isBlank()) ? shortName : longName;
                    routeNames.put(id, name != null ? name : "Unknown");
                    railRouteIds.add(id); // Track this as a rail route
                }
            }
            
            log.info("  Routes: {} total, {} rail routes selected", totalRoutes, railRoutes);
            log.info("  Route type distribution: {}", routeTypeCounts);
        }
    }
    
    private void loadStops(java.util.zip.ZipFile zipFile, Map<String, GtfsStop> stops) throws Exception {
        java.util.zip.ZipEntry entry = zipFile.getEntry("stops.txt");
        if (entry == null) return;
        
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(zipFile.getInputStream(entry), "UTF-8"))) {
            String headerLine = reader.readLine();
            if (headerLine == null) return;
            
            String[] headers = parseCsvLine(headerLine); // Use CSV parser for headers too
            int idIdx = indexOf(headers, "stop_id");
            int nameIdx = indexOf(headers, "stop_name");
            int latIdx = indexOf(headers, "stop_lat");
            int lonIdx = indexOf(headers, "stop_lon");
            int parentIdx = indexOf(headers, "parent_station");
            
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = parseCsvLine(line);
                String stopId = getField(fields, idIdx);
                if (stopId == null) continue;
                stops.put(stopId, new GtfsStop(stopId,
                        getField(fields, nameIdx),
                        parseDouble(getField(fields, latIdx)),
                        parseDouble(getField(fields, lonIdx)),
                        getField(fields, parentIdx)));
            }
        }
    }
    
    private void loadTrips(java.util.zip.ZipFile zipFile, Map<String, String> tripToRoute) throws Exception {
        java.util.zip.ZipEntry entry = zipFile.getEntry("trips.txt");
        if (entry == null) return;
        
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(zipFile.getInputStream(entry), "UTF-8"))) {
            String headerLine = reader.readLine();
            if (headerLine == null) return;
            
            String[] headers = parseCsvLine(headerLine); // Use CSV parser for headers too
            int routeIdx = indexOf(headers, "route_id");
            int tripIdx = indexOf(headers, "trip_id");
            
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = parseCsvLine(line);
                String tripId = getField(fields, tripIdx);
                String routeId = getField(fields, routeIdx);
                if (tripId != null && routeId != null) {
                    tripToRoute.put(tripId, routeId);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
    
    private boolean isNearby(double lat1, double lon1, double lat2, double lon2, double thresholdDeg) {
        return Math.abs(lat1 - lat2) < thresholdDeg && Math.abs(lon1 - lon2) < thresholdDeg;
    }

    private String canonicaliseStopId(String rawId, String source) {
        if (rawId == null) return null;
        String id = rawId.trim();

        boolean changed = true;
        while (changed) {
            changed = false;
            for (Pattern p : ARRIVAL_DEPARTURE_PATTERNS) {
                String replaced = p.matcher(id).replaceAll("");
                if (!replaced.equals(id)) {
                    id = replaced;
                    changed = true;
                }
            }
        }

        return StopIds.of(source, id);
    }

    // -------------------------------------------------------------------------
    // CSV parsing utilities
    // -------------------------------------------------------------------------
    
    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString().trim());
        return fields.toArray(new String[0]);
    }
    
    private int indexOf(String[] headers, String name) {
        for (int i = 0; i < headers.length; i++) {
            if (headers[i].trim().equalsIgnoreCase(name)) return i;
        }
        return -1;
    }
    
    private String getField(String[] fields, int idx) {
        if (idx < 0 || idx >= fields.length) return null;
        String val = fields[idx].trim();
        if (val.startsWith("\"") && val.endsWith("\"")) {
            val = val.substring(1, val.length() - 1);
        }
        return val.isEmpty() ? null : val;
    }
    
    private double parseDouble(String s) {
        if (s == null || s.isEmpty()) return 0.0;
        try { return Double.parseDouble(s); }
        catch (NumberFormatException e) { return 0.0; }
    }
    
    private int parseInt(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return 0; }
    }
    

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------
    
    public Station getStation(String stopId) {
        return stations.get(stopId);
    }

    public Map<String, Station> getAllStations() {
        return Collections.unmodifiableMap(stations);
    }

    public Set<String> getNeighbors(String stopId) {
        return graph.getOrDefault(stopId, Collections.emptySet());
    }

    public List<Connection> getConnections(String fromId, String toId) {
        return connectionInfo.getOrDefault(fromId + "->" + toId, Collections.emptyList());
    }

    public Set<String> getTripsAtStop(String stopId) {
        return stopTrips.getOrDefault(stopId, Collections.emptySet());
    }

    public List<String> getStopsOnTrip(String tripId) {
        return tripStops.getOrDefault(tripId, Collections.emptyList());
    }

    /**
     * Returns the position of stopId within a trip's stop sequence, or -1 if not found.
     * Uses a lazily-populated reverse index for O(1) lookup instead of List.indexOf().
     */
    public int getStopIndexOnTrip(String tripId, String stopId) {
        Map<String, Integer> idx = tripStopIndex.computeIfAbsent(tripId, tid -> {
            List<String> stops = tripStops.getOrDefault(tid, Collections.emptyList());
            Map<String, Integer> m = new java.util.HashMap<>(stops.size() * 2);
            for (int i = 0; i < stops.size(); i++) {
                m.put(stops.get(i), i);
            }
            return m;
        });
        return idx.getOrDefault(stopId, -1);
    }

    /**
     * Returns the Connection for a specific tripId between two adjacent stops, or null.
     * O(1) via a pre-built tripId→Connection index per edge, avoiding stream().filter().
     */
    public Connection getConnectionByTrip(String fromId, String toId, String tripId) {
        return connectionByTrip.computeIfAbsent(fromId + "->" + toId, key -> {
            List<Connection> conns = connectionInfo.getOrDefault(key, Collections.emptyList());
            Map<String, Connection> m = new java.util.HashMap<>(conns.size() * 2);
            for (Connection c : conns) {
                m.put(c.getTripId(), c);
            }
            return m;
        }).get(tripId);
    }

    public boolean isDataLoaded() {
        return !stations.isEmpty() && !graph.isEmpty();
    }

    public Map<String, Object> getStats() {
        return Map.of(
                "stations",   stations.size(),
                "graphNodes", graph.size(),
                "totalEdges", graph.values().stream().mapToInt(Set::size).sum()
        );
    }

    public List<Station> searchStations(String query, int limit) {
        String q = query.toLowerCase();
        return stations.values().stream()
                .filter(s -> s.getName() != null && s.getName().toLowerCase().contains(q))
                .filter(s -> {
                    Set<String> neighbors = graph.get(s.getId());
                    return neighbors != null && !neighbors.isEmpty(); // Must have outgoing connections
                })
                .sorted(Comparator.comparingInt(
                        (Station s) -> graph.getOrDefault(s.getId(), Set.of()).size()).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }
    
    // ========== CACHE MANAGEMENT ==========
    
    private boolean loadFromCache() {
        File cacheFile = new File(gtfsCacheFile).getAbsoluteFile();
        log.info("Looking for GTFS cache at: {}", cacheFile.getAbsolutePath());
        
        if (!cacheFile.exists()) {
            log.info("❌ No GTFS cache file found");
            return false;
        }
        
        try {
            log.info("✓ Cache file exists ({} MB)", cacheFile.length() / 1024 / 1024);
            log.info("  Loading GTFS cache...");
            
            GtfsCacheData data = objectMapper.readValue(cacheFile, GtfsCacheData.class);
            
            this.stations.putAll(data.stations);
            this.graph.putAll(data.graph);
            this.connectionInfo.putAll(data.connectionInfo);
            this.tripStops.putAll(data.tripStops);
            this.stopTrips.putAll(data.stopTrips);
            
            log.info("✓ Successfully loaded GTFS cache");
            log.info("  {} stations, {} graph nodes, {} connections",
                    stations.size(), graph.size(), connectionInfo.size());
            return true;
        } catch (Exception e) {
            log.error("❌ FAILED to load GTFS cache!", e);
            stations.clear();
            graph.clear();
            connectionInfo.clear();
            tripStops.clear();
            stopTrips.clear();
            return false;
        }
    }
    
    private void saveToCache() {
        File cacheFile = new File(gtfsCacheFile).getAbsoluteFile();
        try {
            log.info("Attempting to save GTFS cache...");
            log.info("  Target file: {}", cacheFile.getAbsolutePath());
            
            if (cacheFile.getParentFile() != null) {
                cacheFile.getParentFile().mkdirs();
            }
            
            GtfsCacheData data = new GtfsCacheData();
            data.stations = new HashMap<>(stations);
            data.graph = new HashMap<>(graph);
            data.connectionInfo = new HashMap<>(connectionInfo);
            data.tripStops = new HashMap<>(tripStops);
            data.stopTrips = new HashMap<>(stopTrips);
            data.timestamp = System.currentTimeMillis();
            data.stationCount = stations.size();
            data.graphNodeCount = graph.size();
            
            log.info("  Serializing {} stations, {} connections...", 
                    data.stationCount, connectionInfo.size());
            
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(cacheFile, data);
            
            log.info("✓ GTFS cache saved successfully!");
            log.info("  File: {}", cacheFile.getAbsolutePath());
            log.info("  Size: {} MB", cacheFile.length() / 1024 / 1024);
        } catch (Exception e) {
            log.error("❌ FAILED to save GTFS cache!", e);
            log.error("  Target was: {}", cacheFile.getAbsolutePath());
        }
    }
    
    public void clearCache() {
        File cacheFile = new File(gtfsCacheFile).getAbsoluteFile();
        if (cacheFile.exists()) {
            cacheFile.delete();
            log.info("✓ GTFS cache deleted");
        }
    }
    
    public Map<String, Object> getCacheStats() {
        File cacheFile = new File(gtfsCacheFile).getAbsoluteFile();
        return Map.of(
            "cacheFile", cacheFile.getAbsolutePath(),
            "cacheExists", cacheFile.exists(),
            "cacheSizeMB", cacheFile.exists() ? cacheFile.length() / 1024 / 1024 : 0,
            "dataLoaded", dataLoaded,
            "stationCount", stations.size(),
            "graphNodeCount", graph.size(),
            "connectionCount", connectionInfo.size()
        );
    }
    
    private record GtfsStop(String stopId, String name, double lat, double lon, String parentStation) {}

    private record GtfsStopTime(String stopId, int sequence, int arrivalSeconds, int departureSeconds) {}

    // Cache data structure
    public static class GtfsCacheData {
        public Map<String, Station> stations;
        public Map<String, Set<String>> graph;
        public Map<String, List<Connection>> connectionInfo;
        public Map<String, List<String>> tripStops;
        public Map<String, Set<String>> stopTrips;
        public long timestamp;
        public int stationCount;
        public int graphNodeCount;
    }
}
