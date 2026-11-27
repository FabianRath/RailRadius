export const THEMES = ['light', 'dark'];

/** The visitor's stored choice wins; without one (or with a stale value) the system setting decides. */
export function resolveTheme(storedTheme, systemPrefersDark) {
    if (THEMES.includes(storedTheme)) return storedTheme;
    return systemPrefersDark ? 'dark' : 'light';
}

export function otherTheme(theme) {
    return theme === 'dark' ? 'light' : 'dark';
}
