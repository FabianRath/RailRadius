package com.railradius.service;

import com.railradius.dto.JourneyLeg;
import com.railradius.dto.ReachableStation;
import com.railradius.model.Station;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.railradius.service.TrackTestNetworks.A_LAT;
import static com.railradius.service.TrackTestNetworks.A_LON;
import static com.railradius.service.TrackTestNetworks.C_LAT;
import static com.railradius.service.TrackTestNetworks.C_LON;
import static com.railradius.service.TrackTestNetworks.M_LAT;
import static com.railradius.service.TrackTestNetworks.M_LON;
import static org.assertj.core.api.Assertions.assertThat;

class TrackLinesTest {

    private static final Map<String, Station> STATIONS = Map.of(
            "t:A", station("t:A", A_LAT, A_LON),
            "t:C", station("t:C", C_LAT, C_LON),
            "t:X", station("t:X", 49.0, 17.0),
            "t:N", Station.builder().id("t:N").name("No coordinates").build());

    @Test
    void eachStopPairOfATrainLegGetsItsLineAlongTheTracks() {
        ReachableStation journey = journey(List.of("t:A", "t:C", "t:X"), train("t:A", "t:X"));

        new TrackLines(TrackTestNetworks.bend(), 10).addTo(journey, STATIONS::get);

        List<float[][]> track = journey.getLegs().getFirst().getTrack();
        assertThat(track).hasSize(2);
        assertThat(track.get(0))
                .isDeepEqualTo(new float[][] {point(A_LON, A_LAT), point(M_LON, M_LAT), point(C_LON, C_LAT)});
        assertThat(track.get(1)).as("no track near X").isNull();
    }

    @Test
    void aStopWithoutCoordinatesHasNoLine() {
        ReachableStation journey = journey(List.of("t:A", "t:N"), train("t:A", "t:N"));

        new TrackLines(TrackTestNetworks.bend(), 10).addTo(journey, STATIONS::get);

        assertThat(journey.getLegs().getFirst().getTrack()).containsExactly((float[][]) null);
    }

    @Test
    void eachTrainLegGetsTheStopsOfItsOwnRideAndTransfersNone() {
        JourneyLeg transfer = JourneyLeg.builder().type("TRANSFER").fromStopId("t:C").toStopId("t:C").build();
        ReachableStation journey = journey(List.of("t:A", "t:C", "t:A"),
                train("t:A", "t:C"), transfer, train("t:C", "t:A"));

        new TrackLines(TrackTestNetworks.bend(), 10).addTo(journey, STATIONS::get);

        assertThat(journey.getLegs().get(0).getTrack().getFirst()[0]).isEqualTo(point(A_LON, A_LAT));
        assertThat(journey.getLegs().get(1).getTrack()).isNull();
        assertThat(journey.getLegs().get(2).getTrack()).hasSize(1);
        assertThat(journey.getLegs().get(2).getTrack().getFirst()[0]).isEqualTo(point(C_LON, C_LAT));
    }

    @Test
    void withoutANetworkTheLegsHaveNoTrack() {
        ReachableStation journey = journey(List.of("t:A", "t:C"), train("t:A", "t:C"));

        GtfsTestFixtures.NO_TRACK_LINES.addTo(journey, STATIONS::get);

        assertThat(journey.getLegs().getFirst().getTrack()).isNull();
    }

    private static ReachableStation journey(List<String> path, JourneyLeg... legs) {
        return ReachableStation.builder().path(path).legs(List.of(legs)).build();
    }

    private static JourneyLeg train(String fromStopId, String toStopId) {
        return JourneyLeg.builder().type(JourneyLegBuilder.LEG_TRAIN).fromStopId(fromStopId).toStopId(toStopId).build();
    }

    private static Station station(String id, double lat, double lon) {
        return Station.builder().id(id).name(id).latitude(lat).longitude(lon).build();
    }

    private static float[] point(double lon, double lat) {
        return new float[] {(float) lon, (float) lat};
    }
}
