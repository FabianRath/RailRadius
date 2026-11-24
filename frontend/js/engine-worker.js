// The browser engine (#12) in a Web Worker, so a search never blocks the map. Each request is { id, type, ... };
// it answers { id, type: 'stations' } per round of a search, then { id, type: 'done', result } or
// { id, type: 'failed', message }. Without a built engine (frontend/engine/) the import fails and the page searches
// on the server.
import { load } from '../engine/wasm-gc-module-runtime.js';
import { contentVersionOf } from './engine-rules.js';

const ENGINE_URL = new URL('../engine/search.wasm', import.meta.url).href;

let engine = null;
// The content version of the engine's bytes, so the page can check it is the one the server names
let engineVersion = null;
// The search whose result the engine holds, for its stations' rides
let lastSearchKey = null;

self.onmessage = async ({ data }) => {
    try {
        postMessage({ id: data.id, type: 'done', result: await handle(data) });
    } catch (error) {
        postMessage({ id: data.id, type: 'failed', message: String(error) });
    }
};

async function handle(request) {
    switch (request.type) {
        case 'load': return loadTimetable(request);
        case 'search': return search(request);
        case 'rides': return rides(request);
        default: throw new Error(`Unknown request ${request.type}`);
    }
}

async function loadTimetable({ path, today }) {
    // The server sends it gzipped with Content-Encoding, so fetch unzips it
    const response = await fetch(path);
    if (!response.ok) throw new Error(`HTTP ${response.status} for ${path}`);
    const file = new Int8Array(await response.arrayBuffer());
    if (!engine) await loadEngine();
    lastSearchKey = null;
    engine.load(file, today);
    return { engineVersion };
}

async function loadEngine() {
    const response = await fetch(ENGINE_URL);
    if (!response.ok) throw new Error(`HTTP ${response.status} for ${ENGINE_URL}`);
    const bytes = await response.arrayBuffer();
    engineVersion = await contentVersionOf(bytes);
    engine = (await load(bytes)).exports;
}

function search({ id, options }) {
    const total = runSearch(options, json => postMessage({ id, type: 'stations', stations: JSON.parse(json) }));
    return { total };
}

/** The rides of the path the search shows for the station; searches again if another search ran since. */
function rides({ options, stationId }) {
    if (searchKey(options) !== lastSearchKey) runSearch(options, () => {});
    return JSON.parse(engine.rides(stationId));
}

function runSearch(options, onRound) {
    lastSearchKey = null;
    const total = engine.search(options.stationId, options.maxTransfers, options.minTransferMinutes,
        options.maxTransferMinutes, options.maxJourneyMinutes, onRound);
    lastSearchKey = searchKey(options);
    return total;
}

function searchKey(options) {
    return JSON.stringify(options);
}
