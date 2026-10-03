import { API_BASE } from './config.js';

const SEARCH_LIMIT = 50;

async function getJson(path) {
    const response = await fetch(API_BASE + path);
    if (!response.ok) throw new Error(`HTTP ${response.status} for ${path}`);
    return response.json();
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
    return getJson('/api/admin/debug/status');
}

export function fetchDebugStations() {
    return getJson('/api/admin/debug/stations');
}

export function clearDebugTrigger() {
    return fetch(`${API_BASE}/api/admin/debug/trigger`, { method: 'DELETE' });
}
