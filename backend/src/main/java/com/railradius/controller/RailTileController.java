package com.railradius.controller;

import com.railradius.dto.RailTileJson;
import com.railradius.service.RailTileStore;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The rail-only basemap tiles (#93), outside /api: static map data, not part of the public API. Integer path
 * variables, so no request can name a file outside the tile folders.
 */
@RestController
@RequestMapping(RailTileStore.TILE_PATH)
@RequiredArgsConstructor
public class RailTileController {

    private static final MediaType VECTOR_TILE = MediaType.parseMediaType("application/vnd.mapbox-vector-tile");

    private final RailTileStore railTileStore;

    /**
     * 204 while no tiles are built: the map then keeps OpenFreeMap's rail layers. Not 404, which the browser would log
     * as an error on every page load of a server without tiles.
     */
    @GetMapping("tiles.json")
    public ResponseEntity<RailTileJson> tileJson() {
        return railTileStore.tileJson().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** 204 for a tile without track inside a built tileset, which MapLibre draws as empty without an error. */
    @GetMapping("{zoom}/{x}/{y}.pbf")
    public ResponseEntity<Resource> tile(@PathVariable int zoom, @PathVariable int x, @PathVariable int y) {
        return railTileStore.tile(zoom, x, y)
                .map(file -> ResponseEntity.ok().contentType(VECTOR_TILE).body((Resource) new FileSystemResource(file)))
                .orElseGet(() -> railTileStore.isBuilt()
                        ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build());
    }
}
