package com.railradius.service;

import com.railradius.dto.ReachableStationSummary;
import org.teavm.jso.JSExport;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

import java.io.IOException;
import java.io.StringWriter;
import java.time.LocalDate;
import java.util.List;

/**
 * The browser engine (#7): the server's search on the browser's timetable file ({@link BrowserDataFile}), exported to
 * JavaScript by TeaVM. One graph at a time, and the last search's result for its stations' rides.
 */
public final class BrowserEngine {

    private static final JourneyLegBuilder LEG_BUILDER = new JourneyLegBuilder();
    private static final double NANOS_PER_MILLI = 1e6;

    private static TransitGraph graph;
    private static CompactReachabilitySearch search;
    private static CompactResult lastResult;
    private static int lastMaxJourneyMins;
    private static SearchCounters lastCounters = new SearchCounters();

    /** Takes the JSON array of the stations a round shows. */
    @JSFunctor
    public interface RoundStations extends JSObject {
        void accept(String stationsJson);
    }

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
        search = new CompactReachabilitySearch(loaded);
        lastResult = null;
    }

    /**
     * Searches as the server's stream does: after each round, onRound gets the stations it shows for the first time
     * ({@link ShownStations}), as the stream's summaries. Answers how many stations it showed in all.
     *
     * @throws SearchRefusal if the options are out of bounds
     */
    @JSExport
    public static int search(String startId, int maxTransfers, int minTransferMins, int maxTransferMins,
                             int maxJourneyMins, RoundStations onRound) {
        requireGraph();
        SearchOptions options = new SearchOptions(startId, maxTransfers, minTransferMins, maxTransferMins);
        ShownStations shown = new ShownStations(startId, maxJourneyMins, LEG_BUILDER);
        ServedGraph served = graph.served();
        lastResult = null;
        SearchCounters counters = new SearchCounters();
        CompactResult result = search.search(options.startId(), options.maxTransfers(), options.minTransferMins(),
                options.maxTransferMins(), CompactReachabilitySearch.NO_DEADLINE,
                roundPaths -> send(onRound, shown.newInRound(served, roundPaths)), counters);
        lastCounters = counters;
        send(onRound, shown.newInResult(result));
        lastResult = result;
        lastMaxJourneyMins = maxJourneyMins;
        return shown.count();
    }

    /** Estimated milliseconds the last search spent in fallback scans (#39), for the benchmark. */
    @JSExport
    public static double lastFallbackScanMillis() {
        return lastCounters.fallbackScanNanos / NANOS_PER_MILLI;
    }

    /** The rides of the path the last search showed for the station, as JSON, for its journey from the server. */
    @JSExport
    public static String rides(String stationId) {
        if (lastResult == null) throw new IllegalStateException("No search ran");
        CompactResult.PathSummary path = ShownStations.fewestTransfersWithin(lastResult.paths(stationId),
                lastMaxJourneyMins).orElseThrow(() -> new IllegalArgumentException("Not shown: " + stationId));
        return BrowserJson.rides(lastResult.rideList(stationId, path.transfers()).rides());
    }

    /**
     * The search snapshot from every station, rides as store positions (strict) or as stations and times (same
     * result): what the build check compares (#10, #32).
     */
    @JSExport
    public static String snapshot(boolean sameResult) throws IOException {
        requireGraph();
        StringWriter out = new StringWriter();
        new SearchSnapshot(graph, sameResult ? SearchSnapshot.RideFormat.STATIONS_AND_TIMES
                : SearchSnapshot.RideFormat.STORE_POSITIONS).writeEveryStation(out);
        return out.toString();
    }

    private static void requireGraph() {
        if (graph == null) throw new IllegalStateException("No timetable loaded");
    }

    private static void send(RoundStations onRound, List<ReachableStationSummary> shown) {
        if (!shown.isEmpty()) onRound.accept(BrowserJson.summaries(shown));
    }
}
