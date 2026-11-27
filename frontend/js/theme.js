import { otherTheme, resolveTheme } from './theme-rules.js';
import { addMapButton, applyThemeColors } from './map.js';
import { icon } from './icons.js';

const STORAGE_KEY = 'railradius.theme';
const systemDark = window.matchMedia('(prefers-color-scheme: dark)');

function readStoredTheme() {
    try {
        return localStorage.getItem(STORAGE_KEY);
    } catch {
        return null;
    }
}

function storeTheme(theme) {
    try {
        localStorage.setItem(STORAGE_KEY, theme);
    } catch {
        // Private windows may block storage: the choice then lasts only for this page
    }
}

function currentTheme() {
    return document.documentElement.dataset.theme;
}

/** Before the map is created, so it starts in the right colours. */
export function applyInitialTheme() {
    document.documentElement.dataset.theme = resolveTheme(readStoredTheme(), systemDark.matches);
}

function setTheme(theme, button) {
    document.documentElement.dataset.theme = theme;
    // The button offers the other theme
    button.innerHTML = icon(theme === 'dark' ? 'sun' : 'moon');
    button.setAttribute('aria-label', theme === 'dark' ? 'Switch to light map' : 'Switch to dark map');
    applyThemeColors();
}

/** Adds the toggle to the map controls; call after the map has loaded. */
export function initThemeToggle() {
    const button = document.getElementById('themeToggle');
    addMapButton(button);
    button.hidden = false;
    setTheme(currentTheme(), button);
    button.addEventListener('click', () => {
        const theme = otherTheme(currentTheme());
        storeTheme(theme);
        setTheme(theme, button);
    });
    systemDark.addEventListener('change', event => setTheme(resolveTheme(readStoredTheme(), event.matches), button));
}
