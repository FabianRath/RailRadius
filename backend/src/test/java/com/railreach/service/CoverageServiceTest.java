package com.railreach.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CoverageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void coverageListsTheHomeCountryOfEveryFeed() throws IOException {
        TransitGraph graph = GtfsTestFixtures.load(tempDir, "kappa", "lambda");

        // kappa's K4 lies in LA but stays a kappa station; it must not make LA kappa's country
        assertThat(new CoverageService(graph).coverage().getCountries()).containsExactly("KA", "LA");
    }

    @Test
    void emptyGraphCoversNoCountry() {
        assertThat(new CoverageService(new TransitGraph()).coverage().getCountries()).isEmpty();
    }
}
