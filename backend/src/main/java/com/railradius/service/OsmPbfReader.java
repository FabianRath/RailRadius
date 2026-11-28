package com.railradius.service;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reads the ways and nodes of an OpenStreetMap .osm.pbf file for the track network (#110): the file's blocks one by
 * one, raw or zlib-compressed. Nodes only in the dense form, which is all Geofabrik's extracts hold.
 */
final class OsmPbfReader {

    /** Sees a way that has the required key, with its node IDs in order and all its tags. */
    interface WayVisitor {
        void way(long[] nodeIds, Map<String, String> tags);
    }

    /** Sees a node's ID and position in 1e-7 degrees, OSM's own precision. */
    interface NodeVisitor {
        void node(long id, int latE7, int lonE7);
    }

    private static final String DATA_BLOCK = "OSMData";
    // Header and block fields of the OSM PBF format (fileformat.proto, osmformat.proto)
    private static final int HEADER_TYPE = 1, HEADER_DATA_SIZE = 3;
    private static final int BLOB_RAW = 1, BLOB_RAW_SIZE = 2, BLOB_ZLIB = 3;
    private static final int BLOCK_STRINGS = 1, BLOCK_GROUP = 2, BLOCK_GRANULARITY = 17;
    private static final int BLOCK_LAT_OFFSET = 19, BLOCK_LON_OFFSET = 20;
    private static final int STRING = 1;
    private static final int GROUP_DENSE = 2, GROUP_WAY = 3;
    private static final int DENSE_ID = 1, DENSE_LAT = 8, DENSE_LON = 9;
    private static final int WAY_KEYS = 2, WAY_VALUES = 3, WAY_REFS = 8;
    private static final int DEFAULT_GRANULARITY_NANODEG = 100;
    private static final int NANODEG_PER_E7 = 100;

    private OsmPbfReader() {
    }

    static void readWays(Path file, String requiredKey, WayVisitor visitor) throws IOException {
        byte[] key = requiredKey.getBytes(StandardCharsets.UTF_8);
        eachBlock(file, block -> {
            int keyIndex = block.indexOf(key);
            if (keyIndex < 0) return;
            for (ProtobufCursor group : block.groups()) {
                while (group.next()) {
                    if (group.field() == GROUP_WAY) {
                        way(block, group.message(), keyIndex, visitor);
                    } else {
                        group.skip();
                    }
                }
            }
        });
    }

    static void readNodes(Path file, NodeVisitor visitor) throws IOException {
        eachBlock(file, block -> {
            for (ProtobufCursor group : block.groups()) {
                while (group.next()) {
                    if (group.field() == GROUP_DENSE) {
                        denseNodes(block, group.message(), visitor);
                    } else {
                        group.skip();
                    }
                }
            }
        });
    }

    private static void way(Block block, ProtobufCursor way, int keyIndex, WayVisitor visitor) {
        long[] keys = {}, values = {}, refs = {};
        while (way.next()) {
            switch (way.field()) {
                case WAY_KEYS -> keys = varints(way.message(), false);
                case WAY_VALUES -> values = varints(way.message(), false);
                case WAY_REFS -> refs = varints(way.message(), true);
                default -> way.skip();
            }
        }
        if (Arrays.stream(keys).noneMatch(k -> k == keyIndex)) return;
        Map<String, String> tags = new HashMap<>();
        for (int i = 0; i < keys.length; i++) tags.put(block.string(keys[i]), block.string(values[i]));
        visitor.way(refs, tags);
    }

    private static void denseNodes(Block block, ProtobufCursor dense, NodeVisitor visitor) {
        ProtobufCursor ids = null, lats = null, lons = null;
        while (dense.next()) {
            switch (dense.field()) {
                case DENSE_ID -> ids = dense.message();
                case DENSE_LAT -> lats = dense.message();
                case DENSE_LON -> lons = dense.message();
                default -> dense.skip();
            }
        }
        if (ids == null || lats == null || lons == null) return;
        long id = 0, lat = 0, lon = 0;
        while (ids.hasMore()) {
            id += ids.signedVarint();
            lat += lats.signedVarint();
            lon += lons.signedVarint();
            visitor.node(id, block.e7(lat, block.latOffsetNanodeg), block.e7(lon, block.lonOffsetNanodeg));
        }
    }

    /** A packed list; delta-coded ones are signed and each value adds to the one before. */
    private static long[] varints(ProtobufCursor packed, boolean deltaCoded) {
        long[] values = new long[16];
        int count = 0;
        long previous = 0;
        while (packed.hasMore()) {
            if (count == values.length) values = Arrays.copyOf(values, count * 2);
            long value = deltaCoded ? previous + packed.signedVarint() : packed.varint();
            values[count++] = value;
            previous = value;
        }
        return Arrays.copyOf(values, count);
    }

    private static void eachBlock(Path file, Consumer<Block> action) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            while (true) {
                int headerLength;
                try {
                    headerLength = in.readInt();
                } catch (EOFException end) {
                    return;
                }
                ProtobufCursor header = new ProtobufCursor(in.readNBytes(headerLength));
                String type = "";
                int dataSize = 0;
                while (header.next()) {
                    switch (header.field()) {
                        case HEADER_TYPE -> type = new String(header.bytes(), StandardCharsets.UTF_8);
                        case HEADER_DATA_SIZE -> dataSize = (int) header.varint();
                        default -> header.skip();
                    }
                }
                byte[] blob = in.readNBytes(dataSize);
                if (DATA_BLOCK.equals(type)) action.accept(Block.parse(blobData(blob)));
            }
        }
    }

    private static byte[] blobData(byte[] blob) throws IOException {
        ProtobufCursor cursor = new ProtobufCursor(blob);
        int rawSize = 0;
        byte[] zlib = null;
        while (cursor.next()) {
            switch (cursor.field()) {
                case BLOB_RAW -> {
                    return cursor.bytes();
                }
                case BLOB_RAW_SIZE -> rawSize = (int) cursor.varint();
                case BLOB_ZLIB -> zlib = cursor.bytes();
                default -> cursor.skip();
            }
        }
        if (zlib == null) throw new IOException("A block is neither raw nor zlib-compressed");
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(zlib);
            byte[] data = new byte[rawSize];
            int length = inflater.inflate(data);
            if (length != rawSize) throw new IOException("A block inflates to " + length + " of " + rawSize + " bytes");
            return data;
        } catch (DataFormatException e) {
            throw new IOException("A block is no valid zlib data", e);
        } finally {
            inflater.end();
        }
    }

    /** One primitive block: its string table, position offsets and groups, read before the groups are visited. */
    private record Block(byte[][] strings, List<ProtobufCursor> groups, long granularityNanodeg,
                         long latOffsetNanodeg, long lonOffsetNanodeg) {

        static Block parse(byte[] data) {
            ProtobufCursor cursor = new ProtobufCursor(data);
            List<byte[]> strings = new ArrayList<>();
            List<ProtobufCursor> groups = new ArrayList<>();
            long granularity = DEFAULT_GRANULARITY_NANODEG, latOffset = 0, lonOffset = 0;
            while (cursor.next()) {
                switch (cursor.field()) {
                    case BLOCK_STRINGS -> {
                        ProtobufCursor table = cursor.message();
                        while (table.next()) {
                            if (table.field() == STRING) strings.add(table.bytes()); else table.skip();
                        }
                    }
                    case BLOCK_GROUP -> groups.add(cursor.message());
                    case BLOCK_GRANULARITY -> granularity = cursor.varint();
                    case BLOCK_LAT_OFFSET -> latOffset = cursor.varint();
                    case BLOCK_LON_OFFSET -> lonOffset = cursor.varint();
                    default -> cursor.skip();
                }
            }
            return new Block(strings.toArray(byte[][]::new), groups, granularity, latOffset, lonOffset);
        }

        int indexOf(byte[] string) {
            for (int i = 0; i < strings.length; i++) {
                if (Arrays.equals(strings[i], string)) return i;
            }
            return -1;
        }

        String string(long index) {
            return new String(strings[(int) index], StandardCharsets.UTF_8);
        }

        int e7(long coded, long offsetNanodeg) {
            return (int) ((offsetNanodeg + granularityNanodeg * coded) / NANODEG_PER_E7);
        }
    }
}
