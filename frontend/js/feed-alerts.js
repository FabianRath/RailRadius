// Alerts tab of the admin panel: the daily email about served feeds whose trains end soon (F29), and per-feed pauses.
// A pause holds until the feed's trains end on another day, i.e. until new data for it is served.
import { fetchFeedAlerts, pauseFeedAlert, resumeFeedAlert, sendTestFeedAlert, setFeedAlertsEnabled } from './api.js';
import { escapeHtml } from './util.js';

const toggle = document.getElementById('alertToggle');
const testButton = document.getElementById('alertTest');
const summary = document.getElementById('alertSummary');
const rows = document.getElementById('alertRows');

let enabled = false;

export function initFeedAlerts() {
    toggle.addEventListener('click', () => update(() => setFeedAlertsEnabled(!enabled)));
    testButton.addEventListener('click', sendTest);
    rows.addEventListener('click', event => {
        const button = event.target.closest('[data-source]');
        if (!button) return;
        const source = button.dataset.source;
        update(() => button.dataset.paused === 'true' ? resumeFeedAlert(source) : pauseFeedAlert(source));
    });
}

export function refreshFeedAlerts() {
    return update(fetchFeedAlerts);
}

async function update(request) {
    try {
        render(await request());
    } catch (e) {
        showSummary(`Feed alerts failed: ${e.message}`, true);
    }
}

async function sendTest() {
    testButton.disabled = true;
    showSummary('Sending a test email…', false);
    try {
        const result = await sendTestFeedAlert();
        showSummary(result.message, !result.sent);
    } catch (e) {
        showSummary(`Test email failed: ${e.message}`, true);
    } finally {
        testButton.disabled = false;
    }
}

function render(status) {
    enabled = status.enabled;
    toggle.textContent = `Email alerts: ${enabled ? 'on' : 'off'}`;
    toggle.setAttribute('aria-pressed', String(enabled));
    rows.innerHTML = status.feeds.map(alertRowHtml).join('');
    showSummary(overview(status), !status.mailConfigured);
}

function overview(status) {
    const mail = status.mailConfigured
        ? `Daily email to ${status.recipient}.`
        : 'Mail is not configured (spring.mail.host, railreach.feeds.alerts.to), so nothing is sent.';
    const paused = status.feeds.filter(feed => feed.paused).length;
    return `${mail} ${status.feeds.length} served feed(s) end within ${status.alertDays} days, ${paused} paused.`;
}

function alertRowHtml(alert) {
    const source = escapeHtml(alert.source);
    const daysClass = alert.daysLeft < 0 ? 'feed-state-expired' : 'feed-state-expiring';
    const earlyEnding = alert.earlyEndingOperators.map(end => escapeHtml(end.operator)).join(', ') || '–';
    const state = alert.paused ? '<span class="feed-muted">paused</span>' : 'in the email';
    return `<tr><td>${source}</td><td>${alert.trainsEnd}</td><td class="${daysClass}">${alert.daysLeft}</td>`
        + `<td class="feed-wrap">${earlyEnding}</td><td>${alert.lastServiceDate}</td><td>${state}</td>`
        + `<td><button type="button" class="admin-btn" data-source="${source}" data-paused="${alert.paused}">`
        + `${alert.paused ? 'Resume' : 'Pause'}</button></td></tr>`;
}

function showSummary(text, isError) {
    summary.textContent = text;
    summary.classList.toggle('is-error', isError);
}
