// Dev login: the admin token unlocks dev features (debug overlay, admin panel) and exempts this browser's searches
// from the rate limit (via a session cookie). It is checked against the backend before it is kept, and lives in page
// memory only, so a reload logs out. Offered only on a loopback host: in production the proxy blocks the admin API
// and the admin reaches it through an SSH tunnel to localhost:8080 (see HOSTING.md).
import { endAdminSession, fetchDebugStatus, hasAdminToken, setAdminToken, startAdminSession } from './api.js';

const LOOPBACK_HOSTS = ['localhost', '127.0.0.1', '[::1]'];

const footer = document.getElementById('devFooter');
const toggle = document.getElementById('devToggle');
const form = document.getElementById('devLoginForm');
const tokenInput = document.getElementById('devToken');
const error = document.getElementById('devError');

let callbacks;

/** onLogin/onLogout run whenever dev mode switches on or off. */
export function initDevAuth(onLogin, onLogout) {
    if (!LOOPBACK_HOSTS.includes(location.hostname)) return;
    footer.hidden = false;
    callbacks = { onLogin, onLogout };
    toggle.addEventListener('click', onToggleClick);
    form.addEventListener('submit', onSubmit);
    render(false);
}

async function onToggleClick() {
    if (hasAdminToken()) {
        try {
            await endAdminSession();
        } catch (error) {
            console.error('Could not delete the admin cookie:', error);
        }
        setAdminToken(null);
        render(false);
        callbacks.onLogout();
    } else {
        form.hidden = !form.hidden;
        if (!form.hidden) tokenInput.focus();
    }
}

async function onSubmit(event) {
    event.preventDefault();
    setAdminToken(tokenInput.value.trim());
    if (await tokenIsValid()) {
        await enterDevMode();
        return;
    }
    setAdminToken(null);
    error.textContent = 'Invalid token';
    error.hidden = false;
}

async function tokenIsValid() {
    try {
        await fetchDebugStatus();
        return true;
    } catch {
        return false;
    }
}

async function enterDevMode() {
    render(true);
    callbacks.onLogin();
    try {
        await startAdminSession();
    } catch (error) {
        console.error('Could not set the admin cookie, searches stay rate limited:', error);
    }
}

function render(loggedIn) {
    tokenInput.value = '';
    form.hidden = true;
    error.hidden = true;
    toggle.textContent = loggedIn ? 'Dev mode · log out' : 'Dev login';
    toggle.classList.toggle('is-active', loggedIn);
}
