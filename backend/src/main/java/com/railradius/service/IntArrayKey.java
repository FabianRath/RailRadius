package com.railradius.service;

import java.util.Arrays;

/** An int array as a map key, compared by content: the browser file stores equal sequences once (#48, #49). */
record IntArrayKey(int[] values) {

    @Override
    public boolean equals(Object other) {
        return other instanceof IntArrayKey key && Arrays.equals(values, key.values);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        return Arrays.toString(values);
    }
}
