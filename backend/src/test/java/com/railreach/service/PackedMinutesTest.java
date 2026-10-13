package com.railreach.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackedMinutesTest {

    @Test
    void durationsBelowTheLimitKeepTheirValue() {
        assertThat((int) PackedMinutes.of(0)).isZero();
        assertThat((int) PackedMinutes.of(PackedMinutes.UNREACHED - 1)).isEqualTo(PackedMinutes.UNREACHED - 1);
    }

    @Test
    void aJourneyTooLongToPackFailsInsteadOfWrappingAround() {
        assertThatThrownBy(() -> PackedMinutes.of(PackedMinutes.UNREACHED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anArenaLabelReturnsTheMinutesItWasGiven() {
        LabelArena labels = new LabelArena();
        int label = labels.add(0, 0, 1, 40_000, LabelArena.NONE, WeekPeriodDays.ALL);

        assertThat(labels.durationMinutes(label)).isEqualTo(40_000);
        assertThat(labels.resultLabels().durationMinutes()).containsExactly(40_000);
    }
}
