package com.railradius.service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Browser stand-in for the backend's BinaryBlocks: the same layout, read from the downloaded file in memory, since a
 * browser has no files. Only the reader, which is all the engine needs.
 */
final class BinaryBlocks {

    static final int HASH_BYTES = 32;

    private static final byte[] MAGIC = "RRST".getBytes(StandardCharsets.US_ASCII);

    private BinaryBlocks() {
    }

    static final class Reader implements AutoCloseable {

        private final ByteBuffer buffer;
        private final byte[] hash = new byte[HASH_BYTES];
        private final int blockCount;
        private int blocksRead;

        Reader(byte[] file, int formatVersion) {
            buffer = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
            byte[] magic = new byte[MAGIC.length];
            buffer.get(magic);
            if (!Arrays.equals(magic, MAGIC)) throw new IllegalStateException("No binary blocks file");
            int version = buffer.getInt();
            if (version != formatVersion) {
                throw new IllegalStateException("Format version " + version + ", expected " + formatVersion);
            }
            buffer.get(hash);
            blockCount = buffer.getInt();
        }

        byte[] hash() {
            return hash.clone();
        }

        byte[] bytes() {
            byte[] values = new byte[blockLength(Byte.BYTES)];
            buffer.get(values);
            return values;
        }

        int[] ints() {
            int[] values = new int[blockLength(Integer.BYTES)];
            // Element by element: TeaVM's views of the buffer need aligned offsets, and blocks are not aligned
            for (int i = 0; i < values.length; i++) values[i] = buffer.getInt();
            return values;
        }

        long[] longs() {
            long[] values = new long[blockLength(Long.BYTES)];
            for (int i = 0; i < values.length; i++) values[i] = buffer.getLong();
            return values;
        }

        private int blockLength(int elementBytes) {
            if (blocksRead == blockCount) throw new IllegalStateException("No block left of " + blockCount);
            int actualBytes = buffer.getInt();
            if (actualBytes != elementBytes) {
                throw new IllegalStateException("Block " + blocksRead + " has " + actualBytes + "-byte elements");
            }
            blocksRead++;
            return buffer.getInt();
        }

        @Override
        public void close() {
        }

        void checkAllRead() {
            if (blocksRead != blockCount) {
                throw new IllegalStateException("Read " + blocksRead + " of " + blockCount + " blocks");
            }
        }
    }
}
