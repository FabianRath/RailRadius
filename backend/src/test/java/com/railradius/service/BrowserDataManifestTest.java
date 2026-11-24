package com.railradius.service;

import com.railradius.dto.BrowserDataInfo;
import com.railradius.util.ContentVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class BrowserDataManifestTest {

    private static final byte[] ENGINE = {0, 'a', 's', 'm', 1};
    private static final byte[] NEW_ENGINE = {0, 'a', 's', 'm', 2};

    @TempDir
    Path tempDir;

    @Test
    void namesTheDataFileTheEngineAndTheServersToday() throws IOException {
        TransitGraph graph = GtfsTestFixtures.load(tempDir.resolve("load"), "alpha", "beta");
        BrowserDataStore store = GtfsTestFixtures.browserDataStore(tempDir, graph);
        store.buildServed();
        writeEngine(ENGINE, 1);

        BrowserDataInfo info = manifest(store, graph).current().orElseThrow();

        String version = store.current().orElseThrow().version();
        assertThat(info.getVersion()).isEqualTo(version);
        assertThat(info.getPath()).isEqualTo("/api/browser-data/" + version + ".bin");
        assertThat(info.getEngineVersion()).isEqualTo(ContentVersion.of(ENGINE));
        assertThat(info.getToday()).isEqualTo(LocalDate.ofEpochDay(graph.todayEpochDay()).toString());
    }

    @Test
    void aReplacedEngineGetsItsNewVersion() throws IOException {
        TransitGraph graph = GtfsTestFixtures.load(tempDir.resolve("load"), "alpha", "beta");
        BrowserDataStore store = GtfsTestFixtures.browserDataStore(tempDir, graph);
        store.buildServed();
        BrowserDataManifest manifest = manifest(store, graph);
        writeEngine(ENGINE, 1);
        manifest.current();

        writeEngine(NEW_ENGINE, 2);

        assertThat(manifest.current().orElseThrow().getEngineVersion()).isEqualTo(ContentVersion.of(NEW_ENGINE));
    }

    @Test
    void withoutABuiltEngineBrowsersSearchOnTheServer() throws IOException {
        TransitGraph graph = GtfsTestFixtures.load(tempDir.resolve("load"), "alpha", "beta");
        BrowserDataStore store = GtfsTestFixtures.browserDataStore(tempDir, graph);
        store.buildServed();

        assertThat(manifest(store, graph).current()).isEmpty();
    }

    private BrowserDataManifest manifest(BrowserDataStore store, TransitGraph graph) {
        return new BrowserDataManifest(store, graph, new DefaultResourceLoader(),
                tempDir.toUri() + "frontend/");
    }

    private void writeEngine(byte[] content, long modifiedSeconds) throws IOException {
        Path engine = tempDir.resolve("frontend/engine/search.wasm");
        Files.createDirectories(engine.getParent());
        Files.write(engine, content);
        Files.setLastModifiedTime(engine, FileTime.fromMillis(modifiedSeconds * 1000));
    }
}
