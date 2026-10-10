package com.railreach.service;

import java.util.Arrays;

/** A growable list of ints without boxing, for the compact search's per-round lists. */
final class IntList {

    private static final int INITIAL_CAPACITY = 16;

    private int[] values = new int[INITIAL_CAPACITY];
    private int size;

    void add(int value) {
        if (size == values.length) values = Arrays.copyOf(values, size * 2);
        values[size++] = value;
    }

    int get(int index) {
        return values[index];
    }

    void set(int index, int value) {
        values[index] = value;
    }

    int size() {
        return size;
    }

    void clear() {
        size = 0;
    }

    int[] toArray() {
        return Arrays.copyOf(values, size);
    }

    long sizeBytes() {
        return (long) values.length * Integer.BYTES;
    }
}
