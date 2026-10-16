package com.railradius.service;

import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceDaysTest {

    @Test
    void servicesRunningOnTheSameDayShareIt() {
        assertThat(days(100, 101).intersect(days(101, 200), 0).isEmpty()).isFalse();
        assertThat(days(100, 101).intersect(days(102, 200), 0).isEmpty()).isTrue();
    }

    @Test
    void nextDayShiftWorksAcrossAWordBoundary() {
        // Day 127 is the last bit of one word, day 128 the first of the next
        assertThat(days(127).intersect(days(128), 1).epochDays()).containsExactly(127);
        assertThat(days(127).intersect(days(128), 0).isEmpty()).isTrue();
        assertThat(days(128).intersect(days(127), 1).isEmpty()).isTrue();
    }

    @Test
    void negativeEpochDaysAreStored() {
        assertThat(days(-1).intersect(days(0), 1).epochDays()).containsExactly(-1);
    }

    @Test
    void intersectKeepsTheDaysOnWhichTheLaterServiceRunsShiftedDaysLater() {
        assertThat(days(100, 101, 102).intersect(days(101, 103), 1).epochDays()).containsExactly(100, 102);
        assertThat(days(127, 128).intersect(days(128), 0).epochDays()).containsExactly(128);
    }

    @Test
    void intersectWithEveryDayKeepsTheOtherSidesDays() {
        assertThat(ServiceDays.EVERY_DAY.intersect(days(128), 1).epochDays()).containsExactly(127);
        assertThat(days(5).intersect(ServiceDays.EVERY_DAY, 1).epochDays()).containsExactly(5);
        assertThat(ServiceDays.EVERY_DAY.intersect(ServiceDays.EVERY_DAY, 0)).isSameAs(ServiceDays.EVERY_DAY);
    }

    @Test
    void fromKeepsTheDaysOnAndAfterTheGivenDay() {
        assertThat(days(5, 127, 128, 200).from(128).epochDays()).containsExactly(128, 200);
        assertThat(days(5, 6).from(-100).epochDays()).containsExactly(5, 6);
        assertThat(days(5, 6).from(7).isEmpty()).isTrue();
        assertThat(ServiceDays.EVERY_DAY.from(7)).isSameAs(ServiceDays.EVERY_DAY);
    }

    @Test
    void unionHoldsTheDaysOfBothServices() {
        assertThat(days(5, 6).union(days(130)).epochDays()).containsExactly(5, 6, 130);
        assertThat(days(130).union(days(5)).epochDays()).containsExactly(5, 130);
        assertThat(days(5).union(ServiceDays.EVERY_DAY)).isSameAs(ServiceDays.EVERY_DAY);
    }

    @Test
    void servicesWithoutACommonDayIntersectToNoDays() {
        assertThat(days(100).intersect(days(102), 1).isEmpty()).isTrue();
        assertThat(ServiceDays.EVERY_DAY.isEmpty()).isFalse();
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
