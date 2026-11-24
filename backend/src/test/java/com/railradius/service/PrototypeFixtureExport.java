package com.railradius.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Prototype only (#8): writes the binary store of every SearchSnapshotTest feed combination to
 * prototype/wasm/fixtures, so the browser engine can run the gtfs-mini goldens. Run with -Dprototype.export=true.
 */
@EnabledIfSystemProperty(named = "prototype.export", matches = "true")
class PrototypeFixtureExport {

    private static final Path OUT_DIR = Path.of("../prototype/wasm/fixtures");

    @TempDir
    Path tempDir;

    @Test
    void exportFixtureStores() throws IOException {
        Files.createDirectories(OUT_DIR);
        int i = 0;
        for (List<String> feeds : SearchSnapshotTest.feedCombinations()) {
            Path dir = Files.createDirectories(tempDir.resolve("combo" + i++));
            GtfsTestFixtures.load(dir, feeds.toArray(String[]::new));
            Files.copy(dir.resolve("gtfs-store.bin"), OUT_DIR.resolve(String.join("+", feeds) + ".bin"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
