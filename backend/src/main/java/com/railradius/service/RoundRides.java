package com.railradius.service;

import java.util.Arrays;

/**
 * The rides of one round of a compact search, a row per boarded trip, kept until the next round has boarded from
 * their arrivals. A ride records the stops after its board event one by one until it stops, so its arrivals follow
 * from the row: each stop's duration is worked out again from the trip's legs. Labels are made only for the arrivals
 * that need one, which on the full data are ~1 in 6. Records are numbered over the round, a ride's together: ride r
 * has records {@code recordStart(r)} up to {@code recordStart(r + 1)}. Reused from round to round and search to
 * search; grows as {@link ScratchCapacity} says and never shrinks.
 */
final class RoundRides {

    private static final int INITIAL_CAPACITY = 1 << 10;

    private int[] boardEvent = new int[INITIAL_CAPACITY];
    private int[] previous = new int[INITIAL_CAPACITY];
    private char[] boardMinutes = new char[INITIAL_CAPACITY];
    private long[] days = new long[INITIAL_CAPACITY];
    // recordEnd[r]: records of the rides up to r, inclusive
    private int[] recordEnd = new int[INITIAL_CAPACITY];
    private int size;

    void clear() {
        size = 0;
    }

    int size() {
        return size;
    }

    /** previous is the label boarded from, {@link LabelArena#NONE} for a first ride; days are its WeekPeriodDays. */
    void add(int rideBoardEvent, int previousLabel, int rideBoardMinutes, long rideDays, int recordedStops) {
        if (size == boardEvent.length) grow();
        boardEvent[size] = rideBoardEvent;
        previous[size] = previousLabel;
        boardMinutes[size] = PackedMinutes.of(rideBoardMinutes);
        days[size] = rideDays;
        recordEnd[size] = recordCount() + recordedStops;
        size++;
    }

    int boardEvent(int ride) {
        return boardEvent[ride];
    }

    int previous(int ride) {
        return previous[ride];
    }

    /** The journey's minutes when the ride leaves its board event. */
    int boardMinutes(int ride) {
        return boardMinutes[ride];
    }

    long days(int ride) {
        return days[ride];
    }

    int recordStart(int ride) {
        return ride == 0 ? 0 : recordEnd[ride - 1];
    }

    int recordCount() {
        return size == 0 ? 0 : recordEnd[size - 1];
    }

    long sizeBytes() {
        return (long) boardEvent.length * (3 * Integer.BYTES + Character.BYTES + Long.BYTES);
    }

    private void grow() {
        int capacity = ScratchCapacity.grown(boardEvent.length, size + 1);
        boardEvent = Arrays.copyOf(boardEvent, capacity);
        previous = Arrays.copyOf(previous, capacity);
        boardMinutes = Arrays.copyOf(boardMinutes, capacity);
        days = Arrays.copyOf(days, capacity);
        recordEnd = Arrays.copyOf(recordEnd, capacity);
    }
}
