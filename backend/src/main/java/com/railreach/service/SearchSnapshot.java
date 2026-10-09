package com.railreach.service;

import com.railreach.model.JourneyPath;
import com.railreach.util.ResourceLines;
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
 * The gtfs-mini dumps are checked by SearchSnapshotTest, on both search engines. For dev and full data, {@link #main}
 * dumps the starts of one set in {@code docs/bench-stations.txt}.
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

    private final TransitGraph graph;
    private final SearchEngine search;

    /** engine is {@link SearchEngine#LEGACY} or {@link SearchEngine#COMPACT}. */
    SearchSnapshot(TransitGraph graph, String engine) {
        this.graph = graph;
        this.search = SearchEngine.named(engine, graph);
    }

    /** Today first, then one section per start and transfer limit 0 to {@link #MAX_TRANSFERS}. */
    void write(List<String> startIds, Writer out) throws IOException {
        out.write("# today " + LocalDate.ofEpochDay(graph.todayEpochDay()) + "\n");
        for (String startId : startIds) {
            for (int maxTransfers = 0; maxTransfers <= MAX_TRANSFERS; maxTransfers++) {
                out.write("## " + startId + " maxTransfers " + maxTransfers + "\n");
                SearchResult result = search.search(startId, maxTransfers, MIN_TRANSFER_MINUTES,
                        MAX_TRANSFER_MINUTES, roundPaths -> {});
                Map<String, List<SearchResult.PathSummary>> pathsByStation = new TreeMap<>();
                result.forEachStation(pathsByStation::put);
                for (Map.Entry<String, List<SearchResult.PathSummary>> station : pathsByStation.entrySet()) {
                    for (SearchResult.PathSummary path : station.getValue()) {
                        out.write(line(station.getKey(), result.journey(station.getKey(), path.transfers())) + "\n");
                    }
                }
            }
        }
    }

    private static String line(String stationId, SearchResult.Journey journey) {
        JourneyPath path = journey.path();
        ServiceDays days = journey.runningDays();
        StringJoiner rides = new StringJoiner(RIDE_SEPARATOR);
        for (JourneyRunningDays.RideTimes ride : JourneyRunningDays.rides(path.getConnections())) {
            rides.add(String.join(" ", ride.tripId(), ride.fromStopId(), String.valueOf(ride.departureSeconds()),
                    ride.toStopId(), String.valueOf(ride.arrivalSeconds())));
        }
        return String.join(FIELD_SEPARATOR, stationId, String.valueOf(path.getTransfers()),
                String.valueOf(path.getTotalDurationMinutes()), String.join(" ", path.getStationIds()),
                rides.toString(), dayRanges(days), JourneyRunningDays.describe(days).orElse(NO_LABEL));
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
     * Args: cache file, bench set, today (yyyy-mm-dd), output file, optionally the search engine (default legacy).
     * Paths are relative to backend/.
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 4 && args.length != 5) {
            throw new IllegalArgumentException(
                    "Usage: SearchSnapshot <cache-file> <bench-set> <today> <out-file> [legacy|compact]");
        }
        String engine = args.length == 5 ? args[4] : SearchEngine.LEGACY;
        long startMs = System.currentTimeMillis();
        TransitGraph graph = TransitGraph.withToday(LocalDate.parse(args[2]));
        graph.restore(new GtfsCacheStore(args[0]).load()
                .orElseThrow(() -> new IllegalStateException("No usable cache file " + args[0])));
        List<String> startIds = benchStarts(ResourceLines.read(BENCH_FILE), args[1]);
        if (startIds.isEmpty()) throw new IllegalArgumentException("No starts in bench set " + args[1]);
        List<String> unknown = startIds.stream().filter(id -> graph.getStation(id) == null).toList();
        if (!unknown.isEmpty()) throw new IllegalArgumentException("Not in the cache file: " + unknown);
        try (Writer out = writer(Path.of(args[3]))) {
            new SearchSnapshot(graph, engine).write(startIds, out);
        }
        log.info("Wrote the {} snapshot of {} starts to {} in {} s", engine, startIds.size(), args[3],
                TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - startMs));
    }
}
