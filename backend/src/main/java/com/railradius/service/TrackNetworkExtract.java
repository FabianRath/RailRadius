package com.railradius.service;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.LongStream;

/**
 * Builds the {@link TrackNetwork} file from OSM extracts (#110), run by scripts/rail-tracks.sh. Two passes over each
 * extract: the running track's ways, then the positions of their nodes. OSM node IDs are the same in every extract,
 * so extracts of neighbouring countries join at the border.
 */
@Slf4j
public final class TrackNetworkExtract {

    private static final String RAILWAY = "railway";
    // What the rail tiles draw (scripts/rail-tiles.yml), and also service track (sidings, yards, crossovers), which the
    // tiles leave out: it links a station's platforms to the line, so a train can be followed into the station
    private static final Set<String> RUNNING_TRACK = Set.of("rail", "narrow_gauge");

    private TrackNetworkExtract() {
    }

    /** Arguments: the network file to write, then one or more .osm.pbf extracts. */
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: TrackNetworkExtract <network file> <extract.osm.pbf>...");
        }
        List<Path> extracts = Arrays.stream(args, 1, args.length).map(Path::of).toList();
        long startMs = System.currentTimeMillis();
        TrackNetwork network = extract(extracts);
        network.write(Path.of(args[0]), hash(extracts));
        log.info("Track network: {} vertices, {} edges from {} extracts in {} s", network.vertexCount(),
                network.edgeCount(), extracts.size(), (System.currentTimeMillis() - startMs) / 1000);
    }

    static TrackNetwork extract(List<Path> extracts) throws IOException {
        List<long[]> ways = new ArrayList<>();
        for (Path extract : extracts) {
            OsmPbfReader.readWays(extract, RAILWAY, (nodeIds, tags) -> {
                if (isRunningTrack(tags)) ways.add(nodeIds);
            });
        }
        long[] nodeIds = ways.stream().flatMapToLong(Arrays::stream).sorted().distinct().toArray();
        int[] latE7 = new int[nodeIds.length];
        int[] lonE7 = new int[nodeIds.length];
        boolean[] found = new boolean[nodeIds.length];
        for (Path extract : extracts) {
            OsmPbfReader.readNodes(extract, (id, lat, lon) -> {
                int node = Arrays.binarySearch(nodeIds, id);
                if (node < 0) return;
                latE7[node] = lat;
                lonE7[node] = lon;
                found[node] = true;
            });
        }
        // A way can name a node its extract lacks; such nodes are left out with their edges
        int[] vertexOf = new int[nodeIds.length];
        int vertexCount = 0;
        for (int node = 0; node < nodeIds.length; node++) {
            vertexOf[node] = found[node] ? vertexCount++ : -1;
            if (found[node]) {
                latE7[vertexOf[node]] = latE7[node];
                lonE7[vertexOf[node]] = lonE7[node];
            }
        }
        long[] edges = ways.stream().flatMapToLong(way -> edges(way, nodeIds, vertexOf)).sorted().distinct().toArray();
        log.info("{} running track ways, {} nodes, {} not in the extracts", ways.size(), nodeIds.length,
                nodeIds.length - vertexCount);
        return TrackNetwork.of(Arrays.copyOf(latE7, vertexCount), Arrays.copyOf(lonE7, vertexCount), edges);
    }

    private static boolean isRunningTrack(Map<String, String> tags) {
        return RUNNING_TRACK.contains(tags.get(RAILWAY));
    }

    /** The way's track pieces as (a << 32 | b) with a < b, so the same piece from two extracts is one edge. */
    private static LongStream edges(long[] way, long[] nodeIds, int[] vertexOf) {
        LongStream.Builder edges = LongStream.builder();
        for (int i = 1; i < way.length; i++) {
            int a = vertexOf[Arrays.binarySearch(nodeIds, way[i - 1])];
            int b = vertexOf[Arrays.binarySearch(nodeIds, way[i])];
            if (a < 0 || b < 0 || a == b) continue;
            edges.add((long) Math.min(a, b) << Integer.SIZE | Math.max(a, b));
        }
        return edges.build();
    }

    /** Names the extracts by file name and size, so a network shows which downloads it was built from. */
    private static byte[] hash(List<Path> extracts) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path extract : extracts) {
                String line = extract.getFileName() + ":" + Files.size(extract) + "\n";
                digest.update(line.getBytes(StandardCharsets.UTF_8));
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("The JDK has no SHA-256", e);
        }
    }
}
