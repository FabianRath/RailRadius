package com.railreach.service;

import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceDaysTest {

    @Test
    void servicesRunningOnTheSameDayShareIt() {
        assertThat(days(100, 101).sharesDayWith(days(101, 200), 0)).isTrue();
        assertThat(days(100, 101).sharesDayWith(days(102, 200), 0)).isFalse();
    }

    @Test
    void nextDayShiftWorksAcrossAWordBoundary() {
        // Day 127 is the last bit of one word, day 128 the first of the next
        assertThat(days(127).sharesDayWith(days(128), 1)).isTrue();
        assertThat(days(127).sharesDayWith(days(128), 0)).isFalse();
        assertThat(days(128).sharesDayWith(days(127), 1)).isFalse();
    }

    @Test
    void negativeEpochDaysAreStored() {
        assertThat(days(-1).sharesDayWith(days(0), 1)).isTrue();
    }

    @Test
    void everyDaySharesADayWithAnyServiceThatRuns() {
        assertThat(ServiceDays.EVERY_DAY.sharesDayWith(days(5), 1)).isTrue();
        assertThat(days(5).sharesDayWith(ServiceDays.EVERY_DAY, 0)).isTrue();
        assertThat(ServiceDays.EVERY_DAY.sharesDayWith(days(), 0)).isFalse();
    }

    private static ServiceDays days(int... epochDays) {
        int baseDay = -10;
        BitSet bits = new BitSet();
        for (int day : epochDays) {
            bits.set(day - baseDay);
        }
        return ServiceDays.of(bits, baseDay);
    }
}
