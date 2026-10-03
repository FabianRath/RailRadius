import { MAX_TRANSFER_LEVEL, hasCoordinates } from './map.js';

// Turf 6 is loaded as a classic script (global `turf`), see index.html.
const BUFFER_KM_AT_ZOOM_11 = 0.5;
const BUFFER_ZOOM_REFERENCE = 11;
const CIRCLE_STEPS = 16;

/** Buffer radius halves per zoom level so the merged areas keep a similar on-screen size. */
function bufferRadiusKm(zoom) {
    return Math.pow(2, BUFFER_ZOOM_REFERENCE - zoom) * BUFFER_KM_AT_ZOOM_11;
}

/** Merges a buffer circle around every station into one polygon per transfer level. */
export function buildCoverage(stations, zoom) {
    const radiusKm = bufferRadiusKm(zoom);
    const circlesByLevel = new Map();
    for (const s of stations) {
        if (!hasCoordinates(s.latitude, s.longitude)) continue;
        const level = Math.min(s.transfers, MAX_TRANSFER_LEVEL);
        if (!circlesByLevel.has(level)) circlesByLevel.set(level, []);
        circlesByLevel.get(level).push(
            turf.circle([s.longitude, s.latitude], radiusKm, { steps: CIRCLE_STEPS, units: 'kilometers' }));
    }

    const features = [];
    circlesByLevel.forEach((circles, level) => {
        const merged = circles.reduce(unionOrKeep);
        merged.properties = { level };
        features.push(merged);
    });
    return { type: 'FeatureCollection', features };
}

function unionOrKeep(merged, circle) {
    try {
        return turf.union(merged, circle) ?? merged;
    } catch (e) {
        console.warn('Union failed, skipping one circle:', e);
        return merged;
    }
}
