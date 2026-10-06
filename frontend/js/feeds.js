// Feeds tab of the admin panel: how long each GTFS zip runs, and downloads of new versions. Bulk downloads go one
// feed at a time from here, so each row shows its progress and result.
import { downloadFeed, fetchFeeds } from './api.js';
import { icon } from './icons.js';
import { escapeHtml } from './util.js';

const STATE_LABELS = {
    MISSING: 'Missing', OK: 'OK', EXPIRING: 'Expiring', EXPIRED: 'Expired', NOT_YET_VALID: 'Not yet valid', NO_CALENDAR: 'No calendar',
    UNREADABLE: 'Unreadable',
};
const OUTDATED_STATES = ['EXPIRED', 'EXPIRING'];
// A fresh checkout has no zips; "Download outdated" fetches them too
const TO_DOWNLOAD_STATES = ['MISSING', ...OUTDATED_STATES];
const BYTES_PER_MB = 1024 * 1024;
const ISO_DATE_LENGTH = 10;
const PENDING = { outcome: 'PENDING', message: 'Downloading…' };

const rows = document.getElementById('feedRows');
const summary = document.getElementById('feedSummary');
const toolbarButtons = document.querySelectorAll('#feedRefresh, #feedDownloadOutdated, #feedDownloadAll');

let feeds = [];
// source -> { outcome, message } of the downloads since the page was loaded
const results = new Map();
let busy = false;

export function initFeeds() {
    document.getElementById('feedRefresh').addEventListener('click', refreshFeeds);
    document.getElementById('feedDownloadOutdated').addEventListener('click', () => downloadFeeds(
        feeds.filter(feed => feed.downloadUrl && TO_DOWNLOAD_STATES.includes(feed.state)),
        'missing, expired or expiring'));
    document.getElementById('feedDownloadAll').addEventListener('click', () => downloadFeeds(
        feeds.filter(feed => feed.downloadUrl), 'downloadable'));
    rows.addEventListener('click', event => {
        const button = event.target.closest('[data-source]');
        if (button) downloadFeeds(feeds.filter(feed => feed.source === button.dataset.source), 'selected');
    });
}

export async function refreshFeeds() {
    try {
        feeds = await fetchFeeds();
        render();
        if (!busy) showSummary(overview(), false);
    } catch (e) {
        showSummary(`Loading the feeds failed: ${e.message}`, true);
    }
}

async function downloadFeeds(selected, description) {
    if (busy) return;
    if (selected.length === 0) {
        showSummary(`No ${description} feed has a download URL.`, false);
        return;
    }
    const sources = selected.map(feed => feed.source);
    const question = `Download ${sources.length} ${description} feed(s): ${sources.join(', ')}? `
        + 'Big feeds take minutes each. Replaced zips are kept in previous/.';
    if (!window.confirm(question)) return;
    setBusy(true);
    try {
        for (const [index, source] of sources.entries()) {
            showSummary(`Downloading ${source} (${index + 1}/${sources.length})…`, false);
            results.set(source, PENDING);
            render();
            results.set(source, await download(source));
            await refreshFeeds();
        }
    } finally {
        setBusy(false);
    }
    showSummary(downloadSummary(sources), false);
}

async function download(source) {
    try {
        const result = await downloadFeed(source);
        return { outcome: result.outcome, message: result.message };
    } catch (e) {
        return { outcome: 'FAILED', message: e.message };
    }
}

function overview() {
    const outdated = feeds.filter(feed => OUTDATED_STATES.includes(feed.state)).length;
    const missing = feeds.filter(feed => feed.state === 'MISSING').length;
    const manual = feeds.filter(feed => !feed.downloadUrl).length;
    return `${feeds.length} feeds: ${missing} missing, ${outdated} expired or expiring, ${manual} updated by hand.`;
}

function downloadSummary(sources) {
    const replaced = sources.filter(source => results.get(source).outcome === 'REPLACED').length;
    const text = `${replaced} of ${sources.length} feed(s) replaced.`;
    return replaced > 0 ? `${text} Rebuild from zips (Tools tab) to use the new data.` : text;
}

function render() {
    rows.innerHTML = feeds.map(feedRowHtml).join('');
}

function feedRowHtml(feed) {
    // The first day is only a tooltip, to keep the table narrow
    const runsUntil = feed.lastServiceDate
        ? `<span title="Runs from ${feed.firstServiceDate}">${feed.lastServiceDate}</span>` : '–';
    const stateClass = `feed-state-${feed.state.toLowerCase().replaceAll('_', '-')}`;
    const size = feed.state === 'MISSING' ? '–' : `${(feed.sizeBytes / BYTES_PER_MB).toFixed(1)} MB`;
    const downloaded = feed.downloadedAt?.slice(0, ISO_DATE_LENGTH) ?? '–';
    return `<tr><td>${escapeHtml(feed.source)}</td><td>${runsUntil}</td><td>${feed.daysLeft ?? '–'}</td>`
        + `<td class="feed-wrap"><span class="${stateClass}">${STATE_LABELS[feed.state]}</span></td><td>${size}</td>`
        + `<td>${downloaded}</td><td class="feed-wrap">${sourceHtml(feed)}</td><td>${downloadButtonHtml(feed)}</td>`
        + `<td class="feed-wrap">${resultHtml(results.get(feed.source))}</td></tr>`;
}

function sourceHtml(feed) {
    if (!feed.downloadUrl) return '<span class="feed-muted" title="No download URL in gtfs-feeds.txt">manual</span>';
    // A narrow column breaks the host after a dot, not inside a word
    const host = escapeHtml(new URL(feed.downloadUrl).host).replaceAll('.', '.<wbr>');
    return `<span title="${escapeHtml(feed.downloadUrl)}">${host}</span>`;
}

function downloadButtonHtml(feed) {
    if (!feed.downloadUrl) return '';
    const source = escapeHtml(feed.source);
    return `<button type="button" class="admin-btn feed-download" data-source="${source}"`
        + ` aria-label="Download ${source}"${busy ? ' disabled' : ''}>${icon('download')}</button>`;
}

function resultHtml(result) {
    if (!result) return '';
    return `<span class="feed-result-${result.outcome.toLowerCase()}">${escapeHtml(result.message)}</span>`;
}

function setBusy(value) {
    busy = value;
    toolbarButtons.forEach(button => { button.disabled = value; });
    rows.querySelectorAll('[data-source]').forEach(button => { button.disabled = value; });
}

function showSummary(text, isError) {
    summary.textContent = text;
    summary.classList.toggle('is-error', isError);
}
