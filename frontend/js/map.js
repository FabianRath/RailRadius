import { Map, Marker, Popup, LngLatBounds, NavigationControl }
    from '../vendor/maplibre-gl/maplibre-gl.mjs';
import { MAP_STYLE_URL, MAP_CENTER, MAP_ZOOM } from './config.js';
import { cssVar } from './util.js';
import { withLatinLabels } from './map-labels.js';

export const MAX_TRANSFER_LEVEL = 5;
export const LAYERS = { reachable: 'reachable-stations', debug: 'debug-stations' };

const FIT_PADDING_PX = 50;
const FIT_MAX_ZOOM = 12;
const RAILWAY_MIN_ZOOM = 8;
const POPUP_MAX_WIDTH = '360px';
const JOURNEY_LINE_WIDTH_PX = 5;
const JOURNEY_CASING_WIDTH_PX = 9;
// Every station's kernel this wide; above the density threshold the kernels of nearby stations join into one area
const AREA_RADIUS_PX = 30;
const AREA_DENSITY_THRESHOLD = 0.05;
const AREA_OPACITY = 0.45;
// Index = transfer level; direct destinations stand out
const DOT_RADIUS_BY_LEVEL_PX = [6, 5, 4.5, 4, 3.5, 3];
// With the areas shown, the dots shrink so the areas carry the map
const AREA_DOT_SCALE = 0.5;
const DOT_STROKE_WIDTH_PX = 1;
const AREA_DOT_STROKE_WIDTH_PX = 0.5;
// Roads, buildings, towns and water names compete with the coloured dots; land, water, borders, country names and
// cities stay for orientation
const CLUTTER_LAYERS = new RegExp('^(road|highway|aeroway|building|railway_.*dashline|water_name|landuse_residential'
    + '|landcover_wood|landuse_park|place_(other|suburb|village|town|state))');
const LAND_FILL_LAYERS = ['landcover_ice_shelf', 'landcover_glacier'];
const RAIL_LAYERS = ['railway', 'railway_minor', 'railway_transit'];
const BOUNDARY_LAYERS = ['boundary_state', 'boundary_country_z0-4', 'boundary_country_z5-'];
const LABEL_LAYERS = ['place_city', 'place_city_large', 'place_country_other', 'place_country_minor',
    'place_country_major'];
// Station names from our own data (#96), by importance (stations reached without a stop between): the hubs from the
// Europe view, more as the map zooms in; within a zoom the more important name wins a collision
const STATION_LABEL_TIERS = [
    { minImportance: 12, minZoom: 4, sizePx: 13 },
    { minImportance: 8, minZoom: 6, sizePx: 12 },
    { minImportance: 4, minZoom: 8, sizePx: 11 },
    { minImportance: 0, minZoom: 10, sizePx: 11 }
];
const STATION_LABEL_FONT = ['Noto Sans Regular'];
const STATION_LABEL_OFFSET_EM = 0.6;
const STATION_LABEL_HALO_PX = 1.4;
const EMPTY_COLLECTION = { type: 'FeatureCollection', features: [] };

let map;
let reachableFeatures = [];
let reachableRenderPending = false;
let startMarker = null;

/** Adds a button under the zoom buttons, styled like them. */
export function addMapButton(button) {
    const group = document.createElement('div');
    group.className = 'maplibregl-ctrl maplibregl-ctrl-group';
    group.append(button);
    map.addControl({ onAdd: () => group, onRemove: () => group.remove() }, 'top-right');
}

export function initMap(containerId) {
    // The licences require the credit; spelled out, its own (i) toggle can't be mistaken for the about button
    map = new Map({
        container: containerId, center: MAP_CENTER, zoom: MAP_ZOOM,
        attributionControl: { compact: false },
    });
    // Rewritten before the first render, which already fetches the fonts its labels need
    map.setStyle(MAP_STYLE_URL, { transformStyle: (previous, next) => withoutClutter(withLatinLabels(next)) });
    map.addControl(new NavigationControl({ showCompass: false }), 'top-right');
    return new Promise(resolve => map.on('load', () => {
        applyBoardColors();
        addDataLayers();
        resolve();
    }));
}

/** Call after the space around the map changed: its own ResizeObserver catches up only a frame later. */
export function resizeMap() {
    map.resize();
}

export function hasCoordinates(lat, lon) {
    return lat != null && lon != null;
}

