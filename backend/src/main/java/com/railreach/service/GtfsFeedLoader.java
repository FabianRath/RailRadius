package com.railreach.service;

import com.railreach.model.Connection;
import com.railreach.model.Station;
import com.railreach.util.Coordinates;
import com.railreach.util.GtfsTime;
import com.railreach.util.StopIds;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import static com.railreach.service.GtfsCsvReader.doubleField;
import static com.railreach.service.GtfsCsvReader.field;
import static com.railreach.service.GtfsCsvReader.intField;

/** Parses the rail part of every {@code gtfs_<source>.zip} in a directory into a {@link TransitGraph}. */
@Component
@Slf4j
public class GtfsFeedLoader {

    // Buffered trips are turned into edges in batches, so big feeds don't hold all stop_times in memory
    private static final int TRIP_BATCH_SIZE = 100_000;
    private static final int STOP_TIMES_PROGRESS_LOG_INTERVAL = 500_000;
    // ~150 m: same-name stops of one feed closer than this are one station
    private static final double SAME_NAME_STOP_RADIUS_DEG = 0.0015;
    private static final int GTFS_ROUTE_TYPE_RAIL = 2;
    // Extended GTFS rail types 100–106: railway, high speed, long distance, inter-regional, car transport,
    // sleeper, regional. Trams (0), subways (1), buses (3, 700+) and urban railways (400) are left out.
    private static final int EXTENDED_RAIL_TYPE_MIN = 100;
    private static final int EXTENDED_RAIL_TYPE_MAX = 106;

    // Some feeds split one platform into separate arrival and departure stops
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

    private record GtfsStop(String stopId, String name, double lat, double lon, String parentStation) {}

    private record GtfsStopTime(String stopId, int sequence, int arrivalSeconds, int departureSeconds) {}

    /** The per-feed lookup tables that stop_times rows are resolved against. Route names hold rail routes only. */
    private record FeedTables(String source, Map<String, String> routeNames, Map<String, String> tripToRoute,
                              Map<String, String> rawToCanonicalStop) {}

