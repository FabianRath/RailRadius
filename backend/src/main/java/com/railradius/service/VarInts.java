package com.railradius.service;

import java.util.Arrays;

/**
 * Variable-length unsigned numbers, 7 bits per byte with the high bit set on all but the last, and zigzag coding for
 * signed ones (#49): small numbers of either sign take one byte. The browser file codes its times this way.
 */
final class VarInts {

    private static final int PAYLOAD_BITS = 7;
    private static final int PAYLOAD_MASK = (1 << PAYLOAD_BITS) - 1;
    private static final int MORE = 1 << PAYLOAD_BITS;
    private static final int INITIAL_CAPACITY = 64;

    private VarInts() {
    }

    /** 0, -1, 1, -2, … as 0, 1, 2, 3, …: every int, as a non-negative long. */
    static long zigzag(int value) {
        long wide = value;
        return wide << 1 ^ wide >> (Long.SIZE - 1);
    }

    static int unzigzag(long code) {
        return (int) (code >>> 1 ^ -(code & 1));
    }

    /** A growing byte array of numbers. */
    static final class Writer {

        private byte[] bytes = new byte[INITIAL_CAPACITY];
        private int size;

        /** Adds a non-negative number. */
        void add(long value) {
            if (value < 0) throw new IllegalArgumentException("Negative: " + value);
            long rest = value;
            while (rest >= MORE) {
                append((byte) (rest & PAYLOAD_MASK | MORE));
                rest >>>= PAYLOAD_BITS;
            }
            append((byte) rest);
        }

        byte[] toArray() {
            return Arrays.copyOf(bytes, size);
        }

        private void append(byte value) {
            if (size == bytes.length) bytes = Arrays.copyOf(bytes, size * 2);
            bytes[size++] = value;
        }
    }

    /** Reads what a {@link Writer} wrote, in order. */
    static final class Reader {

        private final byte[] bytes;
        private int position;

        Reader(byte[] bytes) {
            this.bytes = bytes;
        }

        boolean hasNext() {
            return position < bytes.length;
        }

        long next() {
            long value = 0;
            int shift = 0;
            byte current;
            do {
                if (position == bytes.length) throw new IllegalStateException("Numbers end inside one");
                current = bytes[position++];
                value |= (long) (current & PAYLOAD_MASK) << shift;
                shift += PAYLOAD_BITS;
            } while ((current & MORE) != 0);
            return value;
        }

        /** Throws unless every number was read. */
        void checkAllRead() {
            if (position != bytes.length) throw new IllegalStateException((bytes.length - position) + " bytes left");
        }
    }
}