function withoutClutter(style) {
    return { ...style, layers: style.layers.filter(layer => !CLUTTER_LAYERS.test(layer.id)) };
}

/** Recolours the base style down to land, water and rail, so the coloured dots carry the map. */
function applyBoardColors() {
    const land = cssVar('--map-land');
    const water = cssVar('--map-water');
    setPaint(['background'], 'background-color', land);
    setPaint(['water'], 'fill-color', water);
    setPaint(['waterway'], 'line-color', water);
    setPaint(LAND_FILL_LAYERS, 'fill-color', land);
    setPaint(RAIL_LAYERS, 'line-color', cssVar('--map-rail'));
    setPaint(BOUNDARY_LAYERS, 'line-color', cssVar('--map-boundary'));
    setPaint(LABEL_LAYERS, 'text-color', cssVar('--map-label'));
    setPaint(LABEL_LAYERS, 'text-halo-color', cssVar('--map-label-halo'));
    emphasizeRailway();
}

/** Skips ids the style doesn't have, so a changed base style only loses its recolouring. */
function setPaint(layerIds, property, value) {
    for (const id of layerIds) {
        if (map.getLayer(id)) map.setPaintProperty(id, property, value);
    }
}

// The dark style draws railways almost invisibly and only from z13. Rail is the point of this app.
function emphasizeRailway() {
    if (!map.getLayer('railway')) return;
    map.setLayerZoomRange('railway', RAILWAY_MIN_ZOOM, 24);
    map.setPaintProperty('railway', 'line-width',
        ['interpolate', ['exponential', 1.3], ['zoom'], RAILWAY_MIN_ZOOM, 0.8, 10, 1.4, 12, 2, 16, 3.5, 20, 7]);
}

/** ['match', level, 0, color0, 1, color1, …, colorOfMaxLevel] */
function colorByLevelExpression() {
    const pairs = [];
    for (let level = 0; level < MAX_TRANSFER_LEVEL; level++) pairs.push(level, cssVar(`--transfer-${level}`));
    return ['match', ['get', 'level'], ...pairs, cssVar(`--transfer-${MAX_TRANSFER_LEVEL}`)];
}

/** ['match', level, 0, radius0, 1, radius1, …, radiusOfMaxLevel] */
function radiusByLevelExpression(scale) {
    const radii = DOT_RADIUS_BY_LEVEL_PX.map(radius => radius * scale);
    const pairs = radii.slice(0, -1).flatMap((radius, level) => [level, radius]);
    return ['match', ['get', 'level'], ...pairs, radii.at(-1)];
}

function addDataLayers() {
    map.addSource('reachable', { type: 'geojson', data: EMPTY_COLLECTION });
    addAreaLayers();


    map.addSource('debug', { type: 'geojson', data: EMPTY_COLLECTION });
    map.addLayer({ id: LAYERS.debug, type: 'circle', source: 'debug',
        paint: { 'circle-color': ['get', 'color'], 'circle-radius': ['get', 'radius'], 'circle-opacity': 0.75 } });

    // Below the stations, so the stops of the journey stay clickable on top of its lines
    map.addSource('journey', { type: 'geojson', data: EMPTY_COLLECTION });
    map.addLayer({ id: 'journey-casing', type: 'line', source: 'journey',
        layout: { 'line-join': 'round', 'line-cap': 'round' },
        paint: { 'line-width': JOURNEY_CASING_WIDTH_PX } });
    map.addLayer({ id: 'journey-lines', type: 'line', source: 'journey',
        layout: { 'line-join': 'round', 'line-cap': 'round' },
        paint: { 'line-width': JOURNEY_LINE_WIDTH_PX } });

    // Under the dots and beside them, so a name never hides a dot. The hubs' tier last: the layer on top is placed
    // first, so its names win collisions with the tiers below
    map.addSource('station-labels', { type: 'geojson', data: EMPTY_COLLECTION });
    for (let index = STATION_LABEL_TIERS.length - 1; index >= 0; index--) addStationLabelTier(index);

    map.addLayer({ id: LAYERS.reachable, type: 'circle', source: 'reachable',
        // Direct connections (level 0) are drawn on top
        layout: { 'circle-sort-key': ['-', MAX_TRANSFER_LEVEL, ['get', 'level']] },
        paint: {
            'circle-radius': radiusByLevelExpression(1),
            'circle-stroke-width': DOT_STROKE_WIDTH_PX
        } });
    applyDataColors();
}

