package com.railradius.service;

import com.railradius.dto.ReachableStationSummary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BrowserJsonTest {

    private static final char CONTROL = 1;
    // Spelled out, as Java reads a backslash and u in a literal as the character itself
    private static final String UNICODE_ESCAPE = "\\" + "u";

    @Test
    void summariesAreTheStreamsJsonWithTextEscaped() {
        ReachableStationSummary wien = ReachableStationSummary.builder().stopId("austria:1")
                .stopName("Wien \"Hbf\"\\" + CONTROL).country("AT").latitude(48.18518412345).longitude(16.0)
                .transfers(2).totalDuration("1h 5m").totalDurationMinutes(65).build();
        ReachableStationSummary unknown = ReachableStationSummary.builder().stopId("x:2").build();

        assertThat(BrowserJson.summaries(List.of(wien, unknown))).isEqualTo("[{\"stopId\":\"austria:1\","
                + "\"stopName\":\"Wien \\\"Hbf\\\"\\\\" + UNICODE_ESCAPE + "0001\",\"country\":\"AT\","
                + "\"latitude\":48.18518412345,\"longitude\":16.0,\"transfers\":2,\"totalDuration\":\"1h 5m\","
                + "\"totalDurationMinutes\":65},"
                + "{\"stopId\":\"x:2\",\"stopName\":null,\"country\":null,\"latitude\":null,\"longitude\":null,"
                + "\"transfers\":null,\"totalDuration\":null,\"totalDurationMinutes\":null}]");
    }

    @Test
    void ridesAreTheRideListsJson() {
        assertThat(BrowserJson.rides(List.of(new CompactResult.Ride(7, 10, 12), new CompactResult.Ride(9, 30, 31))))
                .isEqualTo("[{\"trip\":7,\"boardEvent\":10,\"alightEvent\":12},"
                        + "{\"trip\":9,\"boardEvent\":30,\"alightEvent\":31}]");
        assertThat(BrowserJson.summaries(List.of())).isEqualTo("[]");
    }

    @Test
    void stationLabelsCarryNameCoordinatesAndImportance() {
        BrowserJson.StationLabel wien = new BrowserJson.StationLabel("Wien \"Hbf\"", 48.185, 16.375, 42);
        assertThat(BrowserJson.stationLabels(List.of(wien)))
                .isEqualTo("[{\"name\":\"Wien \\\"Hbf\\\"\",\"latitude\":48.185,\"longitude\":16.375,"
                        + "\"importance\":42}]");
        assertThat(BrowserJson.stationLabels(List.of())).isEqualTo("[]");
    }
}
