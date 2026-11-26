package com.railradius.service;

import com.railradius.model.Station;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StationSearchIndexTest {

    @TempDir
    static Path tempDir;

    static StationSearchIndex index;

    @BeforeAll
    static void buildIndex() throws Exception {
        index = new StationSearchIndex(GtfsTestFixtures.load(tempDir, "alpha", "beta"));
    }

    @Test
    void searchMatchesAnyPartOfTheNameIgnoringCase() {
        assertThat(ids(index.search("NORTH", 10))).containsExactly("alpha:A2");
        assertThat(ids(index.search("town st", 10))).containsExactly("beta:B1");
    }

    @Test
    void queriesShorterThanATrigramStillMatch() {
        assertThat(ids(index.search("al", 10))).containsExactlyInAnyOrder("alpha:A1", "alpha:A2", "alpha:A4");
    }

    @Test
    void queryWithAnUnknownTrigramFindsNothing() {
        assertThat(index.search("alpha xyz", 10)).isEmpty();
    }

    @Test
    void stationsWithoutOutgoingEdgesAreNotFound() {
        assertThat(index.search("Beta City", 10)).isEmpty();
    }

    @Test
    void searchReturnsAtMostTheLimit() {
        assertThat(index.search("alpha", 2)).hasSize(2);
    }

    @Test
    void limitOutsideTheApiBoundsIsRejected() {
        assertThatThrownBy(() -> index.search("alpha", 0))
                .isInstanceOf(SearchRefusal.class);
        assertThatThrownBy(() -> index.search("alpha", -1))
                .isInstanceOf(SearchRefusal.class);
        assertThatThrownBy(() -> index.search("", StationSearchIndex.MAX_LIMIT + 1))
                .isInstanceOf(SearchRefusal.class);
        assertThat(index.search("alpha", StationSearchIndex.MAX_LIMIT)).isNotEmpty();
    }

    @Test
    void queryLongerThanTheMaximumIsRejected() {
        String longest = "a".repeat(StationSearchIndex.MAX_QUERY_LENGTH);
        assertThat(index.search(longest, 10)).isEmpty();
        assertThatThrownBy(() -> index.search(longest + "a", 10))
                .isInstanceOf(SearchRefusal.class);
    }

    @Test
    void aLoadServesTheIndexOfItsStations(@TempDir Path dir) throws Exception {
        TransitGraph graph = new TransitGraph();
        StationSearchIndex loadedIndex = new StationSearchIndex(graph);

        GtfsTestFixtures.dataService(dir, graph, "alpha", "beta").loadFromFiles();

        assertThat(ids(loadedIndex.search("central", 10))).containsExactly("alpha:A1");
    }

    @Test
    void searchIgnoresAccentsAndFindsGermanSpellingAndTranslatedCityNames(@TempDir Path dir) throws Exception {
        StationSearchIndex omegaIndex = new StationSearchIndex(GtfsTestFixtures.load(dir, "omega"));

        assertThat(ids(omegaIndex.search("koln", 10))).containsExactly("omega:O1");
        assertThat(ids(omegaIndex.search("Koeln Hbf", 10))).containsExactly("omega:O1");
        assertThat(ids(omegaIndex.search("cologne", 10))).containsExactly("omega:O1");
        assertThat(ids(omegaIndex.search("lodz", 10))).containsExactly("omega:O3");
        assertThat(omegaIndex.search("vienna", 10)).isEmpty();
    }

    @Test
    void searchFindsStationsByTheirTranslatedStopNames(@TempDir Path dir) throws Exception {
        StationSearchIndex omegaIndex = new StationSearchIndex(GtfsTestFixtures.load(dir, "omega"));

        assertThat(ids(omegaIndex.search("main station", 10))).containsExactly("omega:O1");
        assertThat(ids(omegaIndex.search("factory", 10))).containsExactly("omega:O3");
        assertThat(omegaIndex.search("riverside", 10)).as("translates another field").isEmpty();
    }

    // #78
    @Test
    void searchFindsAMergedStationByTheNameOfItsAlias(@TempDir Path dir) throws Exception {
        // kappa's K3 "Lambda Gate" is merged into lambda's L1 "Lambdastadt Ost"
        StationSearchIndex mergedIndex = new StationSearchIndex(GtfsTestFixtures.load(dir, "kappa", "lambda"));

        assertThat(ids(mergedIndex.search("lambda gate", 10))).containsExactly("lambda:L1");
    }

    @Test
    void searchFindsAMergedStationByTheTranslatedNameOfItsAlias() {
        assertThat(ids(index.search("grenzstadt", 10))).containsExactly("beta:B1");
    }

    @Test
    void queryDoesNotMatchAcrossTwoNameVariants(@TempDir Path dir) throws Exception {
        StationSearchIndex omegaIndex = new StationSearchIndex(GtfsTestFixtures.load(dir, "omega"));

        assertThat(omegaIndex.search("hbf koeln", 10)).isEmpty();
    }

    private static List<String> ids(List<Station> stations) {
        return stations.stream().map(Station::getId).toList();
    }
}
