package com.railreach.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
