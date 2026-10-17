import { API_BASE } from './config.js';

const SEARCH_LIMIT = 50;
const ADMIN_TOKEN_KEY = 'railradius.adminToken';

let adminToken = readStoredAdminToken();

async function request(path, init = {}) {
    const response = await fetch(API_BASE + path, init);
    if (!response.ok) throw new Error(`HTTP ${response.status} for ${path}`);
    return response;
}

async function requestJson(path, init = {}) {
    return (await request(path, init)).json();
}

function readStoredAdminToken() {
    try {
        return localStorage.getItem(ADMIN_TOKEN_KEY);
    } catch {
        return null;
    }
}

function adminInit(method) {
    return { method, headers: { 'X-Admin-Token': adminToken ?? '' } };
}

function adminJson(path, method = 'GET') {
    return requestJson(path, adminInit(method));
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

/** Sets the admin cookie, so this browser's searches skip the rate limit (EventSource cannot send the header). */
export async function startAdminSession() {
    await request('/api/admin/session', adminInit('POST'));
}

export async function endAdminSession() {
    await request('/api/admin/session', adminInit('DELETE'));
}

export function fetchHealth() {
    return requestJson('/api/health');
}

export function fetchCoverage() {
    return requestJson('/api/coverage');
}

export function searchStations(query) {
    return requestJson(`/api/stations/search?q=${encodeURIComponent(query)}&limit=${SEARCH_LIMIT}`);
}

/** One station by its canonical stop ID (id, name, latitude, longitude, country); fails with HTTP 404 if unknown. */
export function fetchStation(stationId) {
    return requestJson(`/api/stations/${encodeURIComponent(stationId)}`);
}

/** Opens the SSE stream; the caller attaches the 'stations', 'done' and 'error' listeners. */
export function openReachabilityStream(params) {
    return new EventSource(`${API_BASE}/api/reachability/stream?${new URLSearchParams(params)}`);
}

/** One reachable station with its journey; params are the stream's plus targetId. */
export function fetchJourney(params) {
    return requestJson(`/api/reachability/journey?${new URLSearchParams(params)}`);
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

export function fetchFeedStorage() {
    return adminJson('/api/admin/feeds/storage');
}

export function fetchFeedAlerts() {
    return adminJson('/api/admin/feeds/alerts');
}

export function setFeedAlertsEnabled(enabled) {
    return adminJson(`/api/admin/feeds/alerts?enabled=${enabled}`, 'PUT');
}

/** Mails the current alert now, also while alerts are off; answers whether it was sent. */
export function sendTestFeedAlert() {
    return adminJson('/api/admin/feeds/alerts/test', 'POST');
}

export function pauseFeedAlert(source) {
    return adminJson(`/api/admin/feeds/${encodeURIComponent(source)}/alert-pause`, 'PUT');
}

export function resumeFeedAlert(source) {
    return adminJson(`/api/admin/feeds/${encodeURIComponent(source)}/alert-pause`, 'DELETE');
}

export function downloadFeed(source) {
    return adminJson(`/api/admin/feeds/${encodeURIComponent(source)}/download`, 'POST');
}
