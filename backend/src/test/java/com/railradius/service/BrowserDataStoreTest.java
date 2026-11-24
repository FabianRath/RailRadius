package com.railradius.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class BrowserDataStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void publishesTheGzippedFileOfTheServedGraphUnderItsVersion() throws IOException {
        TransitGraph graph = GtfsTestFixtures.load(tempDir.resolve("load"), "alpha", "beta");
        BrowserDataStore store = GtfsTestFixtures.browserDataStore(tempDir, graph);

        store.buildServed();

        BrowserDataStore.Published published = store.current().orElseThrow();
        assertThat(published.version()).matches("[0-9a-f]{16}");
        assertThat(published.file().getFileName().toString()).contains(published.version());
        assertThat(published.sizeBytes()).isEqualTo(Files.size(published.file()));
        Path unzipped = tempDir.resolve("unzipped.bin");
        try (InputStream in = new GZIPInputStream(Files.newInputStream(published.file()))) {
            Files.copy(in, unzipped);
        }
        assertThat(BrowserDataFile.read(unzipped).stations()).hasSize(graph.served().store().stations().size());
    }

    @Test
    void theSameDataGetsTheSameVersion() throws IOException {
        TransitGraph graph = GtfsTestFixtures.load(tempDir.resolve("load"), "alpha", "beta");
        BrowserDataStore store = GtfsTestFixtures.browserDataStore(tempDir, graph);

        store.buildServed();
        String first = store.current().orElseThrow().version();
        store.buildServed();

        assertThat(store.current().orElseThrow().version()).isEqualTo(first);
    }

    @Test
    void publishesNothingOnceAnotherGraphIsServed() throws IOException {
        TransitGraph graph = GtfsTestFixtures.load(tempDir.resolve("load"), "alpha", "beta");
        BrowserDataStore store = GtfsTestFixtures.browserDataStore(tempDir, graph);
        store.buildServed();

        GtfsTestFixtures.dataService(tempDir.resolve("reload"), graph, "zeta").loadFromFiles();

        assertThat(store.current()).isEmpty();
    }

    @Test
    void keepsOnlyTheFileOfTheLatestGraph() throws IOException {
        TransitGraph graph = GtfsTestFixtures.load(tempDir.resolve("load"), "alpha", "beta");
        BrowserDataStore store = GtfsTestFixtures.browserDataStore(tempDir, graph);
        store.buildServed();
        GtfsTestFixtures.dataService(tempDir.resolve("reload"), graph, "zeta").loadFromFiles();

        store.buildServed();

        BrowserDataStore.Published published = store.current().orElseThrow();
        assertThat(filesIn(published.file().getParent())).containsExactly(published.file());
    }

    private static List<Path> filesIn(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.toList();
        }
    }
}
