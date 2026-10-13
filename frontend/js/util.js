export function fmtMins(mins) {
    const h = Math.floor(mins / 60), m = mins % 60;
    return h > 0 ? (m > 0 ? `${h}h ${m}m` : `${h}h`) : `${m}m`;
}

/** Seconds below a minute, otherwise rounded to fmtMins. */
export function fmtSeconds(seconds) {
    return seconds < 60 ? `${seconds}s` : fmtMins(Math.round(seconds / 60));
}

/** GTFS HH:MM:SS (hours may exceed 23) as HH:MM, with "+1" for a time on the next day. */
export function fmtGtfsClock(gtfsTime) {
    const [hours, minutes] = gtfsTime.split(':').map(Number);
    const days = Math.floor(hours / 24);
    const clock = `${String(hours % 24).padStart(2, '0')}:${String(minutes).padStart(2, '0')}`;
    return days > 0 ? `${clock} +${days}` : clock;
}

const BYTES_PER_MB = 1024 * 1024;
const MB_PER_GB = 1024;

/** File sizes as "12.3 MB", or "1.23 GB" from a GB on. */
export function fmtBytes(bytes) {
    const mb = bytes / BYTES_PER_MB;
    return mb < MB_PER_GB ? `${mb.toFixed(1)} MB` : `${(mb / MB_PER_GB).toFixed(2)} GB`;
}

const HTML_ESCAPES = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };

/** GTFS names are untrusted text; escape them before they go into innerHTML. */
export function escapeHtml(value) {
    return String(value ?? '').replace(/[&<>"']/g, ch => HTML_ESCAPES[ch]);
}

/** A small ISO country code pill after a station name; nothing when the country is unknown. */
export function countryBadgeHtml(country) {
    return country ? ` <span class="country-badge">${escapeHtml(country)}</span>` : '';
}

/** Reads a colour or size defined as a CSS variable in app.css, so the map uses the same palette as the UI. */
export function cssVar(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

/**
 * A backdrop click targets the dialog itself. Checking where the press started keeps a text selection dragged out
 * of the dialog from closing it.
 */
export function closeOnBackdropClick(dialog) {
    let pressStartedOutside = false;
    dialog.addEventListener('pointerdown', event => { pressStartedOutside = isOutsideDialog(dialog, event); });
    dialog.addEventListener('click', event => {
        if (pressStartedOutside && isOutsideDialog(dialog, event)) dialog.close();
    });
}

function isOutsideDialog(dialog, event) {
    if (event.target !== dialog) return false;
    const rect = dialog.getBoundingClientRect();
    return event.clientX < rect.left || event.clientX > rect.right
        || event.clientY < rect.top || event.clientY > rect.bottom;
}

const STATUS_LOGGERS = { error: console.error, warning: console.warn, info: console.log };

/** type is 'info', 'warning' or 'error'; the message replaces the previous one in the sidebar. */
export function showStatus(type, message) {
    STATUS_LOGGERS[type]('Status:', message);
    const statusMessage = document.getElementById('statusMessage');
    statusMessage.textContent = message;
    statusMessage.className = `status status-${type}`;
    statusMessage.hidden = false;
}

export function clearStatus() {
    document.getElementById('statusMessage').hidden = true;
}

export function lerpColor(hexA, hexB, t) {
    const parse = h => [1, 3, 5].map(i => parseInt(h.slice(i, i + 2), 16));
    const a = parse(hexA);
    const b = parse(hexB);
    const [r, g, bl] = a.map((v, i) => Math.round(v + (b[i] - v) * t));
    return `rgb(${r},${g},${bl})`;
}
