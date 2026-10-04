import { openReachabilityStream } from './api.js';
import { addReachableStations, clearReachableStations, fitToPoints, getZoom, hasCoordinates, setCoverage }
    from './map.js';
import { buildCoverage } from './coverage.js';
import { coverageEnabled, readQuery } from './query-form.js';
import { showStatus } from './util.js';

const calculateBtn = document.getElementById('calculateBtn');
const resultsSection = document.getElementById('resultsSection');
const reachableCount = document.getElementById('reachableCount');
const maxTransfersUsed = document.getElementById('maxTransfersUsed');
const loadingOverlay = document.getElementById('loadingOverlay');
const loadingCount = document.getElementById('loadingCount');

// stopId -> station as sent by the stream
const results = new Map();
// Finished queries by cacheKey(); repeating one skips the backend entirely
const localResultsCache = new Map();
let activeStream = null;
// Stations only arrive once the backend search has finished, so until then the overlay shows elapsed time
let elapsedTimer = null;
const ELAPSED_TICK_MS = 1000;

/** getSelectedStation returns { id, name } or null. */
export function initReachability(getSelectedStation) {
    calculateBtn.addEventListener('click', () => {
        if (activeStream) {
            cancelQuery();
        } else if (getSelectedStation()) {
            calculate(getSelectedStation());
        }
    });
}

export function getStation(stopId) {
    return results.get(stopId);
}

export function refreshCoverage() {
    setCoverage(coverageEnabled() ? buildCoverage(results.values(), getZoom()) : null);
}

function cacheKey(stationId, query) {
    return [stationId, query.maxTransfers, query.minTransferMinutes, query.maxTransferMinutes,
        query.maxJourneyHours].join('|');
}

function calculate(station) {
    const query = readQuery();
    const key = cacheKey(station.id, query);
    console.log('Reachability query:', key);

    results.clear();
    clearReachableStations();
    setCoverage(null);
    resultsSection.hidden = false;
    maxTransfersUsed.textContent = query.maxTransfers;

    const cached = localResultsCache.get(key);
    if (cached) {
        addResults(cached);
        fitToResults(cached);
        refreshCoverage();
        showStatus('info', `Loaded ${cached.length.toLocaleString()} stations from cache (instant)`);
        return;
    }
    streamResults(station.id, query, key);
}

function streamResults(stationId, query, key) {
    const params = {
        stationId,
        maxTransfers: query.maxTransfers,
        minTransferMinutes: query.minTransferMinutes,
        maxTransferMinutes: query.maxTransferMinutes
    };
    if (query.maxJourneyHours != null) params.maxJourneyHours = query.maxJourneyHours;

    setLoadingState(true);
    reachableCount.textContent = '…';
    const stream = openReachabilityStream(params);
    activeStream = stream;
    let boundsSet = false;

    stream.addEventListener('stations', e => {
        const batch = JSON.parse(e.data);
        addResults(batch);
        loadingCount.textContent = `${results.size.toLocaleString()} stations found`;
        // Fit once, to the first batch, so the view doesn't jump around while results stream in
        if (!boundsSet) boundsSet = fitToResults(batch);
    });

    stream.addEventListener('done', e => {
        const info = JSON.parse(e.data);
        finishStream();
        reachableCount.textContent = info.total.toLocaleString();
        localResultsCache.set(key, [...results.values()]);
        refreshCoverage();
        console.log('Stream done:', info.total, 'stations in', `${info.calculationTimeMs}ms`,
            info.fromCache ? '(from server cache)' : '');
        showStatus('info', `Found ${info.total.toLocaleString()} reachable stations`
            + (info.fromCache ? ' (instant — server cache)' : ''));
    });

    // Fires for the backend's own 'error' event (message in e.data) and for a lost connection (no data)
    stream.addEventListener('error', e => {
        console.error('SSE error:', e);
        finishStream();
        reachableCount.textContent = results.size > 0 ? results.size.toLocaleString() : '—';
        showStatus('error', e.data ? `Search failed: ${e.data}` : 'Search failed: backend not reachable');
    });
}

function addResults(stations) {
    stations.forEach(s => results.set(s.stopId, s));
    addReachableStations(stations);
    reachableCount.textContent = results.size.toLocaleString();
}

/** Returns whether there was anything to fit to. */
function fitToResults(stations) {
    const points = stations
        .filter(s => hasCoordinates(s.latitude, s.longitude))
        .map(s => [s.longitude, s.latitude]);
    fitToPoints(points);
    return points.length > 0;
}

function finishStream() {
    activeStream?.close();
    activeStream = null;
    setLoadingState(false);
}

function cancelQuery() {
    finishStream();
    const found = results.size;
    reachableCount.textContent = found > 0 ? found.toLocaleString() : '—';
    showStatus('info', found > 0 ? `Cancelled — showing ${found.toLocaleString()} partial results` : 'Cancelled');
}

function setLoadingState(active) {
    calculateBtn.classList.toggle('btn-loading', active);
    loadingOverlay.classList.toggle('visible', active);
    clearInterval(elapsedTimer);
    elapsedTimer = null;
    if (active) startElapsedTimer();
}

function startElapsedTimer() {
    const startMs = Date.now();
    loadingCount.textContent = 'Searching… 0 s';
    elapsedTimer = setInterval(() => {
        if (results.size > 0) return;
        const elapsedSeconds = Math.floor((Date.now() - startMs) / 1000);
        loadingCount.textContent = `Searching… ${elapsedSeconds} s`;
    }, ELAPSED_TICK_MS);
}
