// The rail-only basemap tiles (#93), as the backend describes them in TileJSON. DOM-free, so node --test can import it.

/**
 * The MapLibre vector source for the tiles. MapLibre needs absolute tile URLs and the backend gives site paths; joined
 * as text, since new URL() would escape the {z}/{x}/{y} placeholders.
 */
export function railTileSource(tileJson, origin) {
    return {
        type: 'vector',
        tiles: tileJson.tiles.map(path => origin + path),
        minzoom: tileJson.minzoom,
        maxzoom: tileJson.maxzoom,
        bounds: tileJson.bounds
    };
}
