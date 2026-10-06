import { Map, Popup, LngLatBounds, NavigationControl }
    from 'https://unpkg.com/maplibre-gl@6.11.2/dist/maplibre-gl.mjs';
import { MAP_STYLE_URL, MAP_CENTER, MAP_ZOOM } from './config.js';
import { cssVar } from './util.js';

export const MAX_TRANSFER_LEVEL = 5;
export const LAYERS = { reachable: 'reachable-stations', debug: 'debug-stations' };

const FIT_PADDING_PX = 50;
const FIT_MAX_ZOOM = 12;
const RAILWAY_MIN_ZOOM = 8;
const POPUP_MAX_WIDTH = '360px';
const POPUP_SCREEN_MARGIN_PX = 10;
const EMPTY_COLLECTION = { type: 'FeatureCollection', features: [] };

let map;
let reachableFeatures = [];
let reachableRenderPending = false;

export function initMap(containerId) {
    map = new Map({ container: containerId, style: MAP_STYLE_URL, center: MAP_CENTER, zoom: MAP_ZOOM });
    map.addControl(new NavigationControl({ showCompass: false }), 'top-right');
    return new Promise(resolve => map.on('load', () => {
        emphasizeRailways();
        addDataLayers();
        resolve();
    }));
}

export function getZoom() {
    return map.getZoom();
}

export function onZoomEnd(callback) {
    map.on('zoomend', callback);
}

export function hasCoordinates(lat, lon) {
    return lat != null && lon != null;
}

// The dark style draws railways almost invisibly and only from z13. Rail is the point of this app.
function emphasizeRailways() {
    const railColor = cssVar('--map-rail');
    for (const id of ['railway', 'railway_minor', 'railway_transit']) {
        if (map.getLayer(id)) map.setPaintProperty(id, 'line-color', railColor);
    }
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

function addDataLayers() {
    const colorByLevel = colorByLevelExpression();

    map.addSource('coverage', { type: 'geojson', data: EMPTY_COLLECTION });
    map.addLayer({ id: 'coverage-fill', type: 'fill', source: 'coverage',
        paint: { 'fill-color': colorByLevel, 'fill-opacity': 0.6 } });
    map.addLayer({ id: 'coverage-outline', type: 'line', source: 'coverage',
        paint: { 'line-color': colorByLevel, 'line-opacity': 0.8, 'line-width': 1 } });

    map.addSource('debug', { type: 'geojson', data: EMPTY_COLLECTION });
    map.addLayer({ id: LAYERS.debug, type: 'circle', source: 'debug',
        paint: { 'circle-color': ['get', 'color'], 'circle-radius': ['get', 'radius'], 'circle-opacity': 0.75 } });

    map.addSource('reachable', { type: 'geojson', data: EMPTY_COLLECTION });
    map.addLayer({ id: LAYERS.reachable, type: 'circle', source: 'reachable',
        // Direct connections (level 0) are drawn on top and slightly larger
        layout: { 'circle-sort-key': ['-', MAX_TRANSFER_LEVEL, ['get', 'level']] },
        paint: {
            'circle-color': colorByLevel,
            'circle-radius': ['case', ['==', ['get', 'level'], 0], 5, 3],
            'circle-stroke-color': cssVar('--marker-stroke'),
            'circle-stroke-width': 1
        } });
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

export function clearReachableStations() {
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

/** Each station needs lat, lon, color, radius; all its fields become feature properties for the popup. */
export function setDebugStations(stations) {
    const features = stations.map(s => pointFeature(s.lon, s.lat, s));
    map.getSource('debug').setData({ type: 'FeatureCollection', features });
}

export function setCoverage(featureCollection) {
    map.getSource('coverage').setData(featureCollection ?? EMPTY_COLLECTION);
}

/** Points are [lon, lat] pairs. */
export function fitToPoints(points) {
    if (points.length === 0) return;
    const bounds = points.reduce((b, p) => b.extend(p), new LngLatBounds(points[0], points[0]));
    map.fitBounds(bounds, { padding: FIT_PADDING_PX, maxZoom: FIT_MAX_ZOOM });
}

/** Shows a popup with buildHtml(featureProperties) when a point of the layer is clicked. */
export function bindPopup(layerId, buildHtml) {
    map.on('click', layerId, e => {
        const feature = e.features[0];
        const popup = new Popup({ maxWidth: POPUP_MAX_WIDTH, className: 'station-popup' })
            .setLngLat(feature.geometry.coordinates)
            .setHTML(buildHtml(feature.properties))
            .addTo(map);
        snapToWholePixels(popup.getElement());
        panIntoView(popup.getElement());
    });
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

/**
 * MapLibre only picks the side of the point with more room; it never moves the map. A long journey near the top
 * edge is placed below the point and its bottom is cut off, so pan until the whole popup is visible.
 */
function panIntoView(popupElement) {
    const popupRect = popupElement.getBoundingClientRect();
    const mapRect = map.getContainer().getBoundingClientRect();
    const dx = panToFit(popupRect.left, popupRect.right, mapRect.left, mapRect.right);
    const dy = panToFit(popupRect.top, popupRect.bottom, mapRect.top, mapRect.bottom);
    if (dx !== 0 || dy !== 0) map.panBy([dx, dy]);
}

/** Pixels to pan so [start, end] lies within [min, max] plus margin; if it cannot fit, its start stays visible. */
function panToFit(start, end, min, max) {
    const startOverflow = min + POPUP_SCREEN_MARGIN_PX - start;
    if (startOverflow > 0) return -startOverflow;
    const endOverflow = end - (max - POPUP_SCREEN_MARGIN_PX);
    return Math.min(Math.max(endOverflow, 0), -startOverflow);
}

function roundUpToEven(px) {
    return Math.ceil(px / 2) * 2;
}
