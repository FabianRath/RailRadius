package com.railradius.service;

import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Int8Array;

import java.io.IOException;
import java.io.StringWriter;
import java.time.LocalDate;

/**
 * The browser engine (#7): the server's search on the browser's timetable file ({@link BrowserDataFile}), exported to
 * JavaScript by TeaVM. One graph at a time.
 */
public final class BrowserEngine {

    private static TransitGraph graph;

    private BrowserEngine() {
    }

    /** TeaVM compiles from a main class; the exports are the engine's API. */
    public static void main(String[] args) {
    }

    /** Loads an unzipped browser timetable file; today (yyyy-mm-dd) says which days are past. */
    @JSExport
    public static void load(Int8Array file, String today) throws IOException {
        TransitGraph loaded = TransitGraph.withToday(LocalDate.parse(today));
        BinaryBlocks.Reader in = new BinaryBlocks.Reader(file.copyToJavaArray(), BrowserDataFile.FORMAT_VERSION);
        loaded.serve(BrowserDataFile.read(in));
        graph = loaded;
    }

    /** The search snapshot from every station, rides as store positions: what the build check compares (#10). */
    @JSExport
    public static String snapshot() throws IOException {
        if (graph == null) throw new IllegalStateException("No timetable loaded");
        StringWriter out = new StringWriter();
        new SearchSnapshot(graph, SearchSnapshot.RideFormat.STORE_POSITIONS).writeEveryStation(out);
        return out.toString();
    }
}
