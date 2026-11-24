// The rules for searching in the browser (#12), without the page: who may, and what the engine is given.

// About 1 GB of heap on today's full data (#12); Chrome reports at most 8
export const MIN_DEVICE_MEMORY_GB = 8;
// The engine's "no limit" for the journey time: the largest int it takes
export const NO_JOURNEY_LIMIT_MINUTES = 2147483647;
const MINUTES_PER_HOUR = 60;
const VERSION_BYTES = 8;
// How long a browser search may run, like the server's search.deadline-ms: a worker that hangs or dies without an
// error (some browsers kill one silently when it runs out of memory) must not keep a search waiting forever
export const ENGINE_SEARCH_TIMEOUT_MS = 30000;
// How long after the engine's load started a search may wait for it; most visitors take longer to pick a station
export const MAX_ENGINE_WAIT_MS = 5000;

/**
 * Whether this browser may search on its own: desktops only (a fine pointer, not reported as mobile) that can run
 * workers and WebAssembly, with enough memory or none reported (Firefox, Safari), which try and fall back on failure.
 */
export function mayRunEngine({ mobile, finePointer, deviceMemoryGb, hasWorker, hasWebAssembly }) {
    if (mobile || !finePointer || !hasWorker || !hasWebAssembly) return false;
    return deviceMemoryGb == null || deviceMemoryGb >= MIN_DEVICE_MEMORY_GB;
}

/** How long a search may still wait for the engine whose load started then; 0 once the wait is used up. */
export function engineWaitMs(loadStartedMs, nowMs) {
    return Math.max(0, loadStartedMs + MAX_ENGINE_WAIT_MS - nowMs);
}

/** The promise's answer, or a rejection with the message once timeoutMs passed without one. */
export function timeLimited(promise, timeoutMs, message) {
    let timer;
    const timeout = new Promise((resolve, reject) => {
        timer = setTimeout(() => reject(new Error(message)), timeoutMs);
    });
    return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

/** The journey-time limit of request params in whole minutes, as the server cuts it. */
export function maxJourneyMinutesOf(params) {
    if (params.maxJourneyHours == null) return NO_JOURNEY_LIMIT_MINUTES;
    return Math.trunc(params.maxJourneyHours * MINUTES_PER_HOUR);
}

/**
 * Whether what the browser loaded searches exactly as the server does now: the same data, the same engine and the
 * same today (both as /api/browser-data names them).
 */
export function matchesServer(loaded, server) {
    return loaded != null && loaded.version === server.version && loaded.engineVersion === server.engineVersion
        && loaded.today === server.today;
}

/** The content version of bytes as the server names files: the first 8 bytes of their SHA-256 in hex. */
export async function contentVersionOf(bytes) {
    const hash = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
    return [...hash.slice(0, VERSION_BYTES)].map(byte => byte.toString(16).padStart(2, '0')).join('');
}
