package com.railreach.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.BitSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeekPeriodDaysTest {

    // A Thursday; the periods start on Monday 2025-12-29
    private static final LocalDate TODAY = LocalDate.of(2026, 1, 1);

    @Test
    void eachDateSetsItsWeekdayOfItsPeriod() {
        long mask = WeekPeriodDays.of(days("2026-01-01", "2026-01-05", "2026-02-09"), epochDay(TODAY));

        // Thursday of period 0, Monday of period 0, Monday of period 1
        assertThat(mask).isEqualTo(1L << 3 | 1L | 1L << 7);
    }

    @Test
    void datesBeforeTodayAndBeyondTheLastPeriodAreLeftOut() {
        assertThat(WeekPeriodDays.of(days("2025-12-31", "2027-01-11"), epochDay(TODAY))).isZero();
    }

    @Test
    void serviceWithoutCalendarRunsOnEveryWeekdayOfEveryPeriod() {
        assertThat(WeekPeriodDays.of(ServiceDays.EVERY_DAY, epochDay(TODAY))).isEqualTo(WeekPeriodDays.ALL);
    }

    @Test
    void movingADayForwardTurnsSundayIntoMondayOfItsOwnAndTheNextPeriod() {
        assertThat(WeekPeriodDays.movedForward(1L, 1)).isEqualTo(1L << 1);
        assertThat(WeekPeriodDays.movedForward(1L << 6, 1)).isEqualTo(1L | 1L << 7);
        // The last period's Sunday has no next period
        assertThat(WeekPeriodDays.movedForward(1L << 62, 1)).isEqualTo(1L << 56);
        assertThat(WeekPeriodDays.movedForward(1L << 6, 0)).isEqualTo(1L << 6);
        assertThatThrownBy(() -> WeekPeriodDays.movedForward(1L, 2)).isInstanceOf(IllegalArgumentException.class);
    }

    private static int epochDay(LocalDate date) {
        return (int) date.toEpochDay();
    }

    private static ServiceDays days(String... dates) {
        BitSet bits = new BitSet();
        int baseDay = epochDay(LocalDate.of(2025, 1, 1));
        for (String date : dates) bits.set(epochDay(LocalDate.parse(date)) - baseDay);
        return ServiceDays.of(bits, baseDay);
    }
}
