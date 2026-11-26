package com.railradius.service;

import com.railradius.model.JourneyPath;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
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
    static final SearchAdmission.Client VISITOR = new SearchAdmission.Client("192.0.2.1", false);
    private static final int UNLIMITED_SEARCH_BUDGET = 1_000_000;
    // Short, so tests that wait for a queued or joining search to check its client do not wait long
    private static final Duration WAIT_CHECK = Duration.ofMillis(50);

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
        dataService(tempDir, graph, reachabilityCache(graph), tripBatchSize,
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
    /** The info of the store trip with this ID; the served graph finds infos by trip index only (#35). */
    static Optional<TripInfo> tripInfo(ServedGraph served, String tripId) {
        TimetableStore store = served.store();
        return IntStream.range(0, store.tripCount())
                .filter(trip -> store.tripId(trip).equals(tripId))
                .findFirst()
                .stream()
                .mapToObj(served::tripInfo)
                .flatMap(Optional::stream)
                .findFirst();
    }

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
                                       String... feedFolders) throws IOException {
        return dataService(tempDir, graph, reachabilityCache, TRIP_BATCH_SIZE, feedFolders);
    }

    /** Like {@link #dataService(Path, TransitGraph, ReachabilityCache, String...)}. */
    static GtfsDataService dataService(Path tempDir, TransitGraph graph, String... feedFolders) throws IOException {
        return dataService(tempDir, graph, reachabilityCache(graph), feedFolders);
    }

    /** Like {@link #dataService(Path, TransitGraph, String...)}, reporting its loads to the given progress. */
    static GtfsDataService dataService(Path tempDir, TransitGraph graph, GtfsLoadProgress loadProgress,
                                       String... feedFolders) throws IOException {
        return dataService(tempDir, graph, reachabilityCache(graph), TRIP_BATCH_SIZE, loadProgress, feedFolders);
    }

    private static GtfsDataService dataService(Path tempDir, TransitGraph graph, ReachabilityCache reachabilityCache,
                                               int tripBatchSize, String... feedFolders) throws IOException {
        return dataService(tempDir, graph, reachabilityCache, tripBatchSize, new GtfsLoadProgress(), feedFolders);
    }

    private static GtfsDataService dataService(Path tempDir, TransitGraph graph, ReachabilityCache reachabilityCache,
                                               int tripBatchSize, GtfsLoadProgress loadProgress,
                                               String... feedFolders) throws IOException {
        Path gtfsDir = zipFeeds(tempDir, feedFolders);
        GtfsDataService service = new GtfsDataService(graph, feedLoader(tripBatchSize), deduplicator(),
                binaryCacheStore(tempDir), reachabilityCache, loadProgress, browserDataStore(tempDir, graph));
        ReflectionTestUtils.setField(service, "gtfsDirectory", gtfsDir.toString());
        return service;
    }

    /** Zips the feed folders into tempDir/gtfs and returns that directory. */
    private static Path zipFeeds(Path tempDir, String... feedFolders) throws IOException {
        Path gtfsDir = Files.createDirectories(tempDir.resolve("gtfs"));
        for (String folder : feedFolders) {
            zipFeed(folder, gtfsDir.resolve(zipName(folder)));
        }
        return gtfsDir;
    }

    /** The zip a feed folder loads from: its source is the folder name up to the first '-'. */
    private static String zipName(String feedFolder) {
        return "gtfs_" + feedFolder.split("-")[0] + ".zip";
    }

    /**
     * Like {@link #load(Path, String...)} for one feed, with every file of the folder changed by edit first: for
     * tests that compare a feed with a slightly different version of itself.
     */
    static TransitGraph loadEdited(Path tempDir, String feedFolder, UnaryOperator<String> edit) throws IOException {
        Path edited = Files.createDirectories(tempDir.resolve("edited"));
        try (Stream<Path> files = Files.list(resource("gtfs-mini/" + feedFolder))) {
            for (Path file : files.toList()) {
                Files.writeString(edited.resolve(file.getFileName()), edit.apply(Files.readString(file)));
            }
        }
        zipFolder(edited, Files.createDirectories(tempDir.resolve("gtfs")).resolve(zipName(feedFolder)));
        // No folders: the data service loads the zip already in tempDir/gtfs
        return load(tempDir);
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

    /** The browser data store a {@link #dataService} in tempDir builds into, on the caller's thread (no Spring). */
    static BrowserDataStore browserDataStore(Path tempDir, TransitGraph graph) {
        return new BrowserDataStore(graph, tempDir.resolve("browser-data").toString());
    }

    /** A ReachabilityService wired like in the app, without warming. */
    static ReachabilityService reachabilityService(TransitGraph graph) {
        return reachabilityService(graph, reachabilityCache(graph));
    }

    static ReachabilityService reachabilityService(TransitGraph graph, ReachabilityCache cache) {
        return reachabilityService(graph, cache, admission(graph, cache));
    }

    static ReachabilityService reachabilityService(TransitGraph graph, ReachabilityCache cache,
                                                   SearchRateLimiter rateLimiter) {
        return reachabilityService(graph, cache,
                admission(graph, cache, defaultGate(), rateLimiter, CompactReachabilitySearch.NO_DEADLINE));
    }

    static ReachabilityService reachabilityService(TransitGraph graph, ReachabilityCache cache,
                                                   SearchAdmission admission) {
        return new ReachabilityService(cache, admission, new ReachabilityWarmer(cache, admission),
                new JourneyLegBuilder(), new SearchRequestValidator(graph), graph, new SearchCounts(graph));
    }

    /** A cache that serves only results searched on the graph's served graph now. */
    static ReachabilityCache reachabilityCache(TransitGraph graph) {
        return reachabilityCache(graph, RAW_CACHE_MAX_ENTRIES);
    }

    static ReachabilityCache reachabilityCache(TransitGraph graph, int rawMaxEntries) {
        return new ReachabilityCache(graph, rawMaxEntries);
    }

    /** Admission with the app's gate, no deadline (so a slow test machine never stops a search) and no budget limit. */
    static SearchAdmission admission(TransitGraph graph, ReachabilityCache cache) {
        return admission(graph, cache, defaultGate());
    }

    static SearchAdmission admission(TransitGraph graph, ReachabilityCache cache, SearchGate gate) {
        return admission(graph, cache, gate, unlimitedRateLimiter(), CompactReachabilitySearch.NO_DEADLINE);
    }

    static SearchAdmission admission(TransitGraph graph, ReachabilityCache cache, SearchGate gate,
                                     SearchRateLimiter rateLimiter, Duration deadline) {
        return new SearchAdmission(cache, search(graph), rateLimiter, gate, deadline, WAIT_CHECK);
    }

    static SearchGate gate(int permits, int maxQueued) {
        return new SearchGate(permits, maxQueued, WAIT_CHECK);
    }

    private static SearchGate defaultGate() {
        return gate(SEARCH_PERMITS, MAX_QUEUED_SEARCHES);
    }

    static SearchRateLimiter unlimitedRateLimiter() {
        return new SearchRateLimiter(UNLIMITED_SEARCH_BUDGET, 1);
    }

    static CompactReachabilitySearch search(TransitGraph graph) {
        return new CompactReachabilitySearch(graph);
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
