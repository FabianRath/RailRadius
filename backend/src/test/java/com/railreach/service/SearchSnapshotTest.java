package com.railreach.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The search oracle on gtfs-mini (S12): searches from every station of every feed combination the tests load must
 * match the committed dumps in search-snapshots/, on both search engines. After an intended change of the results,
 * rewrite them with {@code -Dsnapshot.update=true} (from the legacy engine), explain every changed line, and commit
 * them with the change.
 */
class SearchSnapshotTest {

    private static final Path GOLDEN_DIR = Path.of("src/test/resources/search-snapshots");
    private static final boolean UPDATE_GOLDENS = Boolean.getBoolean("snapshot.update");

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
                List.of("zeta"));
    }

    static Stream<Arguments> feedCombinationsOnEachEngine() {
        return feedCombinations().stream().flatMap(feeds -> Stream.of(
                Arguments.of(feeds, SearchEngine.LEGACY), Arguments.of(feeds, SearchEngine.COMPACT)));
    }

    @ParameterizedTest
    @MethodSource("feedCombinationsOnEachEngine")
    void searchesFromEveryStationMatchTheGoldenDump(List<String> feeds, String engine) throws IOException {
        String dump = dump(GtfsTestFixtures.load(tempDir, feeds.toArray(String[]::new)), engine);
        Path golden = GOLDEN_DIR.resolve(String.join("+", feeds) + ".txt");
        if (UPDATE_GOLDENS) {
            if (engine.equals(SearchEngine.COMPACT)) return;
            Files.writeString(golden, dump);
            return;
        }
        assertThat(dump.lines()).containsExactlyElementsOf(Files.readAllLines(golden));
    }

    @Test
    void benchStartsAreTheStationsOfOneSet() {
        List<String> lines = List.of("dev austria:1 Wien Hbf", "full germany:8000105 Frankfurt (Main) Hbf",
                "dev hungary:267 Budapest-Keleti");

        assertThat(SearchSnapshot.benchStarts(lines, "dev")).containsExactly("austria:1", "hungary:267");
    }

    private static String dump(TransitGraph graph, String engine) throws IOException {
        StringWriter out = new StringWriter();
        new SearchSnapshot(graph, engine).write(graph.getAllStations().keySet().stream().sorted().toList(), out);
        return out.toString();
    }
}
