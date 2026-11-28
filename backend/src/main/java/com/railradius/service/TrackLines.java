package com.railradius.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.railradius.dto.JourneyLeg;
import com.railradius.dto.ReachableStation;
import com.railradius.model.Station;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The lines along the tracks that a journey's trains ride (#110): for each TRAIN leg one line per pair of consecutive
 * stops, routed on the {@link TrackNetwork} that scripts/rail-tracks.sh builds. Without that file journeys keep
 * their legs as they are, and the map draws straight lines.
 */
@Service
@Slf4j
public class TrackLines {

    // About 5 m: finer than the map shows a journey, and it keeps a long line to a few hundred points
    private static final double SIMPLIFY_TOLERANCE_DEG = 0.00005;
    // 5 decimals, about 1 m
    private static final double COORDINATE_SCALE = 1e5;

    // Null without a track network file
    private final TrackNetwork network;
    private final Cache<StopPair, Optional<float[][]>> lines;

    @Autowired
    public TrackLines(@Value("${railradius.tracks.file:data/rail-tracks.bin}") String file,
                      @Value("${railradius.tracks.cache-size:20000}") int cacheSize) {
        this(load(Path.of(file)), cacheSize);
    }

    TrackLines(TrackNetwork network, int cacheSize) {
        this.network = network;
        this.lines = Caffeine.newBuilder().maximumSize(cacheSize).build();
    }

    private record StopPair(double fromLat, double fromLon, double toLat, double toLon) {
    }

    /**
     * Sets each TRAIN leg's track: one [lon, lat] line per stop pair from the leg's first stop to its last, null for
     * a pair the tracks don't join or a stop without coordinates. Leaves the legs as they are without a network.
     */
    public ReachableStation addTo(ReachableStation station, Function<String, Station> stations) {
        if (network == null) return station;
        List<String> path = station.getPath();
        // A train can pass a station twice, so each leg is looked up after the previous one, as the map does
        int searchFrom = 0;
        for (JourneyLeg leg : station.getLegs()) {
            if (!JourneyLegBuilder.LEG_TRAIN.equals(leg.getType())) continue;
            int from = indexOf(path, leg.getFromStopId(), searchFrom);
            int to = indexOf(path, leg.getToStopId(), from + 1);
            if (from < 0 || to < 0) continue;
            List<float[][]> track = new ArrayList<>();
            for (int i = from; i < to; i++) {
                track.add(line(stations.apply(path.get(i)), stations.apply(path.get(i + 1))));
            }
            leg.setTrack(track);
            searchFrom = to;
        }
        return station;
    }

    private static int indexOf(List<String> path, String stopId, int fromIndex) {
        if (fromIndex < 0) return -1;
        int index = path.subList(fromIndex, path.size()).indexOf(stopId);
        return index < 0 ? -1 : fromIndex + index;
    }

    private float[][] line(Station from, Station to) {
        if (!hasCoordinates(from) || !hasCoordinates(to)) return null;
        StopPair pair = new StopPair(from.getLatitude(), from.getLongitude(), to.getLatitude(), to.getLongitude());
        return lines.get(pair, this::route).orElse(null);
    }

    private static boolean hasCoordinates(Station station) {
        return station != null && station.getLatitude() != null && station.getLongitude() != null;
    }

    /** From the station along the track to the other station, simplified. */
    private Optional<float[][]> route(StopPair pair) {
        return TrackRoutes.route(network, pair.fromLat, pair.fromLon, pair.toLat, pair.toLon).map(vertices -> {
            double[] lons = new double[vertices.length + 2];
            double[] lats = new double[vertices.length + 2];
            lons[0] = pair.fromLon;
            lats[0] = pair.fromLat;
            for (int i = 0; i < vertices.length; i++) {
                lons[i + 1] = network.lon(vertices[i]);
                lats[i + 1] = network.lat(vertices[i]);
            }
            lons[lons.length - 1] = pair.toLon;
            lats[lats.length - 1] = pair.toLat;
            boolean[] keep = simplified(lons, lats);
            List<float[]> points = new ArrayList<>();
            for (int i = 0; i < keep.length; i++) {
                if (keep[i]) points.add(new float[] {rounded(lons[i]), rounded(lats[i])});
            }
            return points.toArray(float[][]::new);
        });
    }

    private static float rounded(double degrees) {
        return (float) (Math.round(degrees * COORDINATE_SCALE) / COORDINATE_SCALE);
    }

    /** Douglas–Peucker: the points to keep so that no dropped one lies farther than the tolerance from the line. */
    private static boolean[] simplified(double[] xs, double[] ys) {
        boolean[] keep = new boolean[xs.length];
        keep[0] = true;
        keep[xs.length - 1] = true;
        Deque<int[]> spans = new ArrayDeque<>();
        spans.push(new int[] {0, xs.length - 1});
        while (!spans.isEmpty()) {
            int[] span = spans.pop();
            int farthest = -1;
            double farthestDeg = SIMPLIFY_TOLERANCE_DEG;
            for (int i = span[0] + 1; i < span[1]; i++) {
                double deg = segmentDistance(xs[i], ys[i], xs[span[0]], ys[span[0]], xs[span[1]], ys[span[1]]);
                if (deg > farthestDeg) {
                    farthestDeg = deg;
                    farthest = i;
                }
            }
            if (farthest < 0) continue;
            keep[farthest] = true;
            spans.push(new int[] {span[0], farthest});
            spans.push(new int[] {farthest, span[1]});
        }
        return keep;
    }

    private static double segmentDistance(double x, double y, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay;
        double lengthSquared = dx * dx + dy * dy;
        double t = lengthSquared == 0 ? 0 : Math.clamp(((x - ax) * dx + (y - ay) * dy) / lengthSquared, 0, 1);
        return Math.hypot(x - (ax + t * dx), y - (ay + t * dy));
    }

    private static TrackNetwork load(Path file) {
        if (!Files.exists(file)) {
            log.info("No track network at {}: journeys are drawn as straight lines", file);
            return null;
        }
        try {
            long startMs = System.currentTimeMillis();
            TrackNetwork network = TrackNetwork.read(file);
            log.info("Track network: {} vertices, {} edges in {} ms", network.vertexCount(), network.edgeCount(),
                    System.currentTimeMillis() - startMs);
            return network;
        } catch (IOException | RuntimeException e) {
            log.error("Could not read the track network {}: journeys are drawn as straight lines", file, e);
            return null;
        }
    }
}
