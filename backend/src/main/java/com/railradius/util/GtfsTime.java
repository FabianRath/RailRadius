package com.railradius.util;

/** GTFS times are int seconds since service-day midnight and may exceed 24 h. */
public final class GtfsTime {

    public static final int SECONDS_PER_MINUTE = 60;
    private static final int SECONDS_PER_HOUR = 3600;
    private static final int MINUTES_PER_HOUR = 60;
    private static final int MINUTES_PER_DAY = 24 * 60;
    public static final int SECONDS_PER_DAY = MINUTES_PER_DAY * SECONDS_PER_MINUTE;
    /** A diff more negative than this is an overnight wrap, not a slightly earlier time in the data. */
    private static final int OVERNIGHT_WRAP_THRESHOLD_MINUTES = -60;
    /** Longest time {@link #diffMinutes} can return across midnight, i.e. from a clock time 61 min later. */
    public static final int MAX_OVERNIGHT_GAP_SECONDS =
            SECONDS_PER_DAY + (OVERNIGHT_WRAP_THRESHOLD_MINUTES - 1) * SECONDS_PER_MINUTE;

    private GtfsTime() {}

    /** Parses {@code H:MM[:SS]} (hours may exceed 23). Blank or malformed input gives 0. */
    public static int parseSeconds(String time) {
        if (time == null || time.isEmpty()) return 0;
        String[] parts = time.split(":");
        if (parts.length < 2) return 0;
        try {
            int hours = Integer.parseInt(parts[0].trim());
            int minutes = Integer.parseInt(parts[1].trim());
            int seconds = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 0;
            return hours * SECONDS_PER_HOUR + minutes * SECONDS_PER_MINUTE + seconds;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The clock minute (0..1439) of a time, so a trip's 24:21 and another feed's 00:21 compare equal. */
    public static int minuteOfDay(int seconds) {
        return (seconds % SECONDS_PER_DAY) / SECONDS_PER_MINUTE;
    }

    /** Formats as {@code HH:MM:SS}; negative input gives {@code 00:00:00}. */
    public static String format(int seconds) {
        if (seconds < 0) return "00:00:00";
        return String.format("%02d:%02d:%02d", seconds / SECONDS_PER_HOUR,
                (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE);
    }

    /**
     * Whole minutes from {@code fromSeconds} to {@code toSeconds}. Feeds restart times at midnight
     * on some trips, so a diff below -60 min is taken to cross midnight and gets +24 h.
     */
    public static int diffMinutes(int fromSeconds, int toSeconds) {
        int minutes = (toSeconds - fromSeconds) / SECONDS_PER_MINUTE;
        return minutes < OVERNIGHT_WRAP_THRESHOLD_MINUTES ? minutes + MINUTES_PER_DAY : minutes;
    }

    /** Formats a duration as {@code 2h 5m} or {@code 45m}; negative input gives {@code 0m}. */
    public static String formatDuration(int minutes) {
        int clamped = Math.max(0, minutes);
        int hours = clamped / MINUTES_PER_HOUR;
        int rest = clamped % MINUTES_PER_HOUR;
        return hours > 0 ? hours + "h " + rest + "m" : rest + "m";
    }
}
