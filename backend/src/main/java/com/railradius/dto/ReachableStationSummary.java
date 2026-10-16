package com.railradius.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A reachable station as the stream sends it: enough to draw it on the map. Its journey (path, legs, running days)
 * is fetched when the station is clicked, as a {@link ReachableStation}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReachableStationSummary {
    private String stopId;
    private String stopName;
    private String country;
    private Double latitude;
    private Double longitude;
    private Integer transfers;
    private String totalDuration;
    private Integer totalDurationMinutes;
}