    void loadDirectory(String directory, TransitGraph graph) {
        File dir = new File(directory);
        if (!dir.isDirectory()) {
            throw new IllegalStateException("GTFS directory not found: " + directory);
        }
        File[] zipFiles = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".zip"));
        if (zipFiles == null || zipFiles.length == 0) {
            throw new IllegalStateException("No .zip files found in: " + directory);
        }
        Arrays.sort(zipFiles, Comparator.comparing(File::getName));
        log.info("Found {} GTFS file(s):", zipFiles.length);
        for (File f : zipFiles) {
            log.info("  - {}", f.getName());
        }

        for (int i = 0; i < zipFiles.length; i++) {
            File zipFile = zipFiles[i];
            String source = zipFile.getName()
                    .replaceFirst("(?i)\\.zip$", "")
                    .replaceFirst("(?i)^gtfs_", "");
            log.info("--------------------------------------------------------------------------------------");
            log.info("[{}/{}] Loading: {} (source={})", i + 1, zipFiles.length, zipFile.getName(), source);
            try {
                loadFeed(zipFile, source, graph);
            } catch (Exception e) {
                log.error("Failed to load {}", zipFile.getName(), e);
            }
        }
    }

    private void loadFeed(File file, String source, TransitGraph graph) throws IOException {
        try (ZipFile zipFile = new ZipFile(file)) {
            Map<String, String> routeNames = loadRailRoutes(zipFile);
            Map<String, GtfsStop> stops = loadStops(zipFile);
            Map<String, String> tripToRoute = loadTrips(zipFile);
            log.info("  Loaded {} rail routes, {} stops, {} trips",
                    routeNames.size(), stops.size(), tripToRoute.size());
            if (stops.isEmpty()) {
                log.error("  No stops loaded! Check stops.txt format");
                return;
            }

            Map<String, String> rawToCanonical = registerStations(stops, source, graph);
            log.info("  Stop ID mapping: {} raw IDs -> {} canonical IDs",
                    rawToCanonical.size(), rawToCanonical.values().stream().distinct().count());
            if (rawToCanonical.isEmpty()) {
                log.error("  No stop ID mappings created! Cannot build graph");
                return;
            }
            loadStopTimes(zipFile, new FeedTables(source, routeNames, tripToRoute, rawToCanonical), graph);
        }
    }

    /** route ID -> display name, for rail routes only. */
    private Map<String, String> loadRailRoutes(ZipFile zipFile) throws IOException {
        Map<String, String> routeNames = new HashMap<>();
        Map<Integer, Integer> routeTypeCounts = new HashMap<>();
        int totalRoutes = 0;
        try (GtfsCsvReader csv = GtfsCsvReader.open(zipFile, "routes.txt")) {
            int idCol = csv.column("route_id");
            int shortNameCol = csv.column("route_short_name");
            int longNameCol = csv.column("route_long_name");
            int typeCol = csv.column("route_type");
            for (String[] row = csv.next(); row != null; row = csv.next()) {
                totalRoutes++;
                String routeType = field(row, typeCol);
                if (routeType != null) {
                    int type = GtfsCsvReader.parseInt(routeType);
                    routeTypeCounts.merge(type, 1, Integer::sum);
                    if (!isRail(type)) continue;
                }
                String id = field(row, idCol);
                if (id == null) continue;
                String shortName = field(row, shortNameCol);
                String name = shortName != null ? shortName : field(row, longNameCol);
                routeNames.put(id, name != null ? name : "Unknown");
            }
        }
        log.info("  Routes: {} total, {} rail routes selected", totalRoutes, routeNames.size());
        log.info("  Route type distribution: {}", routeTypeCounts);
        return routeNames;
    }

    private static boolean isRail(int routeType) {
        return routeType == GTFS_ROUTE_TYPE_RAIL
                || (routeType >= EXTENDED_RAIL_TYPE_MIN && routeType <= EXTENDED_RAIL_TYPE_MAX);
    }

    private Map<String, GtfsStop> loadStops(ZipFile zipFile) throws IOException {
        Map<String, GtfsStop> stops = new HashMap<>();
        try (GtfsCsvReader csv = GtfsCsvReader.open(zipFile, "stops.txt")) {
            int idCol = csv.column("stop_id");
            int nameCol = csv.column("stop_name");
            int latCol = csv.column("stop_lat");
            int lonCol = csv.column("stop_lon");
            int parentCol = csv.column("parent_station");
            for (String[] row = csv.next(); row != null; row = csv.next()) {
                String stopId = field(row, idCol);
                if (stopId == null) continue;
                stops.put(stopId, new GtfsStop(stopId, field(row, nameCol),
                        doubleField(row, latCol), doubleField(row, lonCol), field(row, parentCol)));
            }
        }
        return stops;
    }

    private Map<String, String> loadTrips(ZipFile zipFile) throws IOException {
        Map<String, String> tripToRoute = new HashMap<>();
        try (GtfsCsvReader csv = GtfsCsvReader.open(zipFile, "trips.txt")) {
            int routeCol = csv.column("route_id");
            int tripCol = csv.column("trip_id");
            for (String[] row = csv.next(); row != null; row = csv.next()) {
                String tripId = field(row, tripCol);
                String routeId = field(row, routeCol);
                if (tripId != null && routeId != null) tripToRoute.put(tripId, routeId);
            }
        }
        return tripToRoute;
    }

    /**
     * Merges same-name stops close to each other, adds one station per merged group to the graph
     * and returns raw stop ID -> canonical stop ID. Stops without coordinates are skipped.
     */
    private Map<String, String> registerStations(Map<String, GtfsStop> stops, String source, TransitGraph graph) {
        Map<String, GtfsStop> validStops = new LinkedHashMap<>();
        for (GtfsStop stop : stops.values()) {
            if (stop.lat() == 0.0 && stop.lon() == 0.0) continue;
            validStops.put(stop.stopId(), stop);
        }
        Map<String, String> rawToRepresentative = mergeSameNameStops(validStops);

        Map<String, String> rawToCanonical = new HashMap<>();
        for (GtfsStop stop : validStops.values()) {
            String representativeId = rawToRepresentative.getOrDefault(stop.stopId(), stop.stopId());
            String canonical = canonicaliseStopId(representativeId, source);
            rawToCanonical.put(stop.stopId(), canonical);
            GtfsStop representative = validStops.getOrDefault(representativeId, stop);
            graph.addStationIfAbsent(Station.builder()
                    .id(canonical)
                    .name(representative.name() != null ? representative.name() : canonical)
                    .latitude(representative.lat())
                    .longitude(representative.lon())
                    .parentStation(representative.parentStation() != null
                            ? canonicaliseStopId(representative.parentStation(), source) : null)
                    .build());
        }
        log.info("  Stops: {} valid, {} unique after dedup, {} skipped (no coords)",
                validStops.size(), rawToRepresentative.values().stream().distinct().count(),
                stops.size() - validStops.size());
        return rawToCanonical;
    }

    /** raw stop ID -> raw ID of the first (by ID) nearby stop with the same name. */
    private Map<String, String> mergeSameNameStops(Map<String, GtfsStop> validStops) {
        Map<String, List<GtfsStop>> byName = new HashMap<>();
        for (GtfsStop stop : validStops.values()) {
            if (stop.name() != null) {
                byName.computeIfAbsent(stop.name().trim(), k -> new ArrayList<>()).add(stop);
            }
        }
        Map<String, String> rawToRepresentative = new HashMap<>();
        for (List<GtfsStop> group : byName.values()) {
            group.sort(Comparator.comparing(GtfsStop::stopId));
            for (int i = 0; i < group.size(); i++) {
                GtfsStop candidate = group.get(i);
                rawToRepresentative.put(candidate.stopId(), candidate.stopId());
                for (int j = 0; j < i; j++) {
                    GtfsStop existing = group.get(j);
                    if (Coordinates.areNear(candidate.lat(), candidate.lon(), existing.lat(), existing.lon(),
                            SAME_NAME_STOP_RADIUS_DEG)) {
                        rawToRepresentative.put(candidate.stopId(), rawToRepresentative.get(existing.stopId()));
                        break;
                    }
                }
            }
        }
        return rawToRepresentative;
    }

    private void loadStopTimes(ZipFile zipFile, FeedTables tables, TransitGraph graph) throws IOException {
        if (zipFile.getEntry("stop_times.txt") == null) {
            log.warn("  No stop_times.txt found");
            return;
        }
        Map<String, List<GtfsStopTime>> stopTimesByTrip = new HashMap<>();
        int tripsProcessed = 0;
        int edgesAdded = 0;
        int railRowsRead = 0;
        int nonRailRowsSkipped = 0;
        try (GtfsCsvReader csv = GtfsCsvReader.open(zipFile, "stop_times.txt")) {
            int tripCol = csv.column("trip_id");
            int arrivalCol = csv.column("arrival_time");
            int departureCol = csv.column("departure_time");
            int stopCol = csv.column("stop_id");
            int sequenceCol = csv.column("stop_sequence");
            for (String[] row = csv.next(); row != null; row = csv.next()) {
                String tripId = field(row, tripCol);
                String stopId = field(row, stopCol);
                if (tripId == null || stopId == null) continue;
                String routeId = tables.tripToRoute().get(tripId);
                if (routeId == null || !tables.routeNames().containsKey(routeId)) {
                    nonRailRowsSkipped++;
                    continue;
                }
                stopTimesByTrip.computeIfAbsent(tripId, k -> new ArrayList<>()).add(new GtfsStopTime(stopId,
                        intField(row, sequenceCol),
                        GtfsTime.parseSeconds(field(row, arrivalCol)),
                        GtfsTime.parseSeconds(field(row, departureCol))));

                if (++railRowsRead % STOP_TIMES_PROGRESS_LOG_INTERVAL == 0) {
                    log.info("  ... {} rail stop_times read ({} non-rail skipped), {} trips buffered",
                            railRowsRead, nonRailRowsSkipped, stopTimesByTrip.size());
                }
                if (stopTimesByTrip.size() >= TRIP_BATCH_SIZE) {
                    edgesAdded += addTrips(stopTimesByTrip, tables, graph);
                    tripsProcessed += stopTimesByTrip.size();
                    log.info("  ... {} trips processed total", tripsProcessed);
                    stopTimesByTrip.clear();
                }
            }
        }
        edgesAdded += addTrips(stopTimesByTrip, tables, graph);
        tripsProcessed += stopTimesByTrip.size();
        log.info("  Trips: {}, edges: {}", tripsProcessed, edgesAdded);
    }

    /** Adds each buffered trip's stop sequence and edges to the graph. Returns the number of edges added. */
    private int addTrips(Map<String, List<GtfsStopTime>> stopTimesByTrip, FeedTables tables, TransitGraph graph) {
        int edgesAdded = 0;
        int skippedNoMapping = 0;
        int skippedSelfLoop = 0;
        for (Map.Entry<String, List<GtfsStopTime>> tripEntry : stopTimesByTrip.entrySet()) {
            List<GtfsStopTime> stopTimes = tripEntry.getValue();
            if (stopTimes.size() < 2) continue;
            stopTimes.sort(Comparator.comparingInt(GtfsStopTime::sequence));
            String rawTripId = tripEntry.getKey();
            String routeId = tables.tripToRoute().get(rawTripId);
            String tripId = StopIds.of(tables.source(), rawTripId);

            List<String> stopSequence = new ArrayList<>();
            for (GtfsStopTime st : stopTimes) {
                String canonical = tables.rawToCanonicalStop().get(st.stopId());
                if (canonical != null) stopSequence.add(canonical);
            }
            if (stopSequence.size() >= 2) graph.addTrip(tripId, stopSequence);

            for (int i = 0; i < stopTimes.size() - 1; i++) {
                GtfsStopTime from = stopTimes.get(i);
                GtfsStopTime to = stopTimes.get(i + 1);
                String fromCanonical = tables.rawToCanonicalStop().get(from.stopId());
                String toCanonical = tables.rawToCanonicalStop().get(to.stopId());
                if (fromCanonical == null || toCanonical == null) {
                    skippedNoMapping++;
                    continue;
                }
                if (fromCanonical.equals(toCanonical)) {
                    skippedSelfLoop++;
                    continue;
                }
                graph.addConnection(Connection.builder()
                        .tripId(tripId)
                        .routeId(StopIds.of(tables.source(), routeId))
                        .routeName(tables.routeNames().get(routeId))
                        .fromStopId(fromCanonical)
                        .toStopId(toCanonical)
                        .departureTimeSeconds(from.departureSeconds())
                        .arrivalTimeSeconds(to.arrivalSeconds())
                        .stopsApart(1)
                        .build());
                edgesAdded++;
            }
        }
        if (skippedNoMapping > 0 || skippedSelfLoop > 0) {
            log.warn("  Batch: {} edges added, {} skipped (no mapping), {} skipped (self-loop)",
                    edgesAdded, skippedNoMapping, skippedSelfLoop);
        }
        return edgesAdded;
    }

    private static String canonicaliseStopId(String rawId, String source) {
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
}
