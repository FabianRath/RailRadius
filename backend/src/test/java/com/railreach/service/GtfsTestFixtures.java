package com.railreach.service;

import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a TransitGraph from the hand-written feeds in src/test/resources/gtfs-mini.
 * Each feed folder is zipped to gtfs_<source>.zip, where source is the folder name up to
 * the first '-' (so "alpha-fast-train" still loads as source "alpha").
 */
final class GtfsTestFixtures {

    private GtfsTestFixtures() {}

    private static final int RAW_CACHE_MAX_ENTRIES = 100;
    private static final int TRIP_BATCH_SIZE = 100_000;
    // Made-up countries KA and LA around the kappa and lambda feeds; every other feed lies outside them
    private static final String BORDERS_FILE = "gtfs-mini/borders.geojson";
    // Before every fixture's first service day, so no day counts as past (B31)
    private static final LocalDate TODAY = LocalDate.of(2026, 1, 1);

    static TransitGraph load(Path tempDir, String... feedFolders) throws IOException {
        return load(tempDir, TRIP_BATCH_SIZE, TODAY, feedFolders);
    }

    /** Like {@link #load(Path, String...)}, with stop_times handed to the graph in batches of this many trips. */
    static TransitGraph load(Path tempDir, int tripBatchSize, String... feedFolders) throws IOException {
        return load(tempDir, tripBatchSize, TODAY, feedFolders);
    }

    /** Like {@link #load(Path, String...)}, with the given day as today. */
    static TransitGraph load(Path tempDir, LocalDate today, String... feedFolders) throws IOException {
        return load(tempDir, TRIP_BATCH_SIZE, today, feedFolders);
    }

    private static TransitGraph load(Path tempDir, int tripBatchSize, LocalDate today, String... feedFolders)
            throws IOException {
        TransitGraph graph = new TransitGraph(Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(),
                ZoneOffset.UTC));
        dataService(tempDir, graph, reachabilityCache(graph), new StationSearchIndex(graph), tripBatchSize,
                feedFolders).loadFromFiles();
        return graph;
    }

    /** A GtfsDataService that loads the given feeds into the graph; nothing is loaded yet. */
    static GtfsDataService dataService(Path tempDir, TransitGraph graph, ReachabilityCache reachabilityCache,
                                       StationSearchIndex stationSearchIndex, String... feedFolders)
            throws IOException {
        return dataService(tempDir, graph, reachabilityCache, stationSearchIndex, TRIP_BATCH_SIZE, feedFolders);
    }

    private static GtfsDataService dataService(Path tempDir, TransitGraph graph, ReachabilityCache reachabilityCache,
                                               StationSearchIndex stationSearchIndex, int tripBatchSize,
                                               String... feedFolders) throws IOException {
        Path gtfsDir = Files.createDirectories(tempDir.resolve("gtfs"));
        for (String folder : feedFolders) {
            String source = folder.split("-")[0];
            zipFeed(folder, gtfsDir.resolve("gtfs_" + source + ".zip"));
        }

        GtfsFeedLoader feedLoader = new GtfsFeedLoader();
        ReflectionTestUtils.setField(feedLoader, "tripBatchSize", tripBatchSize);
        GtfsDataService service = new GtfsDataService(graph, feedLoader,
                new StationDeduplicator(new CountryLocator(Files.newInputStream(resource(BORDERS_FILE)))),
                cacheStore(tempDir), reachabilityCache, stationSearchIndex, new GtfsLoadProgress());
        ReflectionTestUtils.setField(service, "gtfsDirectory", gtfsDir.toString());
        return service;
    }

    /** The cache store that {@link #load} writes to. */
    static GtfsCacheStore cacheStore(Path tempDir) {
        GtfsCacheStore cacheStore = new GtfsCacheStore();
        ReflectionTestUtils.setField(cacheStore, "cacheFile", tempDir.resolve("gtfs-cache.json").toString());
        return cacheStore;
    }

    /** A ReachabilityService wired like in the app, without warming or a Caffeine cache. */
    static ReachabilityService reachabilityService(TransitGraph graph) {
        ReachabilityCache cache = reachabilityCache(graph);
        ReachabilityService service = new ReachabilityService(graph, cache, new ReachabilityWarmer(cache),
                new JourneyLegBuilder(graph));
        ReflectionTestUtils.setField(service, "maxTransferTimeMinutes", 180);
        return service;
    }

    static ReachabilityCache reachabilityCache(TransitGraph graph) {
        return reachabilityCache(graph, RAW_CACHE_MAX_ENTRIES);
    }

    static ReachabilityCache reachabilityCache(TransitGraph graph, int rawMaxEntries) {
        return new ReachabilityCache(new ReachabilitySearch(graph), new ConcurrentMapCacheManager(), rawMaxEntries);
    }

    /** Zips the gtfs-mini feed folder to {@code zipFile}. */
    static void zipFeed(String feedFolder, Path zipFile) throws IOException {
        zipFolder(resource("gtfs-mini/" + feedFolder), zipFile);
    }

    static Path resource(String name) {
        try {
            return Path.of(GtfsTestFixtures.class.getClassLoader().getResource(name).toURI());
        } catch (URISyntaxException | NullPointerException e) {
            throw new IllegalStateException("Test resource not found: " + name, e);
        }
    }

    static void zipFolder(Path folder, Path zipFile) throws IOException {
        try (OutputStream out = Files.newOutputStream(zipFile);
             ZipOutputStream zip = new ZipOutputStream(out);
             Stream<Path> files = Files.list(folder)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".txt"))::iterator) {
                zip.putNextEntry(new ZipEntry(file.getFileName().toString()));
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
    }
}
