package com.railradius.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The rail-only basemap tiles (#93) as TileJSON 3.0, returned by {@code GET /tiles/rail/tiles.json} while the tiles
 * are built. Without it the map keeps OpenFreeMap's own rail layers.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RailTileJson {

    private String tilejson;
    // Path templates relative to the site, e.g. /tiles/rail/{z}/{x}/{y}.pbf
    private List<String> tiles;
    private int minzoom;
    private int maxzoom;
    // West, south, east, north in degrees: MapLibre requests no tile outside
    private List<Double> bounds;
}
