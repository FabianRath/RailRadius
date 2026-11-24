package com.railradius.service;

import com.railradius.model.Connection;
import com.railradius.model.JourneyPath;
import com.railradius.util.ResourceLines;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/**
 * The search oracle (S12): every path the search returns as one line of text, sorted, so two runs, or two versions of
 * the search, can be compared with a plain diff. A line holds what the journey shows: the station, transfers,
 * duration, the path's stations, each ride's trip with departure and arrival seconds, and the exact running days
 * with their label. Running days depend on today (B31), so the dump pins it.
 * <p>
 * The gtfs-mini dumps are checked by SearchSnapshotTest. For dev and full data, {@link #main} dumps the starts of one
 * set in {@code docs/bench-stations.txt} from a binary cache file. The browser engine has no trip IDs, so it writes
 * its rides as store positions ({@link RideFormat#STORE_POSITIONS}), which the build check compares with the JVM's
 * (#10).
 */
@Slf4j
final class SearchSnapshot {

    private static final int MAX_TRANSFERS = 5;
    // The API's defaults for a request without a transfer window
    private static final int MIN_TRANSFER_MINUTES = 0;
    private static final int MAX_TRANSFER_MINUTES = 180;
    // Relative to backend/, where Maven runs the main
    private static final Path BENCH_FILE = Path.of("../docs/bench-stations.txt");
    private static final String GZIP_SUFFIX = ".gz";
    private static final String FIELD_SEPARATOR = "\t";
    private static final String RIDE_SEPARATOR = " | ";
    private static final String EVERY_DAY = "every day";
    private static final String NO_DAYS = "none";
    private static final String NO_LABEL = "-";

    /** How a line names a path's rides. */
    enum RideFormat {
        /** Trip ID, board stop and departure, alight stop and arrival, and the path's stations: the goldens. */
        TRIP_IDS,
        /** Trip, board event and alight event as positions in the store: all a browser's store can say. */
        STORE_POSITIONS
    }

    private final TransitGraph graph;
    private final CompactReachabilitySearch search;
    private final RideFormat rideFormat;

    SearchSnapshot(TransitGraph graph, RideFormat rideFormat) {
        this.graph = graph;
        this.search = new CompactReachabilitySearch(graph);
        this.rideFormat = rideFormat;
    }

    /** Today first, then one section per start and transfer limit 0 to {@link #MAX_TRANSFERS}. */
    void write(List<String> startIds, Writer out) throws IOException {
        out.write("# today " + LocalDate.ofEpochDay(graph.todayEpochDay()) + "\n");
        for (String startId : startIds) {
            for (int maxTransfers = 0; maxTransfers <= MAX_TRANSFERS; maxTransfers++) {
                out.write("## " + startId + " maxTransfers " + maxTransfers + "\n");
                // No deadline: the oracle must answer every search, however slow the machine
                CompactResult result = search.search(startId, maxTransfers, MIN_TRANSFER_MINUTES,
                        MAX_TRANSFER_MINUTES, CompactReachabilitySearch.NO_DEADLINE, roundPaths -> {});
                Map<String, List<CompactResult.PathSummary>> pathsByStation = new TreeMap<>();
                result.forEachStation(pathsByStation::put);
                for (Map.Entry<String, List<CompactResult.PathSummary>> station : pathsByStation.entrySet()) {
                    for (CompactResult.PathSummary path : station.getValue()) {
                        out.write(line(result, station.getKey(), path) + "\n");
                    }
                }
            }
        }
    }

    /** {@link #write} from every station of the graph, by ID. */
    void writeEveryStation(Writer out) throws IOException {
        write(graph.getAllStations().keySet().stream().sorted().toList(), out);
    }

    private String line(CompactResult result, String stationId, CompactResult.PathSummary summary) {
        if (rideFormat == RideFormat.STORE_POSITIONS) {
            CompactResult.RideList rideList = result.rideList(stationId, summary.transfers());
            return String.join(FIELD_SEPARATOR, stationId, String.valueOf(summary.transfers()),
                    String.valueOf(summary.durationMinutes()), positions(rideList.rides()),
                    days(rideList.runningDays()));
        }
        CompactResult.Journey journey = result.journey(stationId, summary.transfers());
        JourneyPath path = journey.path();
        return String.join(FIELD_SEPARATOR, stationId, String.valueOf(path.getTransfers()),
                String.valueOf(path.getTotalDurationMinutes()), String.join(" ", path.getStationIds()),
                rides(path.getConnections()), days(journey.runningDays()));
    }

