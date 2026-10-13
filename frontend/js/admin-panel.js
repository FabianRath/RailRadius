// Admin panel: buttons for the /api/admin endpoints, only reachable after the dev login. Each action shows the raw
// JSON response, so it replaces the matching `scripts/api.sh` command. The Feeds tab is its own view (feeds.js).
import {
    clearDebugTrigger, deleteGtfsCache, fetchCacheStats, fetchGtfsLoadStatus, fetchGtfsStats, reloadGtfs,
    setDebugTrigger,
} from './api.js';
import { initFeeds, refreshFeedStorage, refreshFeeds } from './feeds.js';
import { closeOnBackdropClick, fmtSeconds } from './util.js';

const JSON_INDENT = 2;
const PROGRESS_POLL_INTERVAL_MS = 1000;
const PHASE_LABELS = {
    IDLE: 'Idle', PARSING: 'Parsing feeds', MERGING: 'Merging stations', SAVING: 'Writing cache',
    DONE: 'Done', FAILED: 'Failed',
};

// Keys match the data-action attributes in index.html
const ACTIONS = {
    gtfsStats: { run: fetchGtfsStats },
    cacheStats: { run: fetchCacheStats },
    debugOn: { run: setDebugTrigger },
    debugOff: { run: clearDebugTrigger },
    reloadGtfs: {
        run: reloadGtfs,
        showsProgress: true,
        confirm: 'Reparse all GTFS zips and rewrite the cache? This takes minutes on the full dataset, '
            + 'and searches fail until it is done.',
    },
    deleteGtfsCache: {
        run: deleteGtfsCache,
        confirm: 'Delete the GTFS cache file? The next start has to reparse all zips.',
    },
};

const openButton = document.getElementById('adminOpen');
const dialog = document.getElementById('adminPanel');
const actionButtons = dialog.querySelectorAll('[data-action]');
const tabs = dialog.querySelectorAll('[data-tab]');
const views = dialog.querySelectorAll('[data-view]');
const output = document.getElementById('adminOutput');
const progress = document.getElementById('adminProgress');
const progressBar = document.getElementById('adminProgressBar');
const progressText = document.getElementById('adminProgressText');

export function initAdminPanel() {
    openButton.addEventListener('click', () => {
        dialog.showModal();
        refreshFeedStorage();
    });
    document.getElementById('adminClose').addEventListener('click', () => dialog.close());
    closeOnBackdropClick(dialog);
    actionButtons.forEach(button => button.addEventListener('click', () => runAction(button)));
    tabs.forEach(tab => tab.addEventListener('click', () => showTab(tab.dataset.tab)));
    initFeeds();
}

export function showAdminEntry() {
    openButton.hidden = false;
}

export function hideAdminPanel() {
    openButton.hidden = true;
    dialog.close();
}

// The feed table needs more room, so the panel widens while the Feeds tab is open
function showTab(name) {
    tabs.forEach(tab => {
        const active = tab.dataset.tab === name;
        tab.classList.toggle('is-active', active);
        tab.setAttribute('aria-selected', String(active));
    });
    views.forEach(view => { view.hidden = view.dataset.view !== name; });
    dialog.classList.toggle('admin-panel-wide', name === 'feeds');
    if (name === 'feeds') refreshFeeds();
}

// The dialog's padding also targets the dialog itself, so the click position decides
async function runAction(button) {
    const action = ACTIONS[button.dataset.action];
    if (action.confirm && !window.confirm(action.confirm)) return;
    setBusy(true);
    showOutput(`${button.textContent}…`, false);
    // The reload request only answers when it is done, so its progress is polled alongside
    progress.hidden = !action.showsProgress;
    progressBar.value = 0;
    progressText.textContent = 'Starting…';
    const pollTimer = action.showsProgress ? setInterval(refreshProgress, PROGRESS_POLL_INTERVAL_MS) : null;
    try {
        showOutput(JSON.stringify(await action.run(), null, JSON_INDENT), false);
    } catch (e) {
        showOutput(`${button.textContent} failed: ${e.message}`, true);
    } finally {
        clearInterval(pollTimer);
        if (action.showsProgress) await refreshProgress();
        setBusy(false);
    }
}

async function refreshProgress() {
    try {
        renderProgress(await fetchGtfsLoadStatus());
    } catch (e) {
        // Shown instead of hidden, so a backend without the status endpoint doesn't look like a stuck reload
        progressText.textContent = `Progress unavailable: ${e.message}`;
    }
}

function renderProgress(status) {
    progressBar.value = status.parsedPercent;
    const feed = status.currentFeed ? ` (${status.currentFeed})` : '';
    const kpis = [
        PHASE_LABELS[status.phase],
        `feed ${status.feedsDone}/${status.feedsTotal}${feed}`,
        `${Math.round(status.parsedPercent)}% parsed`,
        `${fmtSeconds(status.elapsedSeconds)} elapsed`,
    ];
    if (status.parseEtaSeconds !== null) kpis.push(`~${fmtSeconds(status.parseEtaSeconds)} left`);
    kpis.push(`${status.stationCount.toLocaleString()} stations`,
        `${status.connectionCount.toLocaleString()} connections`);
    progressText.textContent = kpis.join(' · ');
}

function setBusy(busy) {
    actionButtons.forEach(button => { button.disabled = busy; });
}

function showOutput(text, isError) {
    output.textContent = text;
    output.classList.toggle('is-error', isError);
}
