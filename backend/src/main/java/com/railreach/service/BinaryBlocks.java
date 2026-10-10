package com.railreach.service;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * A file of primitive arrays (S20): the magic {@code RRST}, a format version, a hash of what the file was built from,
 * the block count, then each block as its element size in bytes (1, 4 or 8), its length in elements and the elements,
 * little-endian. Blocks are read back in the order they were written. Both sides go through one reused direct buffer,
 * so reading a block allocates nothing but its array.
 */
final class BinaryBlocks {

    static final int HASH_BYTES = 32;

    private static final byte[] MAGIC = "RRST".getBytes(StandardCharsets.US_ASCII);
    private static final int BLOCK_COUNT_OFFSET = MAGIC.length + Integer.BYTES + HASH_BYTES;
    private static final int HEADER_BYTES = BLOCK_COUNT_OFFSET + Integer.BYTES;
    private static final int BLOCK_HEADER_BYTES = 2 * Integer.BYTES;
    private static final int CHUNK_BYTES = 1 << 20;

    private BinaryBlocks() {
    }

    /** Copies count elements from position from of an array into the chunk, or from the chunk into the array. */
    private interface ChunkCopy {
        void copy(ByteBuffer chunk, int from, int count);
    }

    private static ByteBuffer newChunk() {
        return ByteBuffer.allocateDirect(CHUNK_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Writes the header, then one block per call; {@link #close} fills in the block count. */
    static final class Writer implements AutoCloseable {

        private final FileChannel channel;
        private final ByteBuffer chunk = newChunk();
        private int blockCount;

        Writer(Path file, int formatVersion, byte[] hash) throws IOException {
            if (hash.length != HASH_BYTES) throw new IllegalArgumentException("A hash has " + HASH_BYTES + " bytes");
            channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            chunk.clear();
            chunk.put(MAGIC).putInt(formatVersion).put(hash).putInt(0).flip();
            writeChunk();
        }

        void bytes(byte[] values) throws IOException {
            block(Byte.BYTES, values.length, (chunk, from, count) -> chunk.put(values, from, count));
        }

        void ints(int[] values) throws IOException {
            block(Integer.BYTES, values.length, (chunk, from, count) -> chunk.asIntBuffer().put(values, from, count));
        }

        void longs(long[] values) throws IOException {
            block(Long.BYTES, values.length, (chunk, from, count) -> chunk.asLongBuffer().put(values, from, count));
        }

        private void block(int elementBytes, int length, ChunkCopy copy) throws IOException {
            chunk.clear();
            chunk.putInt(elementBytes).putInt(length).flip();
            writeChunk();
            for (int from = 0; from < length; ) {
                int count = Math.min(length - from, CHUNK_BYTES / elementBytes);
                chunk.clear();
                copy.copy(chunk, from, count);
                // Only a byte copy moves the chunk's position; a view buffer leaves it at 0
                chunk.position(0).limit(count * elementBytes);
                writeChunk();
                from += count;
            }
            blockCount++;
        }

        private void writeChunk() throws IOException {
            while (chunk.hasRemaining()) channel.write(chunk);
        }

        @Override
        public void close() throws IOException {
            try (channel) {
                chunk.clear();
                chunk.putInt(blockCount).flip();
                channel.position(BLOCK_COUNT_OFFSET);
                writeChunk();
            }
        }
    }

    /** Checks the header on opening, then reads one block per call. */
    static final class Reader implements AutoCloseable {

        private final FileChannel channel;
        private final ByteBuffer chunk = newChunk();
        private final byte[] hash = new byte[HASH_BYTES];
        private final int blockCount;
        private int blocksRead;

        /** Fails fast, before reading any block, if the file is no block file or has another format version. */
        Reader(Path file, int formatVersion) throws IOException {
            channel = FileChannel.open(file, StandardOpenOption.READ);
            try {
                readChunk(HEADER_BYTES);
                byte[] magic = new byte[MAGIC.length];
                chunk.get(magic);
                if (!Arrays.equals(magic, MAGIC)) throw new IllegalStateException(file + " is no binary cache file");
                int version = chunk.getInt();
                if (version != formatVersion) {
                    throw new IllegalStateException(String.format(
                            "%s has format version %d, expected %d", file, version, formatVersion));
                }
                chunk.get(hash);
                blockCount = chunk.getInt();
            } catch (IOException | RuntimeException e) {
                channel.close();
                throw e;
            }
        }

        byte[] hash() {
            return hash.clone();
        }

        byte[] bytes() throws IOException {
            byte[] values = new byte[blockLength(Byte.BYTES)];
            read(Byte.BYTES, values.length, (chunk, from, count) -> chunk.get(values, from, count));
            return values;
        }

        int[] ints() throws IOException {
            int[] values = new int[blockLength(Integer.BYTES)];
            read(Integer.BYTES, values.length, (chunk, from, count) -> chunk.asIntBuffer().get(values, from, count));
            return values;
        }

        long[] longs() throws IOException {
            long[] values = new long[blockLength(Long.BYTES)];
            read(Long.BYTES, values.length, (chunk, from, count) -> chunk.asLongBuffer().get(values, from, count));
            return values;
        }

        private int blockLength(int elementBytes) throws IOException {
            if (blocksRead == blockCount) throw new IllegalStateException("No block left of " + blockCount);
            readChunk(BLOCK_HEADER_BYTES);
            int actualBytes = chunk.getInt();
            if (actualBytes != elementBytes) {
                throw new IllegalStateException(String.format("Block %d has %d-byte elements, expected %d",
                        blocksRead, actualBytes, elementBytes));
            }
            blocksRead++;
            return chunk.getInt();
        }

        private void read(int elementBytes, int length, ChunkCopy copy) throws IOException {
            for (int from = 0; from < length; ) {
                int count = Math.min(length - from, CHUNK_BYTES / elementBytes);
                readChunk(count * elementBytes);
                copy.copy(chunk, from, count);
                from += count;
            }
        }

        private void readChunk(int byteCount) throws IOException {
            chunk.clear().limit(byteCount);
            while (chunk.hasRemaining()) {
                if (channel.read(chunk) < 0) throw new EOFException("Binary cache file ends in block " + blocksRead);
            }
            chunk.flip();
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }

        /** Throws if blocks are left: the reader and the writer disagree about the layout. */
        void checkAllRead() {
            if (blocksRead != blockCount) {
                throw new IllegalStateException("Read " + blocksRead + " of " + blockCount + " blocks");
            }
        }
    }
}
