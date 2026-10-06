package com.railreach.service;

import java.util.Arrays;
import java.util.BitSet;
import java.util.stream.IntStream;

/**
 * The days a GTFS service runs, as a bitset over epoch days: bit j of {@code words[i]} is epoch day
 * {@code (firstWord + i) * 64 + j}. The search has no travel date; it only asks whether two trips share a day (B25).
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

    /** True if {@code later} runs {@code dayShift} days (0 to 63) after a day this service runs. */
    boolean sharesDayWith(ServiceDays later, int dayShift) {
        if (this == EVERY_DAY) return later == EVERY_DAY || later.words.length > 0;
        if (later == EVERY_DAY) return words.length > 0;
        for (int i = 0; i < words.length; i++) {
            if ((words[i] & later.wordMovedBack(firstWord + i, dayShift)) != 0) return true;
        }
        return false;
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

    /**
     * The days of this service that come {@code dayShift} days (0 to 63) after a day {@code earlier} runs.
     * {@link #EVERY_DAY} on either side keeps the other side's days.
     */
    ServiceDays after(ServiceDays earlier, int dayShift) {
        if (earlier == EVERY_DAY) return this;
        if (this == EVERY_DAY) return earlier.movedForward(dayShift);
        long[] result = new long[words.length];
        for (int i = 0; i < words.length; i++) {
            result[i] = words[i] & earlier.wordMovedForward(firstWord + i, dayShift);
        }
        return trimmed(firstWord, result);
    }

    /** True if this service runs on every day {@code other} runs. */
    boolean covers(ServiceDays other) {
        if (this == EVERY_DAY) return true;
        if (other == EVERY_DAY) return false;
        for (int i = 0; i < other.words.length; i++) {
            if ((other.words[i] & ~wordAt(other.firstWord + i)) != 0) return false;
        }
        return true;
    }

    /**
     * {@code covers(later.after(earlier, dayShift))} without building it: the search checks this for many arrivals
     * per departure.
     */
    boolean coversAfter(ServiceDays later, ServiceDays earlier, int dayShift) {
        if (this == EVERY_DAY) return true;
        if (later == EVERY_DAY || earlier == EVERY_DAY) return covers(later.after(earlier, dayShift));
        for (int i = 0; i < later.words.length; i++) {
            int word = later.firstWord + i;
            long days = later.words[i] & earlier.wordMovedForward(word, dayShift);
            if ((days & ~wordAt(word)) != 0) return false;
        }
        return true;
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

    private ServiceDays movedForward(int dayShift) {
        if (dayShift == 0) return this;
        // Moving forward can reach into the word after the last one
        long[] result = new long[words.length + 1];
        for (int i = 0; i < result.length; i++) {
            result[i] = wordMovedForward(firstWord + i, dayShift);
        }
        return trimmed(firstWord, result);
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

    /** Word {@code word} as if every day ran dayShift days later: bit j is day {@code word * 64 + j - dayShift}. */
    private long wordMovedForward(int word, int dayShift) {
        long high = wordAt(word) << dayShift;
        return dayShift == 0 ? high : high | wordAt(word - 1) >>> (DAYS_PER_WORD - dayShift);
    }

    private long wordAt(int word) {
        int i = word - firstWord;
        return i >= 0 && i < words.length ? words[i] : 0;
    }
}
