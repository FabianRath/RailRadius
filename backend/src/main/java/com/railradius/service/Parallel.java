package com.railradius.service;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * Every place the search runs on several threads, in one class, so an engine without threads (the browser, #8)
 * swaps in a sequential one. Results never depend on the threads.
 */
final class Parallel {

    private static final VarHandle LONG_ELEMENT = MethodHandles.arrayElementVarHandle(long[].class);

    private Parallel() {
    }

    /** 0 up to count, processed in parallel. */
    static IntStream range(int count) {
        return IntStream.range(0, count).parallel();
    }

    /** Each value becomes the sum of itself and all before it. */
    static void prefixSum(int[] values) {
        Arrays.parallelPrefix(values, Integer::sum);
    }

    /** words[index] |= bits, safe against other threads doing the same. */
    static void orInto(long[] words, int index, long bits) {
        LONG_ELEMENT.getAndBitwiseOr(words, index, bits);
    }
}
