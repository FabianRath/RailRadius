package com.railradius.service;

import com.railradius.model.JourneyPath;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a TransitGraph, or the RailNetwork it is built from, from the hand-written feeds in
 * src/test/resources/gtfs-mini. Each feed folder is zipped to gtfs_<source>.zip, where source is the folder name up to
 * the first '-' (so "alpha-fast-train" still loads as source "alpha").
 */
final class GtfsTestFixtures {

    private GtfsTestFixtures() {}

    /** A journey by value: {@link ServiceDays} compares its words by identity. */
    record JourneyValue(JourneyPath path, boolean everyDay, List<Integer> epochDays) {}

    private static final int RAW_CACHE_MAX_ENTRIES = 100;
    private static final int SEARCH_PERMITS = 2;
    private static final int MAX_QUEUED_SEARCHES = 6;
    private static final int TRIP_BATCH_SIZE = 100_000;
    // Made-up countries KA and LA around the kappa and lambda feeds; every other feed lies outside them
    private static final String BORDERS_FILE = "gtfs-mini/borders.geojson";
    // Before every fixture's first service day, so no day counts as past (B31)
    static final LocalDate TODAY = LocalDate.of(2026, 1, 1);
    // Tests that are not about the rate limit search as this client, with a budget no test can use up
    static final SearchRateLimiter.Client VISITOR = new SearchRateLimiter.Client("192.0.2.1", false);
    private static final int UNLIMITED_SEARCH_BUDGET = 1_000_000;
    // railradius.algorithm.max-transfer-time-minutes
    private static final int DEFAULT_MAX_TRANSFER_MINUTES = 180;

    static TransitGraph load(Path tempDir, String... feedFolders) throws IOException {
        return load(tempDir, TRIP_BATCH_SIZE, TODAY, feedFolders);
    }

    /** Like {@link #load(Path, String...)}, with stop_times handed to the loader in batches of this many trips. */
    static TransitGraph load(Path tempDir, int tripBatchSize, String... feedFolders) throws IOException {
        return load(tempDir, tripBatchSize, TODAY, feedFolders);
    }

    /** Like {@link #load(Path, String...)}, with the given day as today. */
    static TransitGraph load(Path tempDir, LocalDate today, String... feedFolders) throws IOException {
        return load(tempDir, TRIP_BATCH_SIZE, today, feedFolders);
    }

    private static TransitGraph load(Path tempDir, int tripBatchSize, LocalDate today, String... feedFolders)
            throws IOException {
        TransitGraph graph = TransitGraph.withToday(today);
        dataService(tempDir, graph, reachabilityCache(graph), new StationSearchIndex(graph), tripBatchSize,
                feedFolders).loadFromFiles();
        return graph;
    }

    /** The feeds loaded and their stations merged, as a load of the app does before it builds the store. */
    static RailNetwork network(Path tempDir, String... feedFolders) throws IOException {
        return network(tempDir, TRIP_BATCH_SIZE, feedFolders);
    }

    /** Like {@link #network(Path, String...)}, with stop_times handed over in batches of this many trips. */
    static RailNetwork network(Path tempDir, int tripBatchSize, String... feedFolders) throws IOException {
        RailNetwork network = new RailNetwork();
        feedLoader(tripBatchSize).loadDirectory(zipFeeds(tempDir, feedFolders).toString(), network,
                new GtfsLoadProgress());
        deduplicator().deduplicate(network);
        return network;
    }

    /** Per station of the result, the journey of each of its paths in order. */
    static Map<String, List<JourneyValue>> journeys(CompactResult result) {
        Map<String, List<JourneyValue>> journeys = new HashMap<>();
        result.forEachStation((stationId, paths) -> journeys.put(stationId, paths.stream()
                .map(path -> result.journey(stationId, path.transfers()))
                .map(journey -> new JourneyValue(journey.path(), journey.runningDays() == ServiceDays.EVERY_DAY,
                        journey.runningDays().epochDays().boxed().toList()))
                .toList()));
        return journeys;
    }

    /** A GtfsDataService that loads the given feeds into the graph; nothing is loaded yet. */
    static GtfsDataService dataService(Path tempDir, TransitGraph graph, ReachabilityCache reachabilityCache,
                                       StationSearchIndex stationSearchIndex, String... feedFolders)
            throws IOException {
        return dataService(tempDir, graph, reachabilityCache, stationSearchIndex, TRIP_BATCH_SIZE, feedFolders);
    }

    /** Like {@link #dataService(Path, TransitGraph, ReachabilityCache, StationSearchIndex, String...)}. */
    static GtfsDataService dataService(Path tempDir, TransitGraph graph, String... feedFolders) throws IOException {
        return dataService(tempDir, graph, reachabilityCache(graph), new StationSearchIndex(graph), feedFolders);
    }

