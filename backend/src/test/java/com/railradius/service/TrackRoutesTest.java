package com.railradius.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.railradius.service.TrackTestNetworks.A_LAT;
import static com.railradius.service.TrackTestNetworks.A_LON;
import static com.railradius.service.TrackTestNetworks.C_LAT;
import static com.railradius.service.TrackTestNetworks.C_LON;
import static org.assertj.core.api.Assertions.assertThat;

class TrackRoutesTest {

    @TempDir
    Path tempDir;

    @Test
    void theRouteFollowsTheTrackAroundTheBend() {
        assertThat(TrackRoutes.route(TrackTestNetworks.bend(), A_LAT, A_LON, C_LAT, C_LON))
                .hasValue(new int[] {0, 1, 2});
    }

    @Test
    void theRouteRunsInBothDirections() {
        assertThat(TrackRoutes.route(TrackTestNetworks.bend(), C_LAT, C_LON, A_LAT, A_LON))
                .hasValue(new int[] {2, 1, 0});
    }

    @Test
    void aStationWithNoTrackNearbyHasNoRoute() {
        assertThat(TrackRoutes.route(TrackTestNetworks.bend(), A_LAT, A_LON, 49.0, 17.0)).isEmpty();
    }

    @Test
    void aTrackFarLongerThanTheStraightLineIsNoRoute() {
        assertThat(TrackRoutes.route(TrackTestNetworks.farDetour(), A_LAT, A_LON, C_LAT, C_LON)).isEmpty();
    }

    @Test
    void stationsOnTracksThatDontMeetHaveNoRoute() {
        TrackNetwork apart = TrackTestNetworks.network(new double[][] {
                {A_LAT, A_LON}, {A_LAT, A_LON + 0.01}, {C_LAT, C_LON - 0.01}, {C_LAT, C_LON}}, 0, 1, 2, 3);
        assertThat(TrackRoutes.route(apart, A_LAT, A_LON, C_LAT, C_LON)).isEmpty();
    }

    @Test
    void theTrackNextToTheStationWinsOverAShorterRideFromATrackFartherAway() {
        // A track 300 m north of A runs straight to C; the track at A itself bends south first
        TrackNetwork network = TrackTestNetworks.network(new double[][] {
                {A_LAT, A_LON}, {A_LAT - 0.01, A_LON + 0.05}, {C_LAT, C_LON}, {A_LAT + 0.0027, A_LON}},
                0, 1, 1, 2, 3, 2);
        assertThat(TrackRoutes.route(network, A_LAT, A_LON, C_LAT, C_LON)).hasValue(new int[] {0, 1, 2});
    }

    @Test
    void aWrittenNetworkReadsBackWithItsVerticesAndEdges() throws IOException {
        Path file = tempDir.resolve("tracks.bin");
        TrackTestNetworks.bend().write(file, new byte[BinaryBlocks.HASH_BYTES]);

        TrackNetwork read = TrackNetwork.read(file);

        assertThat(read.vertexCount()).isEqualTo(3);
        assertThat(read.edgeCount()).isEqualTo(2);
        assertThat(read.lat(1)).isEqualTo(TrackTestNetworks.M_LAT);
        assertThat(neighbours(read, 1)).containsExactlyInAnyOrder(0, 2);
        assertThat(TrackRoutes.route(read, A_LAT, A_LON, C_LAT, C_LON)).hasValue(new int[] {0, 1, 2});
    }

    private static List<Integer> neighbours(TrackNetwork network, int vertex) {
        List<Integer> neighbours = new ArrayList<>();
        network.forEachNeighbour(vertex, neighbours::add);
        return neighbours;
    }
}
