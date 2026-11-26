package com.railradius.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The search oracle on gtfs-mini (S12): searches from every station of every feed combination the tests load must
 * match the committed recordings in search-snapshots/, in both modes: strict (rides by trip ID) and same result
 * (rides by stations and times, in same-result/; #32). The graph as loaded and the graph read back from the binary
 * store file are both held to them. After an intended change of the results, rewrite them with
 * {@code -Dsnapshot.update=true}, explain every changed line, and commit them with the change.
 */
class SearchSnapshotTest {

    private static final Path RECORDING_DIR = Path.of("src/test/resources/search-snapshots");
    private static final Path SAME_RESULT_DIR = RECORDING_DIR.resolve("same-result");
    private static final boolean UPDATE_RECORDINGS = Boolean.getBoolean("snapshot.update");
    private static final List<SearchSnapshot.RideFormat> MODES =
            List.of(SearchSnapshot.RideFormat.TRIP_IDS, SearchSnapshot.RideFormat.STATIONS_AND_TIMES);
    // theta's QT1 and QT2 both run Q1 08:00 -> Q2 08:30: a ride from Q1 to Q2 is as good on either
    private static final String START_TO_HUB = "theta:Q1 28800 theta:Q2 30600";

    @TempDir
    Path tempDir;

    static List<List<String>> feedCombinations() {
        return List.of(
                List.of("alpha", "beta"),
                List.of("alpha-fast-train", "beta"),
                List.of("epsilon"),
                List.of("eta"),
                List.of("france", "switzerland"),
                List.of("gamma", "delta"),
                List.of("germany"),
                List.of("iota"),
                List.of("kappa", "lambda"),
                List.of("netherlands", "poland"),
                List.of("omega"),
                List.of("theta"),
                List.of("zeta"));
    }

    @ParameterizedTest
    @MethodSource("feedCombinations")
    void searchesFromEveryStationMatchTheRecordings(List<String> feeds) throws IOException {
        TransitGraph loaded = GtfsTestFixtures.load(tempDir, feeds.toArray(String[]::new));

        for (SearchSnapshot.RideFormat mode : MODES) {
            String dump = dump(loaded, mode);
            if (UPDATE_RECORDINGS) {
                Files.createDirectories(recording(feeds, mode).getParent());
                Files.writeString(recording(feeds, mode), dump);
            }
            assertThat(dump.lines()).as(mode.name())
                    .containsExactlyElementsOf(Files.readAllLines(recording(feeds, mode)));
        }
    }

    @ParameterizedTest
    @MethodSource("feedCombinations")
    void searchesOnTheGraphReadBackFromTheStoreFileMatchTheRecordings(List<String> feeds) throws IOException {
        GtfsTestFixtures.load(tempDir, feeds.toArray(String[]::new));
        TransitGraph readBack = TransitGraph.withToday(GtfsTestFixtures.TODAY);

        readBack.serve(GtfsTestFixtures.binaryCacheStore(tempDir).load().orElseThrow());

        for (SearchSnapshot.RideFormat mode : MODES) {
            assertThat(dump(readBack, mode).lines()).as(mode.name())
                    .containsExactlyElementsOf(Files.readAllLines(recording(feeds, mode)));
        }
    }

    @Test
    void sameResultIgnoresWhichOfTwoEquallyGoodTrainsARideUses() throws IOException {
        TransitGraph original = GtfsTestFixtures.load(tempDir.resolve("original"), "theta");
        // Trips are numbered in trip ID order, so QT1 renamed to QT3 now comes after QT2
        TransitGraph renamed = GtfsTestFixtures.loadEdited(tempDir.resolve("renamed"), "theta",
                feed -> feed.replace("QT1", "QT3"));

        assertThat(linesWith(dump(original, SearchSnapshot.RideFormat.TRIP_IDS), START_TO_HUB))
                .allMatch(line -> line.contains("theta:QT1 " + START_TO_HUB));
        assertThat(linesWith(dump(renamed, SearchSnapshot.RideFormat.TRIP_IDS), START_TO_HUB))
                .allMatch(line -> line.contains("theta:QT2 " + START_TO_HUB));
        assertThat(dump(renamed, SearchSnapshot.RideFormat.STATIONS_AND_TIMES))
                .isEqualTo(dump(original, SearchSnapshot.RideFormat.STATIONS_AND_TIMES));
    }

    @ParameterizedTest
    @CsvSource({
            "08:30:00, 08:31:00", // both trains reach the hub a minute later
            "Q2, Q5",             // another station
            "20260329, 20260328"  // one running day less
    })
    void sameResultCatchesAChangeInTimeStationOrRunningDays(String from, String to) throws IOException {
        TransitGraph original = GtfsTestFixtures.load(tempDir.resolve("original"), "theta");

        TransitGraph changed = GtfsTestFixtures.loadEdited(tempDir.resolve("changed"), "theta",
                feed -> feed.replace(from, to));

        assertThat(dump(changed, SearchSnapshot.RideFormat.STATIONS_AND_TIMES))
                .isNotEqualTo(dump(original, SearchSnapshot.RideFormat.STATIONS_AND_TIMES));
    }

    @Test
    void benchStartsAreTheStationsOfOneSet() {
        List<String> lines = List.of("dev austria:1 Wien Hbf", "full germany:8000105 Frankfurt (Main) Hbf",
                "dev hungary:267 Budapest-Keleti");

        assertThat(SearchSnapshot.benchStarts(lines, "dev")).containsExactly("austria:1", "hungary:267");
    }

    /** The committed recording of a feed combination in a mode. */
    static Path recording(List<String> feeds, SearchSnapshot.RideFormat mode) {
        Path dir = mode == SearchSnapshot.RideFormat.STATIONS_AND_TIMES ? SAME_RESULT_DIR : RECORDING_DIR;
        return dir.resolve(String.join("+", feeds) + ".txt");
    }

    static String dump(TransitGraph graph) throws IOException {
        return dump(graph, SearchSnapshot.RideFormat.TRIP_IDS);
    }

    static String dump(TransitGraph graph, SearchSnapshot.RideFormat rideFormat) throws IOException {
        StringWriter out = new StringWriter();
        new SearchSnapshot(graph, rideFormat).writeEveryStation(out);
        return out.toString();
    }

    /** The lines of a dump that contain the text; at least one. */
    private static List<String> linesWith(String dump, String text) {
        List<String> lines = dump.lines().filter(line -> line.contains(text)).toList();
        assertThat(lines).isNotEmpty();
        return lines;
    }
}
