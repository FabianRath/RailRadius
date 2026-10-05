// Debug overlay: `scripts/api.sh debug-on` sets a flag in the backend; a browser logged in as dev polls it and then
// shows every station.
import { clearDebugTrigger, fetchDebugStations, fetchDebugStatus } from './api.js';
import { bindPopup, fitToPoints, hasCoordinates, LAYERS, setDebugStations } from './map.js';
import { cssVar, escapeHtml, lerpColor } from './util.js';
import { icon } from './icons.js';

const POLL_INTERVAL_MS = 2000;
const MIN_RADIUS_PX = 2;
const MAX_EXTRA_RADIUS_PX = 3;

const banner = document.getElementById('debugBanner');
const bannerText = document.getElementById('debugBannerText');

let debugActive = false;
let pollTimer = null;

export function initDebugOverlay() {
    document.getElementById('debugClear').addEventListener('click', clearDebugOverlay);
    bindPopup(LAYERS.debug, s =>
        `<div class="popup-station">${escapeHtml(s.name)}</div>`
        + `<div class="popup-meta popup-mono">ID: ${escapeHtml(s.id)}<br>Edges: ${s.edges}</div>`);
}

export function startDebugPolling() {
    if (pollTimer !== null) return;
    pollTimer = setInterval(pollDebugStatus, POLL_INTERVAL_MS);
    pollDebugStatus();
}

/** Stops polling and hides the overlay in this browser only; the backend flag stays as it is. */
export function stopDebugPolling() {
    clearInterval(pollTimer);
    pollTimer = null;
    hideDebugOverlay();
}

async function pollDebugStatus() {
    try {
        const status = await fetchDebugStatus();
        if (pollTimer === null) return; // logged out while the request was running
        if (status.showAllStations && !debugActive) {
            debugActive = true;
            await showDebugStations();
        } else if (!status.showAllStations && debugActive) {
            clearDebugOverlay();
        }
    } catch {
        // Backend may not be up yet; the next poll retries
    }
}

async function showDebugStations() {
    banner.hidden = false;
    bannerText.textContent = 'Fetching stations from backend…';
    try {
        const { stations } = await fetchDebugStations();
        if (!debugActive) return;
        // The backend sorts by edge count, so the first station is the best connected
        const maxEdges = stations[0]?.edges || 1;
        const colors = { low: cssVar('--debug-low'), mid: cssVar('--debug-mid'), high: cssVar('--debug-high') };
        const styled = stations
            .filter(s => hasCoordinates(s.lat, s.lon))
            .map(s => ({ ...s, ...connectivityStyle(Math.min(s.edges / maxEdges, 1), colors) }));
        setDebugStations(styled);
        fitToPoints(styled.map(s => [s.lon, s.lat]));
        bannerText.textContent = `${stations.length.toLocaleString()} stations in memory`
            + ' · colour = connectivity (green→blue→red)';
    } catch (e) {
        bannerText.innerHTML = `${icon('triangle-alert')} Failed to fetch stations: ${escapeHtml(e.message)}`;
        console.error('Debug overlay error:', e);
    }
}

function connectivityStyle(ratio, colors) {
    const color = ratio > 0.5
        ? lerpColor(colors.mid, colors.high, (ratio - 0.5) * 2)
        : lerpColor(colors.low, colors.mid, ratio * 2);
    return { color, radius: MIN_RADIUS_PX + Math.round(ratio * MAX_EXTRA_RADIUS_PX) };
}

function hideDebugOverlay() {
    debugActive = false;
    setDebugStations([]);
    banner.hidden = true;
}

function clearDebugOverlay() {
    hideDebugOverlay();
    clearDebugTrigger().catch(() => {});
}
