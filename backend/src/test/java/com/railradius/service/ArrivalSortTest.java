package com.railradius.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class ArrivalSortTest {

    private static final long SEED = 43;
    private static final int RANDOM_INPUTS = 100;
    private static final int MAX_COUNT = 4 * ArrivalSort.RADIX_MIN_COUNT;
    private static final int FIRST_POSITION = 1_000;
    // Two days of seconds, and few distinct times so equal ones are common
    private static final int SPAN_SECONDS = 2 * 86_400;
    private static final int FEW_TIMES = 50;
    private static final int SECONDS_SHIFT = Integer.SIZE;

    @Test
    void randomArrivalsSortAsTheGeneralSortDoesTiesIncluded() {
        Random random = new Random(SEED);
        ArrivalSort sorter = new ArrivalSort();
        for (int input = 0; input < RANDOM_INPUTS; input++) {
            int count = random.nextInt(MAX_COUNT);
            boolean fewTimes = random.nextBoolean();
            long[] keys = new long[count];
            for (int i = 0; i < count; i++) {
                int seconds = fewTimes ? random.nextInt(FEW_TIMES) * 60 : random.nextInt(SPAN_SECONDS);
                keys[i] = (long) seconds << SECONDS_SHIFT | (FIRST_POSITION + i);
            }

            assertSortsLikeTheGeneralSort(sorter, keys);
        }
    }

    @Test
    void timesSpanningMoreThanOneDigitAndBelowZeroSortAsTheGeneralSortDoes() {
        int[] seconds = {Integer.MAX_VALUE, -5, 0, 1 << 22, -5, 7, Integer.MIN_VALUE + 1, 7};
        long[] keys = new long[ArrivalSort.RADIX_MIN_COUNT];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = (long) seconds[i % seconds.length] << SECONDS_SHIFT | i;
        }

        assertSortsLikeTheGeneralSort(new ArrivalSort(), keys);
    }

    private static void assertSortsLikeTheGeneralSort(ArrivalSort sorter, long[] keys) {
        long[] expected = keys.clone();
        Arrays.sort(expected);
        // Spare room past count must stay untouched
        long[] sorted = Arrays.copyOf(keys, keys.length + 1);
        sorted[keys.length] = Long.MIN_VALUE;
        sorter.sort(sorted, keys.length);
        assertThat(Arrays.copyOf(sorted, keys.length)).containsExactly(expected);
        assertThat(sorted[keys.length]).isEqualTo(Long.MIN_VALUE);
    }
}
