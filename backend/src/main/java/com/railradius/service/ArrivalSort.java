package com.railradius.service;

import java.util.Arrays;

/**
 * Sorts a station's arrival keys, each its arrival seconds in the high 32 bits and its grouped position in the low
 * ones, into exactly {@link Arrays#sort}'s order (#43). Keys come in ascending position, so a stable sort by seconds
 * alone gives that order, ties included: a least-significant-digit radix sort on the seconds, which on large stations
 * takes about half as long as the general sort. Below {@link #RADIX_MIN_COUNT} keys the general sort is faster.
 */
final class ArrivalSort {

    static final int RADIX_MIN_COUNT = 1024;
    private static final int DIGIT_BITS = 11;
    private static final int DIGIT_MASK = (1 << DIGIT_BITS) - 1;
    private static final int SECONDS_SHIFT = Integer.SIZE;

    // Reused from sort to sort: each thread's BoardingWorkspace has one
    private int[] bucketStart = new int[1 << DIGIT_BITS];
    private long[] buffer = new long[0];

    /** Sorts the first count keys; their low 32 bits must be ascending. */
    void sort(long[] keys, int count) {
        if (count < RADIX_MIN_COUNT) {
            Arrays.sort(keys, 0, count);
            return;
        }
        if (buffer.length < count) buffer = new long[Math.max(count, buffer.length * 2)];
        int minSeconds = Integer.MAX_VALUE;
        int maxSeconds = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            int seconds = seconds(keys[i]);
            minSeconds = Math.min(minSeconds, seconds);
            maxSeconds = Math.max(maxSeconds, seconds);
        }
        // Differences from the earliest arrival, as unsigned, so any range of seconds sorts
        int range = maxSeconds - minSeconds;
        long[] from = keys;
        long[] to = buffer;
        for (int shift = 0; shift == 0 || shift < Integer.SIZE && (range >>> shift) != 0; shift += DIGIT_BITS) {
            countingPass(from, to, count, minSeconds, shift);
            long[] sorted = to;
            to = from;
            from = sorted;
        }
        if (from != keys) System.arraycopy(from, 0, keys, 0, count);
    }

    /** Stable: keys with the same digit keep their order. */
    private void countingPass(long[] from, long[] to, int count, int minSeconds, int shift) {
        Arrays.fill(bucketStart, 0);
        for (int i = 0; i < count; i++) {
            bucketStart[digit(from[i], minSeconds, shift)]++;
        }
        int start = 0;
        for (int bucket = 0; bucket < bucketStart.length; bucket++) {
            int size = bucketStart[bucket];
            bucketStart[bucket] = start;
            start += size;
        }
        for (int i = 0; i < count; i++) {
            to[bucketStart[digit(from[i], minSeconds, shift)]++] = from[i];
        }
    }

    private static int digit(long key, int minSeconds, int shift) {
        return ((seconds(key) - minSeconds) >>> shift) & DIGIT_MASK;
    }

    private static int seconds(long key) {
        return (int) (key >> SECONDS_SHIFT);
    }
}
