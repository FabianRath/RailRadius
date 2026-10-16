package com.railradius.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * The distinct exact running days of the trips, each stored once, and their {@link WeekPeriodDays} as of one day. A
 * trip refers to its pattern by index. {@link ServiceDays#EVERY_DAY} stays its own pattern, apart from a service that
 * never runs, and keeps its identity.
 */
final class DayPatterns {

    // A ServiceDays and its long[] (headers, fields, reference), on top of the words themselves
    private static final int OBJECT_BYTES_PER_PATTERN = 40;

    private final ServiceDays[] patterns;
    private volatile Masks masks = new Masks(Integer.MIN_VALUE, new long[0]);

    /** The patterns' WeekPeriodDays as of one epoch day. */
    private record Masks(int epochDay, long[] byPattern) {}

    private DayPatterns(ServiceDays[] patterns) {
        this.patterns = patterns;
    }

    /** Hands out one pattern index per distinct days. */
    static final class Builder {

        private final Map<DaysKey, Integer> indexByDays = new HashMap<>();
        private final List<ServiceDays> patterns = new ArrayList<>();

        int intern(ServiceDays days) {
            return indexByDays.computeIfAbsent(DaysKey.of(days), key -> {
                patterns.add(days);
                return patterns.size() - 1;
            });
        }

        /** The first days interned with this index. */
        ServiceDays days(int pattern) {
            return patterns.get(pattern);
        }

        DayPatterns build() {
            return new DayPatterns(patterns.toArray(ServiceDays[]::new));
        }
    }

    /** ServiceDays compared by content, except that EVERY_DAY equals only itself. */
    private record DaysKey(boolean everyDay, int firstWord, long[] words) {

        static DaysKey of(ServiceDays days) {
            return new DaysKey(days == ServiceDays.EVERY_DAY, days.firstWord(), days.words());
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof DaysKey key && everyDay == key.everyDay && firstWord == key.firstWord
                    && Arrays.equals(words, key.words);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * Boolean.hashCode(everyDay) + firstWord) + Arrays.hashCode(words);
        }
    }

    /** Reads what {@link #writeTo} wrote; the pattern that was EVERY_DAY is that instance again. */
    static DayPatterns readFrom(BinaryBlocks.Reader in) throws IOException {
        int[] firstWords = in.ints();
        int[] wordStart = in.ints();
        long[] words = in.longs();
        int[] everyDayPatterns = in.ints();
        ServiceDays[] patterns = new ServiceDays[firstWords.length];
        for (int pattern = 0; pattern < patterns.length; pattern++) {
            patterns[pattern] = new ServiceDays(firstWords[pattern],
                    Arrays.copyOfRange(words, wordStart[pattern], wordStart[pattern + 1]));
        }
        for (int pattern : everyDayPatterns) {
            patterns[pattern] = ServiceDays.EVERY_DAY;
        }
        return new DayPatterns(patterns);
    }

    /** Each pattern's first word, its words as CSR, and the index of EVERY_DAY if it is one of them. */
    void writeTo(BinaryBlocks.Writer out) throws IOException {
        int[] wordStart = new int[patterns.length + 1];
        for (int pattern = 0; pattern < patterns.length; pattern++) {
            wordStart[pattern + 1] = wordStart[pattern] + patterns[pattern].words().length;
        }
        long[] words = new long[wordStart[patterns.length]];
        for (int pattern = 0; pattern < patterns.length; pattern++) {
            long[] patternWords = patterns[pattern].words();
            System.arraycopy(patternWords, 0, words, wordStart[pattern], patternWords.length);
        }
        out.ints(Arrays.stream(patterns).mapToInt(ServiceDays::firstWord).toArray());
        out.ints(wordStart);
        out.longs(words);
        out.ints(IntStream.range(0, patterns.length).filter(i -> patterns[i] == ServiceDays.EVERY_DAY).toArray());
    }

    int size() {
        return patterns.length;
    }

    ServiceDays days(int pattern) {
        return patterns[pattern];
    }

    /** The patterns' {@link WeekPeriodDays} as of epochDay, by pattern index. */
    long[] weekPeriodMasks(int epochDay) {
        Masks known = masks;
        if (known.epochDay() == epochDay) return known.byPattern();
        long[] byPattern = new long[patterns.length];
        for (int pattern = 0; pattern < patterns.length; pattern++) {
            byPattern[pattern] = WeekPeriodDays.of(patterns[pattern], epochDay);
        }
        masks = new Masks(epochDay, byPattern);
        return byPattern;
    }

    long sizeBytes() {
        long wordBytes = Arrays.stream(patterns).mapToLong(days -> (long) days.words().length * Long.BYTES).sum();
        return wordBytes + (long) patterns.length * (OBJECT_BYTES_PER_PATTERN + Long.BYTES);
    }
}
