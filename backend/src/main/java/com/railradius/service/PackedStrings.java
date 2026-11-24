package com.railradius.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * A list of strings as one UTF-8 byte array plus offsets: two arrays instead of an object per string. String i is
 * {@code blob[offsets[i]]} up to {@code blob[offsets[i + 1]]}. Each {@link #get} decodes a new String.
 */
final class PackedStrings {

    private final byte[] blob;
    private final int[] offsets;

    private PackedStrings(byte[] blob, int[] offsets) {
        this.blob = blob;
        this.offsets = offsets;
    }

    static PackedStrings of(List<String> strings) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int[] offsets = new int[strings.size() + 1];
        for (int i = 0; i < strings.size(); i++) {
            byte[] utf8 = strings.get(i).getBytes(StandardCharsets.UTF_8);
            bytes.write(utf8, 0, utf8.length);
            offsets[i + 1] = bytes.size();
        }
        return new PackedStrings(bytes.toByteArray(), offsets);
    }

    static PackedStrings readFrom(BinaryBlocks.Reader in) throws IOException {
        return new PackedStrings(in.bytes(), in.ints());
    }

    void writeTo(BinaryBlocks.Writer out) throws IOException {
        out.bytes(blob);
        out.ints(offsets);
    }

    int size() {
        return offsets.length - 1;
    }

    String get(int index) {
        return new String(blob, offsets[index], offsets[index + 1] - offsets[index], StandardCharsets.UTF_8);
    }

    /** Whether string {@code index} is {@code utf8}, without decoding it. */
    boolean equalsAt(int index, byte[] utf8) {
        return Arrays.equals(blob, offsets[index], offsets[index + 1], utf8, 0, utf8.length);
    }

    long sizeBytes() {
        return blob.length + (long) offsets.length * Integer.BYTES;
    }
}
