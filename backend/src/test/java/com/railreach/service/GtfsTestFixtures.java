package com.railreach.service;

import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a GtfsDataService from the hand-written feeds in src/test/resources/gtfs-mini.
 * Each feed folder is zipped to gtfs_<source>.zip, where source is the folder name up to
 * the first '-' (so "alpha-fast-train" still loads as source "alpha").
 */
final class GtfsTestFixtures {

    private GtfsTestFixtures() {}

    static GtfsDataService load(Path tempDir, String... feedFolders) throws IOException {
        Path gtfsDir = Files.createDirectories(tempDir.resolve("gtfs"));
        for (String folder : feedFolders) {
            String source = folder.split("-")[0];
            zipFolder(resource("gtfs-mini/" + folder), gtfsDir.resolve("gtfs_" + source + ".zip"));
        }

        GtfsDataService service = new GtfsDataService();
        ReflectionTestUtils.setField(service, "gtfsDirectory", gtfsDir.toString());
        ReflectionTestUtils.setField(service, "gtfsCacheFile", tempDir.resolve("gtfs-cache.json").toString());
        service.loadFromFiles();
        return service;
    }

    private static Path resource(String name) {
        try {
            return Path.of(GtfsTestFixtures.class.getClassLoader().getResource(name).toURI());
        } catch (URISyntaxException | NullPointerException e) {
            throw new IllegalStateException("Test resource not found: " + name, e);
        }
    }

    private static void zipFolder(Path folder, Path zipFile) throws IOException {
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
