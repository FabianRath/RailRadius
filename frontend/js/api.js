import { API_BASE } from './config.js';

const SEARCH_LIMIT = 50;
const ADMIN_TOKEN_KEY = 'railreach.adminToken';

let adminToken = readStoredAdminToken();

async function getJson(path, headers = {}) {
    const response = await fetch(API_BASE + path, { headers });
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

function adminHeaders() {
    return { 'X-Admin-Token': adminToken ?? '' };
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
    return getJson('/api/health');
}

export function searchStations(query) {
    return getJson(`/api/stations/search?q=${encodeURIComponent(query)}&limit=${SEARCH_LIMIT}`);
}

/** Opens the SSE stream; the caller attaches the 'stations', 'done' and 'error' listeners. */
export function openReachabilityStream(params) {
    return new EventSource(`${API_BASE}/api/reachability/stream?${new URLSearchParams(params)}`);
}

export function fetchDebugStatus() {
    return getJson('/api/admin/debug/status', adminHeaders());
}

export function fetchDebugStations() {
    return getJson('/api/admin/debug/stations', adminHeaders());
}

export function clearDebugTrigger() {
    return fetch(`${API_BASE}/api/admin/debug/trigger`, { method: 'DELETE', headers: adminHeaders() });
}