    private static GtfsDataService dataService(Path tempDir, TransitGraph graph, ReachabilityCache reachabilityCache,
                                               StationSearchIndex stationSearchIndex, int tripBatchSize,
                                               String... feedFolders) throws IOException {
        Path gtfsDir = zipFeeds(tempDir, feedFolders);
        GtfsDataService service = new GtfsDataService(graph, feedLoader(tripBatchSize), deduplicator(),
                binaryCacheStore(tempDir), reachabilityCache, stationSearchIndex, new GtfsLoadProgress());
        ReflectionTestUtils.setField(service, "gtfsDirectory", gtfsDir.toString());
        return service;
    }

    /** Zips the feed folders into tempDir/gtfs and returns that directory. */
    private static Path zipFeeds(Path tempDir, String... feedFolders) throws IOException {
        Path gtfsDir = Files.createDirectories(tempDir.resolve("gtfs"));
        for (String folder : feedFolders) {
            String source = folder.split("-")[0];
            zipFeed(folder, gtfsDir.resolve("gtfs_" + source + ".zip"));
        }
        return gtfsDir;
    }

    private static GtfsFeedLoader feedLoader(int tripBatchSize) {
        GtfsFeedLoader feedLoader = new GtfsFeedLoader();
        ReflectionTestUtils.setField(feedLoader, "tripBatchSize", tripBatchSize);
        return feedLoader;
    }

    private static StationDeduplicator deduplicator() throws IOException {
        return new StationDeduplicator(new CountryLocator(Files.newInputStream(resource(BORDERS_FILE))));
    }

    /** The binary cache store that a {@link #dataService} in tempDir writes to. */
    static BinaryCacheStore binaryCacheStore(Path tempDir) {
        return new BinaryCacheStore(tempDir.resolve("gtfs-store.bin").toString());
    }

    /** A ReachabilityService wired like in the app, without warming. */
    static ReachabilityService reachabilityService(TransitGraph graph) {
        return reachabilityService(graph, reachabilityCache(graph));
    }

    static ReachabilityService reachabilityService(TransitGraph graph, ReachabilityCache cache) {
        return reachabilityService(graph, cache, new SearchRateLimiter(UNLIMITED_SEARCH_BUDGET, 1));
    }

    static ReachabilityService reachabilityService(TransitGraph graph, ReachabilityCache cache,
                                                   SearchRateLimiter rateLimiter) {
        return new ReachabilityService(cache, new ReachabilityWarmer(cache), new JourneyLegBuilder(graph), rateLimiter,
                new SearchRequestValidator(graph, DEFAULT_MAX_TRANSFER_MINUTES));
    }

    static ReachabilityCache reachabilityCache(TransitGraph graph) {
        return reachabilityCache(graph, newGate(), RAW_CACHE_MAX_ENTRIES);
    }

    static ReachabilityCache reachabilityCache(TransitGraph graph, int rawMaxEntries) {
        return reachabilityCache(graph, newGate(), rawMaxEntries);
    }

    static ReachabilityCache reachabilityCache(TransitGraph graph, SearchGate gate) {
        return reachabilityCache(graph, gate, RAW_CACHE_MAX_ENTRIES);
    }

    private static SearchGate newGate() {
        return new SearchGate(SEARCH_PERMITS, MAX_QUEUED_SEARCHES);
    }

    private static ReachabilityCache reachabilityCache(TransitGraph graph, SearchGate gate, int rawMaxEntries) {
        return new ReachabilityCache(new CompactReachabilitySearch(graph), gate, rawMaxEntries);
    }

    /** Zips the gtfs-mini feed folder to {@code zipFile}. */
    static void zipFeed(String feedFolder, Path zipFile) throws IOException {
        zipFolder(resource("gtfs-mini/" + feedFolder), zipFile);
    }

    /** Like {@link #zipFeed}, with every file inside {@code entryFolder} instead of at the top level (B35). */
    static void zipFeedInFolder(String feedFolder, Path zipFile, String entryFolder) throws IOException {
        zipFolder(resource("gtfs-mini/" + feedFolder), zipFile, entryFolder + "/");
    }

    static Path resource(String name) {
        try {
            return Path.of(GtfsTestFixtures.class.getClassLoader().getResource(name).toURI());
        } catch (URISyntaxException | NullPointerException e) {
            throw new IllegalStateException("Test resource not found: " + name, e);
        }
    }

    static void zipFolder(Path folder, Path zipFile) throws IOException {
        zipFolder(folder, zipFile, "");
    }

    private static void zipFolder(Path folder, Path zipFile, String entryPrefix) throws IOException {
        try (OutputStream out = Files.newOutputStream(zipFile);
             ZipOutputStream zip = new ZipOutputStream(out);
             Stream<Path> files = Files.list(folder)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".txt"))::iterator) {
                zip.putNextEntry(new ZipEntry(entryPrefix + file.getFileName()));
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
    }
}