/** Re-reads every map colour from the CSS variables; call after the theme changed. */
export function applyThemeColors() {
    applyBoardColors();
    applyDataColors();
}

function applyDataColors() {
    const colorByLevel = colorByLevelExpression();
    const stroke = cssVar('--marker-stroke');
    setPaint(['journey-casing'], 'line-color', stroke);
    setPaint(['journey-lines'], 'line-color', colorByLevel);
    setPaint([LAYERS.reachable], 'circle-color', colorByLevel);
    setPaint([LAYERS.reachable], 'circle-stroke-color', stroke);
    for (let level = 0; level <= MAX_TRANSFER_LEVEL; level++) {
        setPaint([areaLayerId(level)], 'heatmap-color',
            ['step', ['heatmap-density'], 'transparent', AREA_DENSITY_THRESHOLD, cssVar(`--transfer-${level}`)]);
    }
}

/** Each tier's names from its zoom on; a tier holds only the stations no tier above it named. */
function addStationLabelTier(index) {
    const { minImportance, minZoom, sizePx } = STATION_LABEL_TIERS[index];
    const upper = STATION_LABEL_TIERS[index - 1];
    const importance = ['get', 'importance'];
    const filter = ['all', ['>=', importance, minImportance]];
    if (upper) filter.push(['<', importance, upper.minImportance]);
    map.addLayer({ id: `station-labels-${index}`, type: 'symbol', source: 'station-labels', minzoom: minZoom, filter,
        layout: {
            'text-field': ['get', 'name'], 'text-font': STATION_LABEL_FONT, 'text-size': sizePx,
            'text-anchor': 'left', 'text-offset': [STATION_LABEL_OFFSET_EM, 0],
            'symbol-sort-key': ['-', importance]
        },
        paint: {
            'text-color': cssVar('--map-label'),
            'text-halo-color': cssVar('--map-label-halo'), 'text-halo-width': STATION_LABEL_HALO_PX
        } });
}

/** Names the stations ({ name, latitude, longitude, importance }) on the map. */
export function setStationLabels(labels) {
    const features = labels.map(label => pointFeature(label.longitude, label.latitude,
        { name: label.name, importance: label.importance }));
    map.getSource('station-labels').setData({ type: 'FeatureCollection', features });
}

/**
 * One heatmap per transfer level, cut off at a density step so it reads as a solid area. The GPU redraws it on every
 * frame, so zooming costs nothing. Direct connections (level 0) are drawn on top.
 */
function addAreaLayers() {
    for (let level = MAX_TRANSFER_LEVEL; level >= 0; level--) {
        map.addLayer({ id: areaLayerId(level), type: 'heatmap', source: 'reachable',
            filter: ['==', ['get', 'level'], level],
            layout: { visibility: 'none' },
            paint: {
                'heatmap-radius': AREA_RADIUS_PX,
                'heatmap-opacity': AREA_OPACITY,
            } });
    }
}

function areaLayerId(level) {
    return `reach-area-${level}`;
}

/** Shows or hides the reach areas drawn around the reachable stations, with smaller dots while they are shown. */
export function setAreasVisible(visible) {
    for (let level = 0; level <= MAX_TRANSFER_LEVEL; level++) {
        map.setLayoutProperty(areaLayerId(level), 'visibility', visible ? 'visible' : 'none');
    }
    map.setPaintProperty(LAYERS.reachable, 'circle-radius', radiusByLevelExpression(visible ? AREA_DOT_SCALE : 1));
    map.setPaintProperty(LAYERS.reachable, 'circle-stroke-width',
        visible ? AREA_DOT_STROKE_WIDTH_PX : DOT_STROKE_WIDTH_PX);
}

function pointFeature(lon, lat, properties) {
    return { type: 'Feature', geometry: { type: 'Point', coordinates: [lon, lat] }, properties };
}

/** Adds streamed stations; the source is redrawn at most once per frame however many batches arrive. */
export function addReachableStations(stations) {
    for (const s of stations) {
        if (!hasCoordinates(s.latitude, s.longitude)) continue;
        const level = Math.min(s.transfers, MAX_TRANSFER_LEVEL);
        reachableFeatures.push(pointFeature(s.longitude, s.latitude, { stopId: s.stopId, level }));
    }
    scheduleReachableRender();
}

