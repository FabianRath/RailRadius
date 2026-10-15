import { fetchHealth } from './api.js';

// A restart takes seconds, a reload from the zips minutes; either way the notice goes soon after the end
const POLL_INTERVAL_MS = 5000;

const notice = document.getElementById('updatingNotice');
let waitingForServer = null;

/**
 * True while the backend is not serving timetables: unreachable (restarting) or health DOWN (reloading the GTFS
 * data, or started without any). Then shows the "updating timetables" notice until the backend is back, so callers
 * can skip their own error message.
 */
export async function isUpdatingTimetables() {
    if (waitingForServer) return true;
    if (await isServing()) return false;
    // Another check may have started polling while this one waited for its answer
    if (!waitingForServer) {
        notice.hidden = false;
        waitingForServer = pollUntilServing();
    }
    return true;
}

async function pollUntilServing() {
    do {
        await new Promise(resolve => setTimeout(resolve, POLL_INTERVAL_MS));
    } while (!(await isServing()));
    notice.hidden = true;
    waitingForServer = null;
}

async function isServing() {
    try {
        return (await fetchHealth()).status === 'UP';
    } catch {
        return false;
    }
}
