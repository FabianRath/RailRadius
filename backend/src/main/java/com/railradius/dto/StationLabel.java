package com.railradius.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A station the map names (#96), ranked by importance (its edges): from the browser engine, or from
 * {@code GET /api/station-labels} where the engine doesn't run (#108).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StationLabel {
    private String name;
    private double latitude;
    private double longitude;
    private int importance;
}
