import { API_BASE } from './config.js';

const SEARCH_LIMIT = 50;
const ADMIN_TOKEN_KEY = 'railreach.adminToken';

let adminToken = readStoredAdminToken();

async function requestJson(path, init = {}) {
    const response = await fetch(API_BASE + path, init);
    if (!response.ok) throw new Error(`HTTP ${response.status} for ${path}`);
    return response.json();
}

function readStoredAdminToken() {
    try {
        return localStorage.getItem(ADMIN_TOKEN_KEY);
    } catch {
        return null;
    }
}

function adminJson(path, method = 'GET') {
    return requestJson(path, { method, headers: { 'X-Admin-Token': adminToken ?? '' } });
}

export function hasAdminToken() {
    return adminToken !== null;
}

/** Stores the dev token for admin calls (null logs out); kept across reloads when the browser allows it. */
export function setAdminToken(token) {
    adminToken = token;
    try {
        if (token === null) localStorage.removeItem(ADMIN_TOKEN_KEY);
        else localStorage.setItem(ADMIN_TOKEN_KEY, token);
    } catch {
        // Storage blocked (private mode): the login lasts until the page is reloaded
    }
}

export function fetchHealth() {
    return requestJson('/api/health');
}

export function searchStations(query) {
    return requestJson(`/api/stations/search?q=${encodeURIComponent(query)}&limit=${SEARCH_LIMIT}`);
}

/** Opens the SSE stream; the caller attaches the 'stations', 'done' and 'error' listeners. */
export function openReachabilityStream(params) {
    return new EventSource(`${API_BASE}/api/reachability/stream?${new URLSearchParams(params)}`);
}

export function fetchDebugStatus() {
    return adminJson('/api/admin/debug/status');
}

export function fetchDebugStations() {
    return adminJson('/api/admin/debug/stations');
}

export function setDebugTrigger() {
    return adminJson('/api/admin/debug/trigger', 'POST');
}

export function clearDebugTrigger() {
    return adminJson('/api/admin/debug/trigger', 'DELETE');
}

export function fetchGtfsStats() {
    return adminJson('/api/admin/gtfs-stats');
}

export function fetchCacheStats() {
    return adminJson('/api/admin/cache-stats');
}

/** Reparses every GTFS zip and rewrites the cache; the response only arrives when that is done (minutes). */
export function reloadGtfs() {
    return adminJson('/api/admin/reload-gtfs', 'POST');
}

export function fetchGtfsLoadStatus() {
    return adminJson('/api/admin/gtfs-load-status');
}

export function deleteGtfsCache() {
    return adminJson('/api/admin/gtfs-cache', 'DELETE');
}

export function fetchFeeds() {
    return adminJson('/api/admin/feeds');
}

export function downloadFeed(source) {
    return adminJson(`/api/admin/feeds/${encodeURIComponent(source)}/download`, 'POST');
}
