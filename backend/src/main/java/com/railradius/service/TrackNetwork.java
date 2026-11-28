package com.railradius.service;

import com.railradius.util.Coordinates;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * The OSM running track as a graph (#110): each vertex an OSM node's position, each edge the piece of track between
 * two nodes that follow each other on a way, in both directions. A grid over the vertices finds the track near a
 * station. Written by {@link TrackNetworkExtract} as a {@link BinaryBlocks} file, read by {@link TrackLines}.
 */
final class TrackNetwork {

    static final int FORMAT_VERSION = 1;
    static final double E7 = 1e7;

    private static final double CELL_DEG = 0.01;
    private static final double METERS_PER_DEGREE = 111_320;
    // Offsets that make every cell row and column of the globe non-negative, so a cell key fits an int
    private static final int CELL_ROW_OFFSET = (int) Math.ceil(90 / CELL_DEG);
    private static final int CELL_COLUMNS = (int) Math.ceil(360 / CELL_DEG) + 1;
    private static final int CELL_COLUMN_OFFSET = (int) Math.ceil(180 / CELL_DEG);

    private final int[] latE7;
    private final int[] lonE7;
    // Vertex v's edges are edgeTargets[edgeStart[v] .. edgeStart[v + 1])
    private final int[] edgeStart;
    private final int[] edgeTargets;
    // Grid: the vertices of cell cellKeys[i] are cellVertices[cellStart[i] .. cellStart[i + 1])
    private final int[] cellKeys;
    private final int[] cellStart;
    private final int[] cellVertices;

    private TrackNetwork(int[] latE7, int[] lonE7, int[] edgeStart, int[] edgeTargets) {
        this.latE7 = latE7;
        this.lonE7 = lonE7;
        this.edgeStart = edgeStart;
        this.edgeTargets = edgeTargets;
        long[] keyed = new long[latE7.length];
        for (int v = 0; v < keyed.length; v++) keyed[v] = (long) cellKey(lat(v), lon(v)) << Integer.SIZE | v;
        Arrays.sort(keyed);
        cellVertices = new int[keyed.length];
        int[] keys = new int[keyed.length];
        int[] starts = new int[keyed.length + 1];
        int cells = 0;
        for (int i = 0; i < keyed.length; i++) {
            int key = (int) (keyed[i] >>> Integer.SIZE);
            if (cells == 0 || keys[cells - 1] != key) {
                keys[cells] = key;
                starts[cells++] = i;
            }
            cellVertices[i] = (int) keyed[i];
        }
        starts[cells] = keyed.length;
        cellKeys = Arrays.copyOf(keys, cells);
        cellStart = Arrays.copyOf(starts, cells + 1);
    }

    /** The graph of the vertices' positions and the edges, each one (a << 32 | b) for a track piece between a and b. */
    static TrackNetwork of(int[] latE7, int[] lonE7, long[] edges) {
        int[] degree = new int[latE7.length];
        for (long edge : edges) {
            degree[(int) (edge >>> Integer.SIZE)]++;
            degree[(int) edge]++;
        }
        int[] edgeStart = new int[latE7.length + 1];
        for (int v = 0; v < latE7.length; v++) edgeStart[v + 1] = edgeStart[v] + degree[v];
        int[] filled = Arrays.copyOf(edgeStart, latE7.length);
        int[] targets = new int[edgeStart[latE7.length]];
        for (long edge : edges) {
            int a = (int) (edge >>> Integer.SIZE), b = (int) edge;
            targets[filled[a]++] = b;
            targets[filled[b]++] = a;
        }
        return new TrackNetwork(latE7, lonE7, edgeStart, targets);
    }

    static TrackNetwork read(Path file) throws IOException {
        try (BinaryBlocks.Reader reader = new BinaryBlocks.Reader(file, FORMAT_VERSION)) {
            TrackNetwork network = new TrackNetwork(reader.ints(), reader.ints(), reader.ints(), reader.ints());
            reader.checkAllRead();
            return network;
        }
    }

    /** The hash names what the network was built from. */
    void write(Path file, byte[] hash) throws IOException {
        try (BinaryBlocks.Writer writer = new BinaryBlocks.Writer(file, FORMAT_VERSION, hash)) {
            writer.ints(latE7);
            writer.ints(lonE7);
            writer.ints(edgeStart);
            writer.ints(edgeTargets);
        }
    }

    int vertexCount() {
        return latE7.length;
    }

    int edgeCount() {
        return edgeTargets.length / 2;
    }

    double lat(int vertex) {
        return latE7[vertex] / E7;
    }

    double lon(int vertex) {
        return lonE7[vertex] / E7;
    }

    void forEachNeighbour(int vertex, IntConsumer action) {
        for (int e = edgeStart[vertex]; e < edgeStart[vertex + 1]; e++) action.accept(edgeTargets[e]);
    }

    double distanceMeters(int a, int b) {
        return Coordinates.distanceMeters(lat(a), lon(a), lat(b), lon(b));
    }

    /** Every vertex within radiusMeters of the point. */
    void forEachVertexNear(double lat, double lon, double radiusMeters, IntConsumer action) {
        double latRadiusDeg = radiusMeters / METERS_PER_DEGREE;
        double lonRadiusDeg = latRadiusDeg / Math.max(Math.cos(Math.toRadians(lat)), CELL_DEG);
        int firstRow = cellRow(lat - latRadiusDeg), lastRow = cellRow(lat + latRadiusDeg);
        int firstColumn = cellColumn(lon - lonRadiusDeg), lastColumn = cellColumn(lon + lonRadiusDeg);
        for (int row = firstRow; row <= lastRow; row++) {
            for (int column = firstColumn; column <= lastColumn; column++) {
                int cell = Arrays.binarySearch(cellKeys, row * CELL_COLUMNS + column);
                if (cell < 0) continue;
                for (int i = cellStart[cell]; i < cellStart[cell + 1]; i++) {
                    int v = cellVertices[i];
                    if (Coordinates.distanceMeters(lat, lon, lat(v), lon(v)) <= radiusMeters) action.accept(v);
                }
            }
        }
    }

    private static int cellKey(double lat, double lon) {
        return cellRow(lat) * CELL_COLUMNS + cellColumn(lon);
    }

    private static int cellRow(double lat) {
        return (int) Math.floor(lat / CELL_DEG) + CELL_ROW_OFFSET;
    }

    private static int cellColumn(double lon) {
        return (int) Math.floor(lon / CELL_DEG) + CELL_COLUMN_OFFSET;
    }
}
