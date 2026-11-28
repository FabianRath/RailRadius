package com.railradius.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Deflater;

import static org.assertj.core.api.Assertions.assertThat;

class TrackNetworkExtractTest {

    // String table of the test block; index 0 is empty by the format's convention
    private static final List<String> STRINGS = List.of("", "railway", "rail", "highway", "primary", "abandoned");
    private static final int RAILWAY = 1, RAIL = 2, HIGHWAY = 3, PRIMARY = 4, ABANDONED = 5;
    private static final long MISSING_NODE = 99;

    @TempDir
    Path tempDir;

    @Test
    void onlyTheRunningTrackBecomesTheNetwork() throws IOException {
        TrackNetwork network = TrackNetworkExtract.extract(List.of(extract("a.osm.pbf")));

        assertThat(network.vertexCount()).as("nodes 1, 2 and 3 of the rail way").isEqualTo(3);
        assertThat(network.edgeCount()).isEqualTo(2);
        assertThat(network.lat(1)).isEqualTo(48.03);
        assertThat(network.lon(1)).isEqualTo(16.05);
        assertThat(TrackRoutes.route(network, 48.0, 16.0, 48.0, 16.1)).hasValue(new int[] {0, 1, 2});
    }

    @Test
    void trackInTwoExtractsIsOneTrack() throws IOException {
        TrackNetwork network = TrackNetworkExtract.extract(List.of(extract("a.osm.pbf"), extract("b.osm.pbf")));

        assertThat(network.vertexCount()).isEqualTo(3);
        assertThat(network.edgeCount()).isEqualTo(2);
    }

    /**
     * An OSM header block, then one data block: nodes 1-3 on a rail way that bends, nodes 4 and 5 on a road and an
     * abandoned railway, and a rail way to a node the extract lacks.
     */
    private Path extract(String name) throws IOException {
        byte[] nodes = message(2, dense(new long[] {1, 2, 3, 4, 5},
                new long[] {480_000_000, 480_300_000, 480_000_000, 481_000_000, 482_000_000},
                new long[] {160_000_000, 160_500_000, 161_000_000, 160_000_000, 160_000_000}));
        byte[] ways = concat(
                message(3, way(10, RAILWAY, RAIL, 1, 2, 3)),
                message(3, way(11, HIGHWAY, PRIMARY, 3, 4)),
                message(3, way(12, RAILWAY, ABANDONED, 4, 5)),
                message(3, way(13, RAILWAY, RAIL, 3, MISSING_NODE)));
        byte[] strings = concat(STRINGS.stream().map(s -> bytesField(1, s.getBytes(StandardCharsets.UTF_8)))
                .toArray(byte[][]::new));
        byte[] block = concat(message(1, strings), message(2, nodes), message(2, ways));

        ByteArrayOutputStream file = new ByteArrayOutputStream();
        writeBlob(file, "OSMHeader", new byte[0]);
        writeBlob(file, "OSMData", block);
        Path path = tempDir.resolve(name);
        Files.write(path, file.toByteArray());
        return path;
    }

    private static byte[] dense(long[] ids, long[] latsE7, long[] lonsE7) {
        return concat(bytesField(1, deltas(ids)), bytesField(8, deltas(latsE7)), bytesField(9, deltas(lonsE7)));
    }

    private static byte[] way(long id, int key, int value, long... refs) {
        return concat(varintField(1, id), bytesField(2, varint(key)), bytesField(3, varint(value)),
                bytesField(8, deltas(refs)));
    }

    private static void writeBlob(ByteArrayOutputStream file, String type, byte[] data) throws IOException {
        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        byte[] zlib = new byte[data.length + 64];
        int zlibLength = deflater.deflate(zlib);
        deflater.end();
        byte[] blob = concat(varintField(2, data.length), bytesField(3, Arrays.copyOf(zlib, zlibLength)));
        byte[] header = concat(bytesField(1, type.getBytes(StandardCharsets.UTF_8)), varintField(3, blob.length));
        DataOutputStream out = new DataOutputStream(file);
        out.writeInt(header.length);
        out.write(header);
        out.write(blob);
    }

    /** A packed list of signed, delta-coded values. */
    private static byte[] deltas(long[] values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long previous = 0;
        for (long value : values) {
            long delta = value - previous;
            out.writeBytes(varint((delta << 1) ^ (delta >> 63)));
            previous = value;
        }
        return out.toByteArray();
    }

    private static byte[] message(int field, byte[] content) {
        return bytesField(field, content);
    }

    private static byte[] bytesField(int field, byte[] content) {
        return concat(varint((long) field << 3 | 2), varint(content.length), content);
    }

    private static byte[] varintField(int field, long value) {
        return concat(varint((long) field << 3), varint(value));
    }

    private static byte[] varint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while ((value & ~0x7fL) != 0) {
            out.write((int) (value & 0x7f) | 0x80);
            value >>>= 7;
        }
        out.write((int) value);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }
}
