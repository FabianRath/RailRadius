package com.railradius.service;

/**
 * Small track networks for the track line tests (#110), east of Vienna. Stations A and C are 7.4 km apart on a
 * west-east line; the track between them bends north through M.
 */
final class TrackTestNetworks {

    static final double A_LAT = 48.00, A_LON = 16.00;
    static final double M_LAT = 48.03, M_LON = 16.05;
    static final double C_LAT = 48.00, C_LON = 16.10;
    // Far north of the line: a track through here is a 110 km detour between A and C
    static final double FAR_LAT = 48.50, FAR_LON = 16.05;

    private TrackTestNetworks() {
    }

    /** A - M - C */
    static TrackNetwork bend() {
        return network(new double[][] {{A_LAT, A_LON}, {M_LAT, M_LON}, {C_LAT, C_LON}}, 0, 1, 1, 2);
    }

    /** A - FAR - C */
    static TrackNetwork farDetour() {
        return network(new double[][] {{A_LAT, A_LON}, {FAR_LAT, FAR_LON}, {C_LAT, C_LON}}, 0, 1, 1, 2);
    }

    /** Vertices as {lat, lon}, then edges as pairs of vertex indices. */
    static TrackNetwork network(double[][] vertices, int... edgeEnds) {
        int[] latE7 = new int[vertices.length];
        int[] lonE7 = new int[vertices.length];
        for (int v = 0; v < vertices.length; v++) {
            latE7[v] = (int) Math.round(vertices[v][0] * TrackNetwork.E7);
            lonE7[v] = (int) Math.round(vertices[v][1] * TrackNetwork.E7);
        }
        long[] edges = new long[edgeEnds.length / 2];
        for (int e = 0; e < edges.length; e++) {
            edges[e] = (long) edgeEnds[2 * e] << Integer.SIZE | edgeEnds[2 * e + 1];
        }
        return TrackNetwork.of(latE7, lonE7, edges);
    }
}
