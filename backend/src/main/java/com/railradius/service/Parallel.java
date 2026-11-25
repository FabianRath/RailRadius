package com.railradius.service;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import java.util.stream.IntStream;

/**
 * Every place the search runs on several threads, in one class, so an engine without threads (the browser, #8)
 * swaps in a sequential one. Results never depend on the threads.
 */
final class Parallel {

    private static final VarHandle LONG_ELEMENT = MethodHandles.arrayElementVarHandle(long[].class);

    private Parallel() {
    }

    /** Calls the action with 0 up to count, in parallel. */
    static void forEach(int count, IntConsumer action) {
        IntStream.range(0, count).parallel().forEach(action);
    }

    /** The values for 0 up to count, worked out in parallel, in index order. */
    static <T> List<T> map(int count, IntFunction<T> value) {
        return IntStream.range(0, count).parallel().mapToObj(value).toList();
    }

    /** The values for 0 up to count, worked out in parallel, in index order. */
    static int[] mapToInt(int count, IntUnaryOperator value) {
        return IntStream.range(0, count).parallel().map(value).toArray();
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
