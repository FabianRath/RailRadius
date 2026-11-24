package com.railradius.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class SearchCountsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);

    @Test
    void serverAndBrowserSearchesAreCountedApartUnderToday() {
        SearchCounts counts = new SearchCounts(TransitGraph.withToday(TODAY));

        counts.countServerSearch();
        counts.countBrowserSearch();
        counts.countBrowserSearch();

        assertThat(counts.perDay()).containsExactly(new SearchCounts.DaySearches("2026-11-24", 1, 2));
    }

    @Test
    void noSearchesGiveNoDays() {
        assertThat(new SearchCounts(TransitGraph.withToday(TODAY)).perDay()).isEmpty();
    }
}