    /** Each ride as trip, board event and alight event. */
    private static String positions(List<CompactResult.Ride> rides) {
        StringJoiner positions = new StringJoiner(RIDE_SEPARATOR);
        for (CompactResult.Ride ride : rides) {
            positions.add(ride.trip() + " " + ride.boardEvent() + " " + ride.alightEvent());
        }
        return positions.toString();
    }

    /** The day ranges and their label. */
    private static String days(ServiceDays days) {
        return dayRanges(days) + FIELD_SEPARATOR + JourneyRunningDays.describe(days).orElse(NO_LABEL);
    }

    /** Each ride as trip, board stop and departure, alight stop and arrival: consecutive connections on one trip. */
    private static String rides(List<Connection> connections) {
        StringJoiner rides = new StringJoiner(RIDE_SEPARATOR);
        int rideStart = 0;
        while (rideStart < connections.size()) {
            Connection board = connections.get(rideStart);
            int rideEnd = rideStart;
            while (rideEnd + 1 < connections.size()
                    && connections.get(rideEnd + 1).getTripId().equals(board.getTripId())) {
                rideEnd++;
            }
            Connection alight = connections.get(rideEnd);
            rides.add(String.join(" ", board.getTripId(), board.getFromStopId(),
                    String.valueOf(board.getDepartureTimeSeconds()), alight.getToStopId(),
                    String.valueOf(alight.getArrivalTimeSeconds())));
            rideStart = rideEnd + 1;
        }
        return rides.toString();
    }

    /** The epoch days as ranges, such as "20458-20470,20475". */
    private static String dayRanges(ServiceDays days) {
        if (days == ServiceDays.EVERY_DAY) return EVERY_DAY;
        int[] epochDays = days.epochDays().toArray();
        if (epochDays.length == 0) return NO_DAYS;
        StringJoiner ranges = new StringJoiner(",");
        int rangeStart = 0;
        for (int i = 1; i <= epochDays.length; i++) {
            if (i < epochDays.length && epochDays[i] == epochDays[i - 1] + 1) continue;
            ranges.add(rangeStart == i - 1 ? String.valueOf(epochDays[rangeStart])
                    : epochDays[rangeStart] + "-" + epochDays[i - 1]);
            rangeStart = i;
        }
        return ranges.toString();
    }

    /** Lines {@code <set> <stationId> <name>}: the start IDs of one set. */
    static List<String> benchStarts(List<String> benchLines, String set) {
        return benchLines.stream()
                .map(line -> line.split("\\s+", 3))
                .filter(columns -> columns[0].equals(set))
                .map(columns -> columns[1])
                .toList();
    }

    /** Gzipped if the name ends in .gz: a full-data dump is several GB as text. */
    private static Writer writer(Path file) throws IOException {
        OutputStream out = Files.newOutputStream(file);
        if (file.toString().endsWith(GZIP_SUFFIX)) out = new GZIPOutputStream(out);
        return new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
    }

    /**
     * Args: binary cache file, bench set, today (yyyy-mm-dd), output file. Paths are relative to backend/.
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            throw new IllegalArgumentException("Usage: SearchSnapshot <store-file> <bench-set> <today> <out-file>");
        }
        long startMs = System.currentTimeMillis();
        TransitGraph graph = TransitGraph.withToday(LocalDate.parse(args[2]));
        graph.serve(new BinaryCacheStore(args[0]).load()
                .orElseThrow(() -> new IllegalStateException("No usable binary cache file " + args[0])));
        List<String> startIds = benchStarts(ResourceLines.read(BENCH_FILE), args[1]);
        if (startIds.isEmpty()) throw new IllegalArgumentException("No starts in bench set " + args[1]);
        List<String> unknown = startIds.stream().filter(id -> graph.getStation(id) == null).toList();
        if (!unknown.isEmpty()) throw new IllegalArgumentException("Not in the cache file: " + unknown);
        try (Writer out = writer(Path.of(args[3]))) {
            new SearchSnapshot(graph, RideFormat.TRIP_IDS).write(startIds, out);
        }
        log.info("Wrote the snapshot of {} starts to {} in {} s", startIds.size(), args[3],
                TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - startMs));
    }
}
