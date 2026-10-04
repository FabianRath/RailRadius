export function fmtMins(mins) {
    const h = Math.floor(mins / 60), m = mins % 60;
    return h > 0 ? (m > 0 ? `${h}h ${m}m` : `${h}h`) : `${m}m`;
}

const HTML_ESCAPES = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };

/** GTFS names are untrusted text; escape them before they go into innerHTML. */
export function escapeHtml(value) {
    return String(value ?? '').replace(/[&<>"']/g, ch => HTML_ESCAPES[ch]);
}

/** Reads a colour or size defined as a CSS variable in app.css, so the map uses the same palette as the UI. */
export function cssVar(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
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
