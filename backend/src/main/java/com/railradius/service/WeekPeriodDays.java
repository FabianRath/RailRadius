package com.railradius.service;

/**
 * The days a service runs as the search sees them: one bit per weekday of each 6-week period of the coming year, set
 * if the service runs on at least one such date from today on. Bit {@code period * 7 + weekday} (Monday = 0); periods
 * start at the Monday of today's week.
 * <p>
 * The search checks that every train of a journey runs on a common day (B33). Exact dates made that check, and the
 * pruning that depends on it, too costly on the full data: tens of thousands of distinct day sets, so rides rarely
 * cover each other. Weekday × period still tells apart weekday from weekend trains and summer from winter
 * timetables; it lets through trains that run in the same period on the same weekdays but in different weeks. The
 * days shown for a journey stay exact ({@link JourneyRunningDays}).
 */
final class WeekPeriodDays {

    private static final int DAYS_PER_WEEK = 7;
    private static final int WEEKS_PER_PERIOD = 6;
    private static final int PERIODS = 9;
    private static final int DAYS_PER_PERIOD = DAYS_PER_WEEK * WEEKS_PER_PERIOD;
    // 1970-01-01, epoch day 0, was a Thursday
    private static final int EPOCH_WEEKDAY = 3;
    private static final int SUNDAY = 6;

    /** Every weekday of every period: a service without calendar data. */
    static final long ALL = (1L << (PERIODS * DAYS_PER_WEEK)) - 1;
    private static final long SUNDAYS = sundays();

    private WeekPeriodDays() {
    }

    static long of(ServiceDays days, int todayEpochDay) {
        if (days == ServiceDays.EVERY_DAY) return ALL;
        int periodsStart = todayEpochDay - weekday(todayEpochDay);
        int horizonEnd = periodsStart + PERIODS * DAYS_PER_PERIOD;
        long mask = 0;
        for (int day : days.epochDays().filter(d -> d >= todayEpochDay && d < horizonEnd).toArray()) {
            int offset = day - periodsStart;
            mask |= 1L << (offset / DAYS_PER_PERIOD * DAYS_PER_WEEK + offset % DAYS_PER_WEEK);
        }
        return mask;
    }

    /**
     * The mask as if every day ran dayShift (0 or 1) days later: a wait past midnight. A Sunday becomes the Monday of
     * its own period and of the next one, since a period's last Sunday is followed by the next period's first Monday.
     */
    static long movedForward(long mask, int dayShift) {
        if (dayShift == 0) return mask;
        if (dayShift != 1) throw new IllegalArgumentException("Day shift must be 0 or 1: " + dayShift);
        long sundays = mask & SUNDAYS;
        return ((mask & ~SUNDAYS) << 1) | (sundays >>> SUNDAY) | ((sundays << 1) & ALL);
    }

    private static int weekday(int epochDay) {
        return Math.floorMod(epochDay + EPOCH_WEEKDAY, DAYS_PER_WEEK);
    }

    private static long sundays() {
        long mask = 0;
        for (int period = 0; period < PERIODS; period++) {
            mask |= 1L << (period * DAYS_PER_WEEK + SUNDAY);
        }
        return mask;
    }
}
