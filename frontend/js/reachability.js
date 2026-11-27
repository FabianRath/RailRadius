import { fetchJourney, openReachabilityStream } from './api.js';
import { addReachableStations, clearReachableStations, fitToPoints, hasCoordinates, setStartStation } from './map.js';
import { checkQuery, readQuery } from './query-form.js';
import { requestParamsOf } from './query.js';
import { clearStatus, fmtChanges, showStatus } from './util.js';
import { isUpdatingTimetables } from './server-status.js';
import { dismissIntroHint } from './intro-hint.js';
import { writeDeepLink } from './deep-link.js';
import {
    fetchBrowserJourney, isBrowserDataCached, searchInBrowser, startBrowserEngine, waitForBrowserEngine
} from './browser-engine.js';

const calculateBtn = document.getElementById('calculateBtn');
const reachableCount = document.getElementById('reachableCount');
const maxTransfersUsed = document.getElementById('maxTransfersUsed');
const loadingOverlay = document.getElementById('loadingOverlay');
const loadingCount = document.getElementById('loadingCount');

// stopId -> station as sent by the stream: position, transfers and duration, without the journey
const results = new Map();
// Finished queries by cacheKey(): their stations and whether the browser searched them (#12); repeating one skips
// the search entirely
const localResultsCache = new Map();
// The shown query's stream parameters, whether the browser searched it, and its stations' journeys fetched so far
// (stopId -> station)
let shownParams = null;
let shownInBrowser = false;
let shownStart = null;
let journeys = new Map();
// The running search, in the browser or on the server: { cancel() }
let activeSearch = null;
// Until the first round's stations arrive, the overlay shows elapsed time
let elapsedTimer = null;
const ELAPSED_TICK_MS = 1000;

/** getSelectedStation returns a station search result (id, latitude, longitude, …) or null. */
export function initReachability(getSelectedStation) {
    calculateBtn.addEventListener('click', () => {
        if (activeSearch) {
            cancelQuery();
        } else if (getSelectedStation()) {
            calculate(getSelectedStation());
        }
    });
}

/** A reachable station, or the start station: journeys begin there though the stream never sends it. */
export function getStation(stopId) {
    if (stopId === shownStart?.id) return shownStart;
    return results.get(stopId);
}

/** The station with its journey (path, legs, running days), fetched on first use for the shown query. */
export async function getJourney(stopId) {
    // A new query may start while this one loads; its journey must not land in the new query's map
    const queryJourneys = journeys;
    let journey = queryJourneys.get(stopId);
    if (!journey) {
        journey = await fetchShownJourney(stopId);
        queryJourneys.set(stopId, journey);
    }
    return journey;
}

async function fetchShownJourney(stopId) {
    if (shownInBrowser) {
        try {
            return await fetchBrowserJourney(shownParams, stopId);
        } catch (error) {
            console.warn('Journey of the browser search failed, asking the server:', error);
        }
    }
    return fetchJourney({ ...shownParams, targetId: stopId });
}

function cacheKey(params) {
    return Object.values(params).join('|');
}

/**
 * Runs a search from the station with the form's query, as the GO button does. Answers whether it ran: an invalid
 * form shows its message instead. A search from a shared link runs as soon as the page opens, so it runs in the
 * browser only if the browser's data is already cached, else on the server at once.
 */
export function calculate(station, fromLink = false) {
    if (!checkQuery()) return false;
    const query = readQuery();
    const params = requestParamsOf(station.id, query);
    const key = cacheKey(params);
    console.log('Reachability query:', key);

    clearStatus();
    dismissIntroHint();
    writeDeepLink(station.id, query);
    shownParams = params;
    shownInBrowser = false;
    journeys = new Map();
    results.clear();
    clearReachableStations();
    setStartStation(station);
    shownStart = station;
    maxTransfersUsed.textContent = `max ${fmtChanges(query.maxTransfers)}`;

    const cached = localResultsCache.get(key);
    if (cached) {
        shownInBrowser = cached.inBrowser;
        addResults(cached.stations);
        fitToResults(cached.stations);
        return true;
    }
    setLoadingState(true);
    reachableCount.textContent = '…';
    runSearch(params, key, fromLink);
    return true;
}

/**
 * Searches in the browser if its engine is ready or soon will be (waitForBrowserEngine), else on the server; a
 * browser search that fails is searched again on the server from scratch.
 */
async function runSearch(params, key, fromLink) {
    const search = { cancelled: false, cancel() { this.cancelled = true; } };
    activeSearch = search;
    const inBrowser = (!fromLink || await isBrowserDataCached()) && await waitForBrowserEngine();
    if (search.cancelled) return;
    if (!inBrowser) {
        streamResults(params, key);
        return;
    }
    try {
        const info = await searchInBrowser(params, stations => {
            if (!search.cancelled) addRoundStations(stations);
        });
        if (search.cancelled) return;
        shownInBrowser = true;
        finishSearch(key, info, true);
    } catch (error) {
        if (search.cancelled) return;
        console.warn('Browser search failed, searching on the server:', error);
        results.clear();
        clearReachableStations();
        streamResults(params, key);
    }
}

function streamResults(params, key) {
    const stream = openReachabilityStream(params);
    activeSearch = { cancel: () => stream.close() };

    stream.addEventListener('stations', e => addRoundStations(JSON.parse(e.data)));

    stream.addEventListener('done', e => {
        finishSearch(key, JSON.parse(e.data), false);
        startBrowserEngine();
    });

    // Fires for the backend's own 'error' event (message in e.data) and for a lost connection (no data)
    stream.addEventListener('error', async e => {
        console.error('SSE error:', e);
        endSearch();
        reachableCount.textContent = results.size > 0 ? results.size.toLocaleString() : '—';
        if (await isUpdatingTimetables()) return;
        showStatus('error', e.data ? `Search failed: ${e.data}` : 'Search failed: backend not reachable');
    });
}

function addRoundStations(stations) {
    addResults(stations);
    loadingCount.textContent = `${results.size.toLocaleString()} stations found`;
}

function finishSearch(key, info, inBrowser) {
    endSearch();
    reachableCount.textContent = info.total.toLocaleString();
    const all = [...results.values()];
    localResultsCache.set(key, { stations: all, inBrowser });
    // Fit once at the end, so the view doesn't jump with every round of the search
    fitToResults(all);
    console.log('Search done:', info.total, 'stations in', `${info.calculationTimeMs}ms`,
        inBrowser ? '(in the browser)' : '(on the server)');
}

function addResults(stations) {
    stations.forEach(s => results.set(s.stopId, s));
    addReachableStations(stations);
    reachableCount.textContent = results.size.toLocaleString();
}

function fitToResults(stations) {
    // The start too, so a search that reaches nothing still shows where it started
    const points = [shownStart, ...stations]
        .filter(s => hasCoordinates(s.latitude, s.longitude))
        .map(s => [s.longitude, s.latitude]);
    fitToPoints(points);
}

function endSearch() {
    activeSearch?.cancel();
    activeSearch = null;
    setLoadingState(false);
}

function cancelQuery() {
    endSearch();
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
    loadingCount.textContent = '0 s';
    elapsedTimer = setInterval(() => {
        if (results.size > 0) return;
        const elapsedSeconds = Math.floor((Date.now() - startMs) / 1000);
        loadingCount.textContent = `${elapsedSeconds} s`;
    }, ELAPSED_TICK_MS);
}
