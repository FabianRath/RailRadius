package com.railreach.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.OptionalInt;

/**
 * Station IDs by dense index, and the index of an ID, without a String or map entry per station. The lookup is an
 * open-addressing table with linear probing: a slot holds index + 1, 0 is empty.
 */
final class StationTable {

    // At most half full, so probe runs stay short
    private static final int SLOTS_PER_ID = 2;

    private final PackedStrings ids;
    private final int[] idHash;

    private StationTable(PackedStrings ids, int[] idHash) {
        this.ids = ids;
        this.idHash = idHash;
    }

    /** idsByIndex.get(i) gets index i. The IDs must be distinct. */
    static StationTable of(List<String> idsByIndex) {
        int[] idHash = new int[Integer.highestOneBit(Math.max(1, idsByIndex.size() * SLOTS_PER_ID)) << 1];
        for (int index = 0; index < idsByIndex.size(); index++) {
            int slot = firstSlot(idsByIndex.get(index), idHash.length);
            while (idHash[slot] != 0) slot = (slot + 1) & (idHash.length - 1);
            idHash[slot] = index + 1;
        }
        return new StationTable(PackedStrings.of(idsByIndex), idHash);
    }

    // String.hashCode is fixed by the JLS, so the stored slots hold in every JVM
    static StationTable readFrom(BinaryBlocks.Reader in) throws IOException {
        return new StationTable(PackedStrings.readFrom(in), in.ints());
    }

    void writeTo(BinaryBlocks.Writer out) throws IOException {
        ids.writeTo(out);
        out.ints(idHash);
    }

    int size() {
        return ids.size();
    }

    String id(int index) {
        return ids.get(index);
    }

    /** The station's dense index; empty if the table doesn't have it. */
    OptionalInt indexOf(String id) {
        byte[] utf8 = id.getBytes(StandardCharsets.UTF_8);
        for (int slot = firstSlot(id, idHash.length); idHash[slot] != 0; slot = (slot + 1) & (idHash.length - 1)) {
            int index = idHash[slot] - 1;
            if (ids.equalsAt(index, utf8)) return OptionalInt.of(index);
        }
        return OptionalInt.empty();
    }

    long sizeBytes() {
        return ids.sizeBytes() + (long) idHash.length * Integer.BYTES;
    }

    // Spreads the high bits too: the table index takes only the low ones
    private static int firstSlot(String id, int slotCount) {
        int hash = id.hashCode();
        return (hash ^ (hash >>> 16)) & (slotCount - 1);
    }
}
