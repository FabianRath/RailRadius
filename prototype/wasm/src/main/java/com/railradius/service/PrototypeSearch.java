package com.railradius.service;

import java.io.IOException;
import java.io.StringWriter;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * Prototype only (#8): what a browser engine does, free of any tool's JS interop, so TeaVM, GraalVM Web Image and the
 * JVM run the same code. One graph at a time.
 */
public final class PrototypeSearch {

    private static TransitGraph graph;
    private static CompactReachabilitySearch search;

    private PrototypeSearch() {
    }

    /** Loads a binary store file; today pins the running days, like SearchSnapshot. Returns load ms and counts. */
    public static String load(byte[] file, String today) throws IOException {
        long startMs = System.currentTimeMillis();
        TransitGraph loaded = TransitGraph.withToday(LocalDate.parse(today));
        BinaryBlocks.Reader in = new BinaryBlocks.Reader(file, BinaryCacheStore.FORMAT_VERSION);
        BinaryCacheStore.BinaryCacheData data = BinaryCacheStore.read(in);
        in.checkAllRead();
        loaded.serve(data);
        graph = loaded;
        search = new CompactReachabilitySearch(loaded);
        return "loaded in " + (System.currentTimeMillis() - startMs) + " ms: " + loaded.stationCount()
                + " stations, " + loaded.timetableStore().tripCount() + " trips, "
                + loaded.timetableStore().eventCount() + " events";
    }

    /** The SearchSnapshot dump of these starts (comma-separated), or of every station if empty. */
    public static String snapshot(String startIdsCsv) throws IOException {
        List<String> startIds = startIdsCsv.isEmpty()
                ? graph.getAllStations().keySet().stream().sorted().toList()
                : Arrays.asList(startIdsCsv.split(","));
        StringWriter out = new StringWriter();
        new SearchSnapshot(graph).write(startIds, out);
        return out.toString();
    }

    /** One search with the API's default window; returns "stations ms". */
    public static String search(String startId, int maxTransfers) {
        long startNanos = System.nanoTime();
        CompactResult result = search.search(startId, maxTransfers, 0, 180, CompactReachabilitySearch.NO_DEADLINE,
                paths -> {});
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        int[] stations = {0};
        result.forEachStation((id, paths) -> stations[0]++);
        return stations[0] + " " + elapsedMs;
    }
}
