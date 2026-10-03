package com.railreach.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GtfsTimeTest {

    @Test
    void parsesTimesPastMidnightOfTheServiceDay() {
        assertThat(GtfsTime.parseSeconds("08:30:15")).isEqualTo(8 * 3600 + 30 * 60 + 15);
        assertThat(GtfsTime.parseSeconds("25:10:00")).isEqualTo(25 * 3600 + 10 * 60);
        assertThat(GtfsTime.parseSeconds("7:05")).isEqualTo(7 * 3600 + 5 * 60);
    }

    @Test
    void blankOrMalformedTimesParseAsZero() {
        assertThat(GtfsTime.parseSeconds(null)).isZero();
        assertThat(GtfsTime.parseSeconds("")).isZero();
        assertThat(GtfsTime.parseSeconds("noon")).isZero();
        assertThat(GtfsTime.parseSeconds("ab:cd")).isZero();
    }

    @Test
    void formatsAsZeroPaddedHoursMinutesSeconds() {
        assertThat(GtfsTime.format(8 * 3600 + 5)).isEqualTo("08:00:05");
        assertThat(GtfsTime.format(25 * 3600)).isEqualTo("25:00:00");
        assertThat(GtfsTime.format(-1)).isEqualTo("00:00:00");
    }

    @Test
    void diffWrapsAcrossMidnightOnlyBelowMinusOneHour() {
        assertThat(GtfsTime.diffMinutes(23 * 3600 + 30 * 60, 10 * 60)).isEqualTo(40);
        assertThat(GtfsTime.diffMinutes(10 * 3600, 9 * 3600 + 30 * 60)).isEqualTo(-30);
        assertThat(GtfsTime.diffMinutes(8 * 3600, 9 * 3600)).isEqualTo(60);
    }

    @Test
    void formatsDurationsWithHoursOnlyWhenNeeded() {
        assertThat(GtfsTime.formatDuration(125)).isEqualTo("2h 5m");
        assertThat(GtfsTime.formatDuration(45)).isEqualTo("45m");
        assertThat(GtfsTime.formatDuration(-5)).isEqualTo("0m");
    }
}
