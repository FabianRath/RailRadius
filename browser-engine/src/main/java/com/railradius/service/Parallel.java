package com.railradius.service;

import java.util.stream.IntStream;

/** Browser stand-in for the backend's Parallel: TeaVM's WebAssembly has one thread, so all of it is sequential. */
final class Parallel {

    private Parallel() {
    }

    static IntStream range(int count) {
        return IntStream.range(0, count);
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
