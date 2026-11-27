// PROTOTYPE (branch prototype/redesign, never merged): rail-first basemaps, see prototype.js.
//   1  OpenFreeMap tiles without roads and clutter, OSM rail as the hero
//   2  no OSM rail: our own network from the timetable, with our station names
//   12 our network when zoomed out, OSM tracks once zoomed in
//   R  our own rail-only tiles built from OSM (scripts/prototype-rail-tiles.yml) at every zoom, plus our names
//   L  OSM tracks (the tiles have rail only from z8) plus our station names at every zoom
import { basemap } from './prototype.js';
import { cssVar } from './util.js';

const DROPPED = /^(road|highway|aeroway|building|railway_.*dashline|water_name|landuse_residential|landcover_wood|landuse_park|place_(other|suburb|village|town|state))/;
const OSM_RAIL = /^railway/;
// The OpenFreeMap tiles carry rail only from this zoom
const SWITCH_ZOOM = 8;
const LABEL_FONT = ['Noto Sans Regular'];
const MAX_EDGE_KM = 120;
const KM_PER_DEG = 111;

function lengthKm([[lon1, lat1], [lon2, lat2]]) {
    const dx = (lon2 - lon1) * Math.cos((lat1 + lat2) / 2 * Math.PI / 180);
    return Math.hypot(dx, lat2 - lat1) * KM_PER_DEG;
}

export function prototypeBasemapActive() {
    return basemap.key !== 'off';
}

/** Rewrites the OpenFreeMap style before its first render. */
export function transformBasemapStyle(style) {
    if (!prototypeBasemapActive()) return style;
    const layers = style.layers.filter(layer => {
        if (DROPPED.test(layer.id)) return false;
        if (['2', 'R'].includes(basemap.key) && OSM_RAIL.test(layer.id)) return false;
        if (basemap.key !== '1' && layer.id === 'place_city') return false;
        return true;
    });
    for (const layer of layers) {
        if (!OSM_RAIL.test(layer.id)) continue;
        layer.minzoom = SWITCH_ZOOM;
    }
    return { ...style, layers };
}

/** Recolours what is left and adds our network; call on load, before the result layers. */
export async function applyPrototypeBasemap(map) {
    if (!prototypeBasemapActive()) return;
    const rail = cssVar('--map-rail');
    for (const id of ['railway', 'railway_minor', 'railway_transit']) {
        if (!map.getLayer(id)) continue;
        map.setLayerZoomRange(id, SWITCH_ZOOM, 24);
        map.setPaintProperty(id, 'line-color', rail);
        map.setPaintProperty(id, 'line-width',
            ['interpolate', ['exponential', 1.4], ['zoom'], 5, 0.6, 8, 1.2, 12, 2.4, 16, 4]);
    }
    if (basemap.key === '1') return;

    if (basemap.key === 'R') addRailTiles(map, rail);

    const response = await fetch('/api/prototype/network');
    const network = await response.json();
    // Trains leaving the covered countries have no stops in between: drop those chords
    network.features = network.features.filter(feature => feature.geometry.type === 'Point'
        || lengthKm(feature.geometry.coordinates) <= MAX_EDGE_KM);
    map.addSource('proto-network', { type: 'geojson', data: network });
    const maxzoom = basemap.key === '12' ? SWITCH_ZOOM : 24;
    if (!['L', 'R'].includes(basemap.key)) map.addLayer({
        id: 'proto-network-lines', type: 'line', source: 'proto-network', maxzoom,
        filter: ['==', ['geometry-type'], 'LineString'],
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: {
            'line-color': rail,
            'line-width': ['interpolate', ['linear'], ['zoom'],
                5, ['interpolate', ['linear'], ['get', 'trips'], 1, 0.5, 100, 1.4, 400, 2.4],
                10, ['interpolate', ['linear'], ['get', 'trips'], 1, 1.2, 100, 3, 400, 5]],
        },
    });
    if (!['L', 'R'].includes(basemap.key)) map.addLayer({
        id: 'proto-network-stations', type: 'circle', source: 'proto-network', minzoom: 9,
        filter: ['==', ['geometry-type'], 'Point'],
        paint: {
            'circle-radius': 2.2, 'circle-color': cssVar('--map-land'),
            'circle-stroke-color': rail, 'circle-stroke-width': 1,
        },
    });
    addLabelTier(map, 'hubs', 300, 5, 13);
    addLabelTier(map, 'mid', 80, 8, 12);
    addLabelTier(map, 'all', 0, 10, 11);
}

function addLabelTier(map, name, minTrips, minzoom, sizePx) {
    map.addLayer({
        id: `proto-network-labels-${name}`, type: 'symbol', source: 'proto-network', minzoom,
        filter: ['all', ['==', ['geometry-type'], 'Point'], ['>=', ['get', 'trips'], minTrips]],
        layout: {
            'text-field': ['get', 'name'], 'text-font': LABEL_FONT, 'text-size': sizePx,
            'text-anchor': 'left', 'text-offset': [0.6, 0], 'text-optional': true,
            'symbol-sort-key': ['-', ['get', 'trips']],
        },
        paint: {
            'text-color': cssVar('--map-label'),
            'text-halo-color': cssVar('--map-label-halo'), 'text-halo-width': 1.4,
        },
    });
}

function addRailTiles(map, color) {
    map.addSource('proto-rail', {
        type: 'vector', tiles: [`${location.origin}/prototype-tiles/{z}/{x}/{y}.pbf`], minzoom: 4, maxzoom: 12,
    });
    // Planetiler reads one OSM file per run: each country is its own layer in the merged tiles
    for (const country of ['at', 'hu', 'sk']) map.addLayer({
        id: `proto-rail-${country}`, type: 'line', source: 'proto-rail', 'source-layer': `rail_${country}`,
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: {
            'line-color': color,
            'line-width': ['interpolate', ['exponential', 1.4], ['zoom'],
                4, ['match', ['get', 'rank'], 'main', 0.7, 0.4],
                8, ['match', ['get', 'rank'], 'main', 1.6, 'branch', 1.1, 0.7],
                12, ['match', ['get', 'rank'], 'main', 3, 'branch', 2.2, 1.4],
                16, 4],
        },
    });
}
