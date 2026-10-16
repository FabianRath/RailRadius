package com.railradius.service;

import java.util.Arrays;

/**
 * The labels of one search as parallel arrays: the arrivals that become candidates or are boarded from, each with its
 * trip, where it boarded and left, its duration, its journey's days and the label before its transfer, without an
 * object per label. Other arrivals live only as their {@link RoundRides} row. An arena serves one search at a time and
 * is reused by the next; it grows as {@link ScratchCapacity} says and is never shrunk.
 * <p>
 * Stop indexes are shorts, so a trip with more than 32,767 stops fails fast. A label's transfers are the rides
 * before it, so they need no column. daysMemo is {@link LabelDays}' memo of the journey's exact running days.
 */
final class LabelArena {

    static final int NONE = -1;
    static final int UNKNOWN_DAYS = -1;
    private static final int INITIAL_CAPACITY = 1 << 12;
    // label >>> WORD_SHIFT is the label's word in the trim's bitset
    private static final int WORD_SHIFT = 6;

    private int[] trip = new int[INITIAL_CAPACITY];
    private int[] previous = new int[INITIAL_CAPACITY];
    private char[] durationMinutes = new char[INITIAL_CAPACITY];
    private short[] boardIdx = new short[INITIAL_CAPACITY];
    private short[] alightIdx = new short[INITIAL_CAPACITY];
    private long[] weekPeriodDays = new long[INITIAL_CAPACITY];
    private int[] daysMemo = new int[INITIAL_CAPACITY];
    private int size;
    // A trim's bitset of kept labels, and per word of it how many labels the words before keep
    private long[] kept = new long[0];
    private int[] keptBefore = new int[0];

    void clear() {
        size = 0;
    }

    int size() {
        return size;
    }

    /** previous is the label boarded from, {@link #NONE} for a first ride; days are its {@link WeekPeriodDays}. */
    int add(int tripIndex, int board, int alight, int minutes, int previousLabel, long days) {
        int label = reserve(1);
        set(label, tripIndex, board, alight, minutes, previousLabel, days);
        return label;
    }

    /** Makes room for count labels after the last one, to {@link #set} later. Returns the first of them. */
    int reserve(int count) {
        if (size + count > trip.length) grow(size + count);
        size += count;
        return size - count;
    }

    /** Fills a {@link #reserve}d label, as {@link #add} does. Threads may fill different labels at once. */
    void set(int label, int tripIndex, int board, int alight, int minutes, int previousLabel, long days) {
        if (alight > Short.MAX_VALUE) throw new IllegalStateException("Trip " + tripIndex + " has too many stops");
        trip[label] = tripIndex;
        previous[label] = previousLabel;
        durationMinutes[label] = PackedMinutes.of(minutes);
        boardIdx[label] = (short) board;
        alightIdx[label] = (short) alight;
        weekPeriodDays[label] = days;
        daysMemo[label] = UNKNOWN_DAYS;
    }

    int trip(int label) {
        return trip[label];
    }

    int previous(int label) {
        return previous[label];
    }

    int durationMinutes(int label) {
        return durationMinutes[label];
    }

    int boardIdx(int label) {
        return boardIdx[label];
    }

    int alightIdx(int label) {
        return alightIdx[label];
    }

    /** One per ride before the label's: each round boards from the one before. */
    int transfers(int label) {
        int count = 0;
        for (int ride = previous[label]; ride != NONE; ride = previous[ride]) {
            count++;
        }
        return count;
    }

    long weekPeriodDays(int label) {
        return weekPeriodDays[label];
    }

    int daysMemo(int label) {
        return daysMemo[label];
    }

    void setDaysMemo(int label, int memo) {
        daysMemo[label] = memo;
    }

    /** Starts marking the labels the next {@link #trim} keeps: none so far. */
    void startTrim() {
        int words = wordCount(size);
        if (kept.length < words) {
            kept = new long[words];
            keptBefore = new int[words];
        } else {
            Arrays.fill(kept, 0, words, 0L);
        }
    }

    /** Marks the label and every ride before its transfers for the next {@link #trim}. */
    void keepWithEarlierRides(int label) {
        for (int ride = label; ride != NONE && (kept[ride >>> WORD_SHIFT] & 1L << ride) == 0; ride = previous[ride]) {
            kept[ride >>> WORD_SHIFT] |= 1L << ride;
        }
    }

    /**
     * Drops every label not marked since {@link #startTrim}. Kept labels stay in order, and until the next trim,
     * {@link #trimmedIndex} maps a kept label's old index to its new one.
     */
    void trim() {
        int words = wordCount(size);
        int keptCount = 0;
        for (int word = 0; word < words; word++) {
            keptBefore[word] = keptCount;
            keptCount += Long.bitCount(kept[word]);
        }
        // Each kept label moves down or stays, so it never overwrites one that has yet to move
        for (int word = 0; word < words; word++) {
            for (long bits = kept[word]; bits != 0; bits &= bits - 1) {
                moveTo(word << WORD_SHIFT | Long.numberOfTrailingZeros(bits));
            }
        }
        size = keptCount;
    }

    /** The new index of a label kept by the last {@link #trim}. */
    int trimmedIndex(int label) {
        int word = label >>> WORD_SHIFT;
        return keptBefore[word] + Long.bitCount(kept[word] & ((1L << label) - 1));
    }

    /** The labels' columns that a {@link CompactResult} keeps. */
    CompactResult.Labels resultLabels() {
        // A label comes after the one it boarded from
        byte[] transfers = new byte[size];
        for (int label = 0; label < size; label++) {
            if (previous[label] != NONE) transfers[label] = (byte) (transfers[previous[label]] + 1);
        }
        return new CompactResult.Labels(Arrays.copyOf(trip, size), Arrays.copyOf(previous, size),
                intColumn(durationMinutes, size), Arrays.copyOf(boardIdx, size), Arrays.copyOf(alightIdx, size),
                transfers);
    }

    /** Bytes of the arrays, for the search's log. */
    long sizeBytes() {
        return (long) trip.length * (3 * Integer.BYTES + Character.BYTES + 2 * Short.BYTES + Long.BYTES)
                + (long) kept.length * (Long.BYTES + Integer.BYTES);
    }

    private void moveTo(int label) {
        int to = trimmedIndex(label);
        trip[to] = trip[label];
        previous[to] = previous[label] == NONE ? NONE : trimmedIndex(previous[label]);
        durationMinutes[to] = durationMinutes[label];
        boardIdx[to] = boardIdx[label];
        alightIdx[to] = alightIdx[label];
        weekPeriodDays[to] = weekPeriodDays[label];
        daysMemo[to] = daysMemo[label];
    }

    private static int[] intColumn(char[] column, int length) {
        int[] ints = new int[length];
        for (int i = 0; i < length; i++) {
            ints[i] = column[i];
        }
        return ints;
    }

    private static int wordCount(int labelCount) {
        return (labelCount + Long.SIZE - 1) >>> WORD_SHIFT;
    }

    private void grow(int minCapacity) {
        int capacity = ScratchCapacity.grown(trip.length, minCapacity);
        trip = Arrays.copyOf(trip, capacity);
        previous = Arrays.copyOf(previous, capacity);
        durationMinutes = Arrays.copyOf(durationMinutes, capacity);
        boardIdx = Arrays.copyOf(boardIdx, capacity);
        alightIdx = Arrays.copyOf(alightIdx, capacity);
        weekPeriodDays = Arrays.copyOf(weekPeriodDays, capacity);
        daysMemo = Arrays.copyOf(daysMemo, capacity);
    }
}
