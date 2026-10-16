package com.railradius.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class BinaryCacheStoreTest {

    // Little-endian, after the magic
    private static final int FORMAT_VERSION_OFFSET = 4;
    // zeta's services run from 2026-01-05 to 2026-03-29
    private static final LocalDate ZETA_FIRST_DAY = LocalDate.of(2026, 1, 5);
    private static final LocalDate ZETA_LAST_DAY = LocalDate.of(2026, 3, 29);

    @TempDir
    Path tempDir;

    static List<List<String>> feedCombinations() {
        return SearchSnapshotTest.feedCombinations();
    }

    @ParameterizedTest
    @MethodSource("feedCombinations")
    void aGraphRestoredFromTheBinaryCacheSearchesAndAnswersLikeTheParsedOne(List<String> feeds) throws IOException {
        TransitGraph parsed = GtfsTestFixtures.load(tempDir, feeds.toArray(String[]::new));

        TransitGraph restored = TransitGraph.withToday(GtfsTestFixtures.TODAY);
        restored.restore(store().load().orElseThrow());

        assertThat(SearchSnapshotTest.dump(restored)).isEqualTo(SearchSnapshotTest.dump(parsed));
        assertThat(restored.getAllStations()).isEqualTo(parsed.getAllStations());
        for (String stopId : parsed.getAllStations().keySet()) {
            assertThat(restored.getTranslatedNames(stopId)).as(stopId).isEqualTo(parsed.getTranslatedNames(stopId));
            assertThat(restored.nextStationCount(stopId)).as(stopId).isEqualTo(parsed.nextStationCount(stopId));
        }
        TimetableStore parsedStore = parsed.timetableStore();
        for (int trip = 0; trip < parsedStore.tripCount(); trip++) {
            String tripId = parsedStore.tripId(trip);
            assertThat(restored.getTripInfo(tripId)).as(tripId).isEqualTo(parsed.getTripInfo(tripId));
        }
    }

    @Test
    void howLongEachFeedRunsIsStoredWithTheServedData() throws IOException {
        TransitGraph parsed = GtfsTestFixtures.load(tempDir, "alpha", "zeta");

        TransitGraph restored = TransitGraph.withToday(GtfsTestFixtures.TODAY);
        restored.restore(store().load().orElseThrow());

        assertThat(restored.feedTimespans()).isEqualTo(parsed.feedTimespans());
        // alpha has no calendar, so its trains run every day and never end
        assertThat(restored.feedTimespans()).extracting(FeedTimespan::source, FeedTimespan::validity)
                .containsExactly(tuple("zeta", new FeedValidity(ZETA_FIRST_DAY, ZETA_LAST_DAY)));
    }

    @Test
    void aBinaryCacheOfAnotherFormatVersionIsNotRead() throws IOException {
        GtfsTestFixtures.load(tempDir, "alpha", "beta");
        try (RandomAccessFile file = new RandomAccessFile(binaryFile().toFile(), "rw")) {
            file.seek(FORMAT_VERSION_OFFSET);
            file.write(BinaryCacheStore.FORMAT_VERSION + 1);
        }

        assertThat(store().load()).isEmpty();
    }

    @Test
    void aTruncatedBinaryCacheIsNotRead() throws IOException {
        GtfsTestFixtures.load(tempDir, "alpha", "beta");
        try (FileChannel channel = FileChannel.open(binaryFile(), StandardOpenOption.WRITE)) {
            channel.truncate(channel.size() - 1);
        }

        assertThat(store().load()).isEmpty();
    }

    /** The binary cache that loading the feeds wrote. */
    private BinaryCacheStore store() {
        return GtfsTestFixtures.binaryCacheStore(tempDir);
    }

    private Path binaryFile() {
        return tempDir.resolve("gtfs-store.bin");
    }
}
