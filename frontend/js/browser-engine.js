// Searching in the browser (#12): owns the engine's worker (js/engine-worker.js) and the data version it has loaded.
// Every failure leaves the search to the server: the caller catches it and streams instead.
import { countBrowserSearch, fetchBrowserData, fetchRideJourney, fetchStationLabels } from './api.js';
import {
    ENGINE_SEARCH_TIMEOUT_MS, engineWaitMs, matchesServer, mayRunEngine, maxJourneyMinutesOf, timeLimited
} from './engine-rules.js';

// The data version this browser last loaded, so its file is in the HTTP cache
const LOADED_VERSION_KEY = 'railradius.browserDataVersion';

let worker = null;
// What the worker searches with ({ version, engineVersion, today }, named like /api/browser-data); null until it is
// loaded, while a new one loads, and after a failure
let loaded = null;
let loading = null;
let loadStartedMs = null;
// After an engine failure this visit searches on the server only
let failed = false;
let nextRequestId = 1;
// Gets the stations the map names, once per loaded data file (#96), or from the server without an engine (#108)
let onStationLabels = () => {};
let labelsShown = false;
// Requests sent to the worker and not answered yet: id -> { resolve, reject, onStations }
const pending = new Map();

/**
 * Starts loading the engine and the server's data in the background, if this browser may search on its own and
 * nothing is loaded or loading yet. Called once the map has loaded, so the first view keeps the bandwidth, and after
 * each search the server answered, which retries a load that failed (e.g. while the server built its file).
 */
export function startBrowserEngine() {
    if (!mayRunEngine(thisBrowser())) {
        showServerStationLabels();
        return;
    }
    if (failed || loading || loaded) return;
    loadStartedMs = Date.now();
    loading = loadData()
        .catch(error => {
            console.warn('The browser engine did not load, searching on the server:', error);
            showServerStationLabels();
        })
        .finally(() => {
            loading = null;
        });
}

/** Hands each loaded data file's station labels ({ name, latitude, longitude, importance }) to the callback. */
export function onBrowserStationLabels(callback) {
    onStationLabels = callback;
}

/**
 * Whether the next search can run in the browser: true once the engine is ready, waiting for a running load until
 * engineWaitMs says the server should answer instead (the load goes on for later searches).
 */
export async function waitForBrowserEngine() {
    if (loading) {
        const waitMs = engineWaitMs(loadStartedMs, Date.now());
        await Promise.race([loading, new Promise(resolve => setTimeout(resolve, waitMs))]);
    }
    return loaded !== null;
}

/**
 * Whether this browser loaded the server's current data file before, so it is in the HTTP cache (for a year) and
 * loading it needs no download. Fetch's only-if-cached can't tell: WebKit ignores it and downloads the file. If the
 * cache was cleared after all, the search still waits at most until engineWaitMs runs out.
 */
export async function isBrowserDataCached() {
    if (failed || !mayRunEngine(thisBrowser())) return false;
    try {
        const { version } = await fetchBrowserData();
        return localStorage.getItem(LOADED_VERSION_KEY) === version;
    } catch {
        return false;
    }
}

/**
 * Searches in the browser and hands each round's new stations to onStations, like the server's stream. Fails if the
 * server's data, engine or today changed since the load (the new ones then load in the background) or the engine
 * fails.
 */
export async function searchInBrowser(params, onStations) {
    await checkMatchesServer();
    const startMs = Date.now();
    const { total } = await callEngine({ type: 'search', options: optionsOf(params) }, onStations,
        ENGINE_SEARCH_TIMEOUT_MS);
    countBrowserSearch().catch(error => console.warn('Could not count the browser search:', error));
    return { total, calculationTimeMs: Date.now() - startMs };
}

/** A station's journey for a browser search: the engine's rides of its path, built into legs by the server. */
export async function fetchBrowserJourney(params, stationId) {
    const version = loaded?.version;
    if (!version) throw new Error('The browser engine has no data loaded');
    // Searches again if another search ran since, so it gets the same limit
    const rides = await callEngine({ type: 'rides', options: optionsOf(params), stationId }, undefined,
        ENGINE_SEARCH_TIMEOUT_MS);
    return fetchRideJourney({
        version,
        minTransferMinutes: params.minTransferMinutes,
        maxTransferMinutes: params.maxTransferMinutes,
        rides
    });
}

