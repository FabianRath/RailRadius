package com.railradius.service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

/**
 * Browser stand-in for the backend's Parallel: TeaVM's WebAssembly has one thread, so all of it is sequential, in plain
 * loops rather than streams, which cost the engine time for nothing (#41).
 */
final class Parallel {

    private Parallel() {
    }

    static void forEach(int count, IntConsumer action) {
        for (int i = 0; i < count; i++) {
            action.accept(i);
        }
    }

    static <T> List<T> map(int count, IntFunction<T> value) {
        List<T> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(value.apply(i));
        }
        return values;
    }

    static int[] mapToInt(int count, IntUnaryOperator value) {
        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = value.applyAsInt(i);
        }
        return values;
    }

    static void prefixSum(int[] values) {
        for (int i = 1; i < values.length; i++) {
            values[i] += values[i - 1];
        }
    }

    static void orInto(long[] words, int index, long bits) {
        words[index] |= bits;
    }
}
