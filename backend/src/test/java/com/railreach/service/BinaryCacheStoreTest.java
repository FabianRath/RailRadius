package com.railreach.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BinaryCacheStoreTest {

    // Little-endian, after the magic
    private static final int FORMAT_VERSION_OFFSET = 4;

    @TempDir
    Path tempDir;

    static List<List<String>> feedCombinations() {
        return SearchSnapshotTest.feedCombinations();
    }

    @ParameterizedTest
    @MethodSource("feedCombinations")
    void aGraphRestoredFromTheBinaryCacheSearchesAndAnswersLikeTheParsedOne(List<String> feeds) throws IOException {
        TransitGraph parsed = GtfsTestFixtures.load(tempDir, feeds.toArray(String[]::new));
        BinaryCacheStore store = saved(parsed);

        TransitGraph restored = TransitGraph.withToday(GtfsTestFixtures.TODAY);
        restored.restore(store.load().orElseThrow());

        assertThat(SearchSnapshotTest.dump(restored, SearchEngine.COMPACT))
                .isEqualTo(SearchSnapshotTest.dump(parsed, SearchEngine.COMPACT));
        assertThat(restored.getAllStations()).isEqualTo(parsed.getAllStations());
        assertThat(restored.translatedNames()).isEqualTo(parsed.translatedNames());
        for (String stopId : parsed.getAllStations().keySet()) {
            assertThat(restored.getNeighbors(stopId)).as(stopId).isEqualTo(parsed.getNeighbors(stopId));
        }
        TimetableStore parsedStore = parsed.timetableStore();
        for (int trip = 0; trip < parsedStore.tripCount(); trip++) {
            String tripId = parsedStore.tripId(trip);
            assertThat(restored.getTripInfo(tripId)).as(tripId).isEqualTo(parsed.getTripInfo(tripId));
        }
    }

    @Test
    void aBinaryCacheOfAnotherFormatVersionIsNotRead() throws IOException {
        BinaryCacheStore store = saved(GtfsTestFixtures.load(tempDir, "alpha", "beta"));
        try (RandomAccessFile file = new RandomAccessFile(binaryFile().toFile(), "rw")) {
            file.seek(FORMAT_VERSION_OFFSET);
            file.write(BinaryCacheStore.FORMAT_VERSION + 1);
        }

        assertThat(store.load()).isEmpty();
    }

    @Test
    void aTruncatedBinaryCacheIsNotRead() throws IOException {
        BinaryCacheStore store = saved(GtfsTestFixtures.load(tempDir, "alpha", "beta"));
        try (FileChannel channel = FileChannel.open(binaryFile(), StandardOpenOption.WRITE)) {
            channel.truncate(channel.size() - 1);
        }

        assertThat(store.load()).isEmpty();
    }

    private BinaryCacheStore saved(TransitGraph graph) {
        BinaryCacheStore store = GtfsTestFixtures.binaryCacheStore(tempDir);
        store.save(graph, Path.of(GtfsTestFixtures.cacheStore(tempDir).path()));
        return store;
    }

    private Path binaryFile() {
        return tempDir.resolve("gtfs-store.bin");
    }
}
