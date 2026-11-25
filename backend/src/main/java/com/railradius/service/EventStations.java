package com.railradius.service;

/**
 * Each stop event's station index. Full data has ~34k stations, so 16 bits hold them and halve the column (#37); a
 * dataset with more stations keeps 32 bits, so it still searches correctly.
 */
final class EventStations {

    // A char holds 0 to 65535 without sign, so this many station indexes fit
    private static final int NARROW_STATION_LIMIT = Character.MAX_VALUE + 1;

    // Exactly one of the two is set
    private final char[] narrow;
    private final int[] wide;

    private EventStations(char[] narrow, int[] wide) {
        this.narrow = narrow;
        this.wide = wide;
    }

    /** The column of these stations, narrow if every index below stationCount fits 16 bits. */
    static EventStations of(int[] stations, int stationCount) {
        if (stationCount > NARROW_STATION_LIMIT) return new EventStations(null, stations);
        char[] narrow = new char[stations.length];
        for (int event = 0; event < stations.length; event++) {
            narrow[event] = (char) stations[event];
        }
        return new EventStations(narrow, null);
    }

    int get(int event) {
        return narrow != null ? narrow[event] : wide[event];
    }

    int size() {
        return narrow != null ? narrow.length : wide.length;
    }

    /** Whether the column holds 16 bits per event. */
    boolean isNarrow() {
        return narrow != null;
    }

    int[] toInts() {
        if (wide != null) return wide;
        int[] stations = new int[narrow.length];
        for (int event = 0; event < narrow.length; event++) {
            stations[event] = narrow[event];
        }
        return stations;
    }

    long sizeBytes() {
        return narrow != null ? (long) narrow.length * Character.BYTES : (long) wide.length * Integer.BYTES;
    }
}
