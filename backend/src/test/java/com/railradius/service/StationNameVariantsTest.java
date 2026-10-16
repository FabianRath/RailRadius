package com.railradius.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StationNameVariantsTest {

    private final StationNameVariants variants = new StationNameVariants();

    @Test
    void foldRemovesAccentsCaseAndExtraSpaces() {
        assertThat(StationNameVariants.fold("  Köln   Hbf ")).isEqualTo("koln hbf");
        assertThat(StationNameVariants.fold("Malmö Central")).isEqualTo("malmo central");
    }

    @Test
    void foldMapsLettersThatHaveNoSeparateAccent() {
        assertThat(StationNameVariants.fold("Łódź Fabryczna")).isEqualTo("lodz fabryczna");
        assertThat(StationNameVariants.fold("Ørestad")).isEqualTo("orestad");
        assertThat(StationNameVariants.fold("Hauptstraße")).isEqualTo("hauptstrasse");
    }

    @Test
    void foldedNameComesFirst() {
        assertThat(variants.variants("Köln Hbf")).first().isEqualTo("koln hbf");
    }

    @Test
    void addsGermanSpellingOfUmlauts() {
        assertThat(variants.variants("Köln Hbf")).contains("koeln hbf");
    }

    @Test
    void replacesTheLocalCityNameWithEachTranslation() {
        assertThat(variants.variants("Köln Hbf")).contains("cologne hbf");
        assertThat(variants.variants("Milano Centrale")).contains("milan centrale", "mailand centrale");
        assertThat(variants.variants("Flughafen Wien")).contains("flughafen vienna");
    }

    @Test
    void doesNotTranslateTheCityNameInsideALongerWord() {
        assertThat(variants.variants("Wiener Neustadt Hbf")).containsExactly("wiener neustadt hbf");
    }

    @Test
    void nameWithoutAccentsOrKnownCityHasOneVariant() {
        assertThat(variants.variants("Alpha Central")).containsExactly("alpha central");
    }
}
