package com.railreach.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Names of stations that the full data had duplicated within 100 m (B29), and of nearby separate stations. */
class StationNameMatcherTest {

    private static final double MIN_SIMILARITY = 0.4;

    private final StationNameMatcher matcher = new StationNameMatcher();

    @Test
    void cyrillicNamesMatchTheirLatinSpelling() {
        assertSimilar("Горна Оряховица", "GORNA ORYAHOVITSA");
        assertSimilar("Велико Търново", "VELIKO TARNOVO");
        assertSimilar("Стара Загора", "STARA ZAGORA");
        assertSimilar("Пазарджик", "PAZARDJIK");
    }

    @Test
    void bulgarianCentralStationMatchesTheCityName() {
        assertSimilar("Централна гара Пловдив", "PLOVDIV");
        assertSimilar("Централна гара София", "SOFYA(SOFIA)");
    }

    @Test
    void wordsForStationAreIgnored() {
        assertSimilar("Bressanone, Stazione di Bressanone", "Bressanone/Brixen");
        assertSimilar("Ropice zastávka", "Ropice zast. (Ropica przystanek)");
    }

    @Test
    void spellingsWithoutSpecialLettersMatch() {
        assertSimilar("København H", "Koebenhavn H");
    }

    @Test
    void abbreviationsMatchTheFullWord() {
        assertSimilar("Bologna Centrale", "BOLOGNA C.LE");
        assertSimilar("Berlin Ostbf", "S Ostbahnhof (Berlin)");
        assertSimilar("Aachen Hauptbahnhof", "Aachen, Hbf");
    }

    @Test
    void translatedNamesMatch() {
        assertSimilar("Padua", "PADOVA");
        assertSimilar("Trieste Airport", "Trieste Aeroporto");
    }

    @Test
    void oneTypoInALongWordStillMatches() {
        assertSimilar("Madesano", "MEDESANO");
    }

    @Test
    void separateStationsOfOneCityAreNotSimilar() {
        assertNotSimilar("Avignon Centre", "Avignon-TGV");
        assertNotSimilar("Madrid-Atocha Cercanías", "Madrid-Puerta de Atocha-Almudena Grandes");
        assertNotSimilar("Málaga M.Z. Cercanías", "Málaga María Zambrano");
        assertNotSimilar("Napoli P. Garibaldi", "NAPOLI CENTRALE");
        assertNotSimilar("Viterbo", "VITERBO PORTA FIORENTINA");
        assertNotSimilar("Bagnoli", "BAGNOLI AGNANO TERME");
    }

    @Test
    void wordsWithADifferentFirstLetterDontMatch() {
        assertNotSimilar("Lezo-Rentería", "Errenteria");
    }

    @Test
    void sharingOnlyAWordForStationIsNotSimilar() {
        assertNotSimilar("Brno hlavní nádraží", "Praha hlavní nádraží");
        assertNotSimilar("Gare de Lyon", "Gare du Nord");
    }

    @Test
    void shortNameContainedInTheLongerOne() {
        assertThat(matcher.oneContainsTheOther("Split željeznički kolodvor", "Split")).isTrue();
        assertThat(matcher.oneContainsTheOther("Malahide", "Malahide railway station")).isTrue();
        assertThat(matcher.oneContainsTheOther("Desenzano Del Garda-Sirmione", "DESENZANO")).isTrue();
        assertThat(matcher.oneContainsTheOther("Avignon Centre", "Avignon-TGV")).isFalse();
    }

    private void assertSimilar(String name1, String name2) {
        assertThat(matcher.similarity(name1, name2)).as(name1 + " / " + name2).isGreaterThanOrEqualTo(MIN_SIMILARITY);
    }

    private void assertNotSimilar(String name1, String name2) {
        assertThat(matcher.similarity(name1, name2)).as(name1 + " / " + name2).isLessThan(MIN_SIMILARITY);
    }
}
