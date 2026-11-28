package com.railradius.service;

/**
 * Reads the fields of one protobuf message in a byte array, in file order, for the OSM extract reader (#110). Only
 * the wire types an .osm.pbf file uses: varints, 64- and 32-bit values and length-delimited fields.
 */
final class ProtobufCursor {

    private static final int VARINT = 0;
    private static final int FIXED64 = 1;
    private static final int LENGTH_DELIMITED = 2;
    private static final int FIXED32 = 5;
    private static final int WIRE_TYPE_BITS = 3;
    private static final int WIRE_TYPE_MASK = 7;

    private final byte[] buffer;
    private final int end;
    private int position;
    private int tag;

    ProtobufCursor(byte[] buffer) {
        this(buffer, 0, buffer.length);
    }

    private ProtobufCursor(byte[] buffer, int offset, int length) {
        this.buffer = buffer;
        this.position = offset;
        this.end = offset + length;
    }

    /** Moves to the next field; false at the end of the message. */
    boolean next() {
        if (position >= end) return false;
        tag = (int) varint();
        return true;
    }

    int field() {
        return tag >>> WIRE_TYPE_BITS;
    }

    boolean hasMore() {
        return position < end;
    }

    long varint() {
        long value = 0;
        for (int shift = 0; ; shift += 7) {
            byte b = buffer[position++];
            value |= (long) (b & 0x7f) << shift;
            if (b >= 0) return value;
        }
    }

    /** A zigzag-encoded signed varint (protobuf's sint32 and sint64). */
    long signedVarint() {
        long raw = varint();
        return (raw >>> 1) ^ -(raw & 1);
    }

    /** The current length-delimited field as a cursor of its own: a nested message or a packed list. */
    ProtobufCursor message() {
        int length = (int) varint();
        ProtobufCursor nested = new ProtobufCursor(buffer, position, length);
        position += length;
        return nested;
    }

    byte[] bytes() {
        int length = (int) varint();
        byte[] value = new byte[length];
        System.arraycopy(buffer, position, value, 0, length);
        position += length;
        return value;
    }

    void skip() {
        switch (tag & WIRE_TYPE_MASK) {
            case VARINT -> varint();
            case FIXED64 -> position += Long.BYTES;
            case LENGTH_DELIMITED -> {
                // Read first: `position += varint()` would add the length to the position from before it was read
                int length = (int) varint();
                position += length;
            }
            case FIXED32 -> position += Integer.BYTES;
            default -> throw new IllegalStateException("Unsupported protobuf wire type " + (tag & WIRE_TYPE_MASK));
        }
    }
}
