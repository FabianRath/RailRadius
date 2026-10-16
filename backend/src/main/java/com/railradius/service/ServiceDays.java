package com.railradius.service;

import java.util.Arrays;
import java.util.BitSet;
import java.util.stream.IntStream;

/**
 * The days a GTFS service runs, as a bitset over epoch days: bit j of {@code words[i]} is epoch day
 * {@code (firstWord + i) * 64 + j}. The search compares days coarsely ({@link WeekPeriodDays}); these exact days are
 * for the days shown for a journey and for the rare-train rule.
 */
public record ServiceDays(int firstWord, long[] words) {

    /** For trips whose feed has no calendar or whose service is not in it. Compared by identity. */
    static final ServiceDays EVERY_DAY = new ServiceDays(0, new long[0]);

    private static final int DAYS_PER_WORD = Long.SIZE;

    /** The days of {@code bits}, where bit i is epoch day {@code baseDay + i}. */
    static ServiceDays of(BitSet bits, int baseDay) {
        if (bits.isEmpty()) return new ServiceDays(0, new long[0]);
        int firstWord = Math.floorDiv(baseDay + bits.nextSetBit(0), DAYS_PER_WORD);
        int lastWord = Math.floorDiv(baseDay + bits.length() - 1, DAYS_PER_WORD);
        long[] words = new long[lastWord - firstWord + 1];
        for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
            int day = baseDay + i;
            words[Math.floorDiv(day, DAYS_PER_WORD) - firstWord] |= 1L << Math.floorMod(day, DAYS_PER_WORD);
        }
        return new ServiceDays(firstWord, words);
    }

    /**
     * The days of this service on which {@code later} runs {@code dayShift} days (0 to 63) later. {@link #EVERY_DAY}
     * on either side keeps the other side's days.
     */
    ServiceDays intersect(ServiceDays later, int dayShift) {
        if (later == EVERY_DAY) return this;
        if (this == EVERY_DAY) return later.movedBack(dayShift);
        long[] result = new long[words.length];
        for (int i = 0; i < words.length; i++) {
            result[i] = words[i] & later.wordMovedBack(firstWord + i, dayShift);
        }
        return trimmed(firstWord, result);
    }

    /** The days on which this service or {@code other} runs. */
    ServiceDays union(ServiceDays other) {
        if (this == EVERY_DAY || other == EVERY_DAY) return EVERY_DAY;
        if (other.words.length == 0) return this;
        if (words.length == 0) return other;
        int first = Math.min(firstWord, other.firstWord);
        int last = Math.max(firstWord + words.length, other.firstWord + other.words.length);
        long[] result = new long[last - first];
        for (int i = 0; i < result.length; i++) {
            result[i] = wordAt(first + i) | other.wordAt(first + i);
        }
        return new ServiceDays(first, result);
    }

    /** The days from {@code epochDay} on; {@link #EVERY_DAY} stays as it is. */
    ServiceDays from(int epochDay) {
        if (this == EVERY_DAY) return this;
        int firstKeptWord = Math.floorDiv(epochDay, DAYS_PER_WORD) - firstWord;
        if (firstKeptWord < 0) return this;
        long[] result = words.clone();
        Arrays.fill(result, 0, Math.min(firstKeptWord, result.length), 0L);
        if (firstKeptWord < result.length) result[firstKeptWord] &= -1L << Math.floorMod(epochDay, DAYS_PER_WORD);
        return trimmed(firstWord, result);
    }

    /** True if this service never runs; {@link #EVERY_DAY} runs on every day. */
    boolean isEmpty() {
        return this != EVERY_DAY && words.length == 0;
    }

    /** The number of days it runs; 0 for {@link #EVERY_DAY}. */
    int dayCount() {
        return Arrays.stream(words).mapToInt(Long::bitCount).sum();
    }

    /** The last day it runs; not defined for an empty service or {@link #EVERY_DAY}. */
    int lastEpochDay() {
        int lastWord = words.length - 1;
        return (firstWord + lastWord) * DAYS_PER_WORD + DAYS_PER_WORD - 1 - Long.numberOfLeadingZeros(words[lastWord]);
    }

    /** The epoch days, ascending. Empty for {@link #EVERY_DAY}. */
    IntStream epochDays() {
        return IntStream.range(0, words.length).flatMap(i -> BitSet.valueOf(new long[] {words[i]}).stream()
                .map(j -> (firstWord + i) * DAYS_PER_WORD + j));
    }

    private ServiceDays movedBack(int dayShift) {
        if (dayShift == 0) return this;
        // Moving back can reach into the word before the first one
        long[] result = new long[words.length + 1];
        for (int i = 0; i < result.length; i++) {
            result[i] = wordMovedBack(firstWord - 1 + i, dayShift);
        }
        return trimmed(firstWord - 1, result);
    }

    private static ServiceDays trimmed(int firstWord, long[] words) {
        int start = 0;
        int end = words.length;
        while (start < end && words[start] == 0) start++;
        while (end > start && words[end - 1] == 0) end--;
        return new ServiceDays(firstWord + start, Arrays.copyOfRange(words, start, end));
    }

    /** Word {@code word} as if every day ran dayShift days earlier: bit j is day {@code word * 64 + j + dayShift}. */
    private long wordMovedBack(int word, int dayShift) {
        long low = wordAt(word) >>> dayShift;
        return dayShift == 0 ? low : low | wordAt(word + 1) << (DAYS_PER_WORD - dayShift);
    }

    private long wordAt(int word) {
        int i = word - firstWord;
        return i >= 0 && i < words.length ? words[i] : 0;
    }
}
