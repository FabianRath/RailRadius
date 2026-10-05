package com.railreach.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.BitSet;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class JourneyRunningDaysTest {

    @Test
    void weekdayPatternIgnoresAHoliday() {
        ServiceDays weekdays = days(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 30), date ->
                date.getDayOfWeek().getValue() <= 5 && !date.equals(LocalDate.of(2026, 1, 6)));

        assertThat(JourneyRunningDays.describe(weekdays)).hasValue("Runs Mon–Fri");
    }

    @Test
    void twoConsecutiveWeekdaysAreNotARange() {
        ServiceDays weekend = days(LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 25), date ->
                date.getDayOfWeek().getValue() >= 6);

        assertThat(JourneyRunningDays.describe(weekend)).hasValue("Runs Sat, Sun");
    }

    @Test
    void everyWeekdayReadsDaily() {
        ServiceDays daily = days(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 31), date -> true);

        assertThat(JourneyRunningDays.describe(daily)).hasValue("Runs daily");
    }

    @Test
    void aFewDaysAreIrregular() {
        ServiceDays twoDays = days(LocalDate.of(2026, 1, 6), LocalDate.of(2026, 1, 9),
                date -> date.getDayOfMonth() % 3 == 0);

        assertThat(JourneyRunningDays.describe(twoDays)).hasValue("Irregular: 2 days in the timetable");
    }

    @Test
    void scatteredDaysWithoutAWeekdayPatternAreCounted() {
        // Mon 5, Tue 13, Wed 21, Thu 29 Jan: each weekday runs once in four weeks
        ServiceDays scattered = days(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 29),
                date -> date.getDayOfMonth() % 8 == 5);

        assertThat(JourneyRunningDays.describe(scattered)).hasValue("Irregular: 4 days in the timetable");
    }

    @Test
    void aSingleDayIsSingular() {
        ServiceDays oneDay = days(LocalDate.of(2025, 12, 12), LocalDate.of(2025, 12, 12), date -> true);

        assertThat(JourneyRunningDays.describe(oneDay)).hasValue("Irregular: 1 day in the timetable");
    }

    @Test
    void noCalendarDataOrNoDaysGiveNoDescription() {
        assertThat(JourneyRunningDays.describe(ServiceDays.EVERY_DAY)).isEmpty();
        assertThat(JourneyRunningDays.describe(ServiceDays.of(new BitSet(), 0))).isEmpty();
    }

    private static ServiceDays days(LocalDate first, LocalDate last, Predicate<LocalDate> runs) {
        BitSet bits = new BitSet();
        first.datesUntil(last.plusDays(1)).filter(runs).forEach(date -> bits.set((int) date.toEpochDay()));
        return ServiceDays.of(bits, 0);
    }
}
