package com.railradius.service;

/**
 * A search's durations in minutes as chars, in the scratch columns that hold millions of them (S23). A journey's
 * minutes stay far below the limit (45 days), since every wait is at most a few hours; a longer one fails fast
 * instead of wrapping around.
 */
final class PackedMinutes {

    /** Above every packed duration: trip progress marks an unreached stop with it. */
    static final char UNREACHED = Character.MAX_VALUE;

    private PackedMinutes() {
    }

    static char of(int minutes) {
        if (minutes < 0 || minutes >= UNREACHED) {
            throw new IllegalStateException("Journey too long for the search: " + minutes + " minutes");
        }
        return (char) minutes;
    }
}
