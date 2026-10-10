package com.railreach.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinaryBlocksTest {

    private static final int VERSION = 7;
    // Each block spans several of the 1 MB chunks the file is read and written in
    private static final int LARGE_BLOCK_ELEMENTS = 700_000;
    private static final long SEED = 20;

    @TempDir
    Path tempDir;

    @Test
    void blocksLargerThanOneChunkReadBackAsWritten() throws IOException {
        Random random = new Random(SEED);
        byte[] bytes = new byte[3 * LARGE_BLOCK_ELEMENTS];
        random.nextBytes(bytes);
        int[] ints = random.ints(LARGE_BLOCK_ELEMENTS).toArray();
        long[] longs = random.longs(LARGE_BLOCK_ELEMENTS).toArray();
        byte[] hash = new byte[BinaryBlocks.HASH_BYTES];
        random.nextBytes(hash);
        Path file = tempDir.resolve("blocks.bin");
        try (BinaryBlocks.Writer out = new BinaryBlocks.Writer(file, VERSION, hash)) {
            out.bytes(bytes);
            out.ints(ints);
            out.ints(new int[0]);
            out.longs(longs);
        }

        try (BinaryBlocks.Reader in = new BinaryBlocks.Reader(file, VERSION)) {
            assertThat(in.hash()).isEqualTo(hash);
            assertThat(in.bytes()).isEqualTo(bytes);
            assertThat(in.ints()).isEqualTo(ints);
            assertThat(in.ints()).isEmpty();
            assertThat(in.longs()).isEqualTo(longs);
            in.checkAllRead();
        }
    }

    @Test
    void readingABlockAsTheWrongTypeFails() throws IOException {
        Path file = tempDir.resolve("blocks.bin");
        try (BinaryBlocks.Writer out = new BinaryBlocks.Writer(file, VERSION, new byte[BinaryBlocks.HASH_BYTES])) {
            out.ints(new int[] {1, 2});
        }

        try (BinaryBlocks.Reader in = new BinaryBlocks.Reader(file, VERSION)) {
            assertThatThrownBy(in::longs).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void blocksLeftUnreadFailTheCheck() throws IOException {
        Path file = tempDir.resolve("blocks.bin");
        try (BinaryBlocks.Writer out = new BinaryBlocks.Writer(file, VERSION, new byte[BinaryBlocks.HASH_BYTES])) {
            out.ints(new int[] {1});
            out.ints(new int[] {2});
        }

        try (BinaryBlocks.Reader in = new BinaryBlocks.Reader(file, VERSION)) {
            in.ints();
            assertThatThrownBy(in::checkAllRead).isInstanceOf(IllegalStateException.class);
        }
    }
}
