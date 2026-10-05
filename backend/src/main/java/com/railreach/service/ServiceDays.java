package com.railreach.service;

import java.util.BitSet;

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