function thisBrowser() {
    return {
        mobile: navigator.userAgentData?.mobile ?? false,
        finePointer: matchMedia('(pointer: fine)').matches,
        deviceMemoryGb: navigator.deviceMemory,
        hasWorker: typeof Worker !== 'undefined',
        hasWebAssembly: typeof WebAssembly !== 'undefined'
    };
}

async function loadData() {
    const server = await fetchBrowserData();
    if (!worker) worker = startWorker();
    const { engineVersion } = await callEngine({ type: 'load', path: server.path, today: server.today });
    const candidate = { version: server.version, engineVersion, today: server.today };
    if (!matchesServer(candidate, server)) {
        // The page holds another engine than the server names (a deploy since it loaded): a new worker fetches it
        stopWorker();
        throw new Error(`The browser engine ${engineVersion} is not the server's ${server.engineVersion}`);
    }
    loaded = candidate;
    rememberLoadedVersion(server.version);
    console.log('Searches now run in the browser, data version', server.version);
    onStationLabels(await callEngine({ type: 'stationLabels' }, undefined, ENGINE_SEARCH_TIMEOUT_MS));
    labelsShown = true;
}

async function checkMatchesServer() {
    if (!loaded) throw new Error('The browser engine has no data loaded');
    const server = await fetchBrowserData();
    if (matchesServer(loaded, server)) return;
    if (loaded.engineVersion !== server.engineVersion) stopWorker();
    loaded = null;
    startBrowserEngine();
    throw new Error(`The server now searches with data ${server.version}, engine ${server.engineVersion}, `
        + `today ${server.today}`);
}

// Storage can be blocked (private windows); the next link then simply searches on the server
function rememberLoadedVersion(version) {
    try {
        localStorage.setItem(LOADED_VERSION_KEY, version);
    } catch (error) {
        console.warn('Could not note the loaded data version:', error);
    }
}

function optionsOf(params) {
    return {
        stationId: params.stationId,
        maxTransfers: params.maxTransfers,
        minTransferMinutes: params.minTransferMinutes,
        maxTransferMinutes: params.maxTransferMinutes,
        maxJourneyMinutes: maxJourneyMinutesOf(params)
    };
}

function startWorker() {
    const started = new Worker(new URL('./engine-worker.js', import.meta.url), { type: 'module' });
    started.onmessage = onWorkerMessage;
    // The engine failed to load (not built, no Wasm GC) or the worker crashed, e.g. out of memory
    started.onerror = event => giveUp(new Error(event.message || 'The browser engine stopped'));
    return started;
}

/**
 * An engine failure (out of memory, an error in the search, no answer within timeoutMs) gives the rest of the visit
 * to the server; a worker stopped on purpose (stopWorker) is no failure. Without timeoutMs it waits as long as it
 * takes: a load's download depends on the connection.
 */
async function callEngine(request, onStations = () => {}, timeoutMs = null) {
    const caller = worker;
    if (!caller) throw new Error('The browser engine is not running');
    const id = nextRequestId++;
    const answer = new Promise((resolve, reject) => {
        pending.set(id, { resolve, reject, onStations });
        caller.postMessage({ ...request, id });
    });
    try {
        return await (timeoutMs === null ? answer
            : timeLimited(answer, timeoutMs, `The browser engine did not answer within ${timeoutMs} ms`));
    } catch (error) {
        if (worker === caller) giveUp(error);
        throw error;
    }
}

function onWorkerMessage({ data }) {
    const request = pending.get(data.id);
    if (!request) return;
    if (data.type === 'stations') {
        request.onStations(data.stations);
        return;
    }
    pending.delete(data.id);
    if (data.type === 'done') {
        request.resolve(data.result);
    } else {
        request.reject(new Error(data.message));
    }
}

function giveUp(error) {
    if (failed) return;
    console.warn('Searches stay on the server:', error);
    failed = true;
    loaded = null;
    stopWorker();
    showServerStationLabels();
}

/** The names the engine would give, from the server, when it doesn't run or failed before giving them (#108). */
function showServerStationLabels() {
    if (labelsShown) return;
    labelsShown = true;
    fetchStationLabels().then(onStationLabels).catch(error => {
        labelsShown = false;
        console.warn('The map shows no station names:', error);
    });
}

function stopWorker() {
    worker?.terminate();
    worker = null;
    pending.forEach(request => request.reject(new Error('The browser engine was stopped')));
    pending.clear();
}