/**
 * Marks the station a search starts from (latitude, longitude); one without coordinates only removes the old marker.
 * An HTML marker, so it stays above every dot and line drawn on the canvas.
 */
export function setStartStation(station) {
    startMarker?.remove();
    startMarker = null;
    if (!hasCoordinates(station.latitude, station.longitude)) return;
    const element = document.createElement('div');
    element.className = 'start-marker';
    startMarker = new Marker({ element, anchor: 'center' })
        .setLngLat([station.longitude, station.latitude])
        .addTo(map);
}

export function clearReachableStations() {
    clearJourney();
    reachableFeatures = [];
    scheduleReachableRender();
}

function scheduleReachableRender() {
    if (reachableRenderPending) return;
    reachableRenderPending = true;
    requestAnimationFrame(() => {
        reachableRenderPending = false;
        map.getSource('reachable').setData({ type: 'FeatureCollection', features: reachableFeatures });
    });
}

/** Draws the lines ([lon, lat] coordinates and a level for the colour) and hides every station not in stopIds. */
export function showJourney({ stopIds, lines }) {
    const features = lines.map(line => ({ type: 'Feature',
        geometry: { type: 'LineString', coordinates: line.coordinates }, properties: { level: line.level } }));
    map.getSource('journey').setData({ type: 'FeatureCollection', features });
    map.setFilter(LAYERS.reachable, ['in', ['get', 'stopId'], ['literal', stopIds]]);
}

export function clearJourney() {
    map.getSource('journey').setData(EMPTY_COLLECTION);
    map.setFilter(LAYERS.reachable, null);
}

/** Each station needs lat, lon, color, radius; all its fields become feature properties for the popup. */
export function setDebugStations(stations) {
    const features = stations.map(s => pointFeature(s.lon, s.lat, s));
    map.getSource('debug').setData({ type: 'FeatureCollection', features });
}

/** Points are [lon, lat] pairs. */
export function fitToPoints(points) {
    if (points.length === 0) return;
    const bounds = points.reduce((b, p) => b.extend(p), new LngLatBounds(points[0], points[0]));
    map.fitBounds(bounds, { padding: FIT_PADDING_PX, maxZoom: FIT_MAX_ZOOM });
}

/** Moves straight to the bounds ([[west, south], [east, north]]), without the fly animation. */
export function jumpToBounds(bounds) {
    map.fitBounds(bounds, { padding: FIT_PADDING_PX, maxZoom: FIT_MAX_ZOOM, animate: false });
}

/** Shows a popup with buildHtml(featureProperties) when a point of the layer is clicked. */
export function bindPopup(layerId, buildHtml) {
    bindClick(layerId, feature => {
        const popup = new Popup({ maxWidth: POPUP_MAX_WIDTH, className: 'station-popup' })
            .setLngLat(feature.geometry.coordinates)
            .setHTML(buildHtml(feature.properties))
            .addTo(map);
        snapToWholePixels(popup.getElement());
    });
}

/** Calls callback(featureProperties) when a point of the layer is clicked. */
export function onPointClick(layerId, callback) {
    bindClick(layerId, feature => callback(feature.properties));
}

/** Calls callback when the map is clicked anywhere but on a point. */
export function onEmptyMapClick(callback) {
    map.on('click', e => {
        const hits = map.queryRenderedFeatures(e.point, { layers: [LAYERS.reachable, LAYERS.debug] });
        if (hits.length === 0) callback();
    });
}

function bindClick(layerId, onClick) {
    map.on('click', layerId, e => onClick(e.features[0]));
    map.on('mouseenter', layerId, () => { map.getCanvas().style.cursor = 'pointer'; });
    map.on('mouseleave', layerId, () => { map.getCanvas().style.cursor = ''; });
}

/**
 * MapLibre places a popup with translate(-50%, -100%), so a fractional or odd size puts it between pixels and its
 * text renders soft. Growing the content to an even whole-pixel size keeps every anchor on the pixel grid.
 */
function snapToWholePixels(popupElement) {
    const content = popupElement.querySelector('.maplibregl-popup-content');
    const outer = popupElement.getBoundingClientRect();
    const inner = content.getBoundingClientRect();
    content.style.width = `${inner.width + roundUpToEven(outer.width) - outer.width}px`;
    content.style.height = `${inner.height + roundUpToEven(outer.height) - outer.height}px`;
}

function roundUpToEven(px) {
    return Math.ceil(px / 2) * 2;
}
