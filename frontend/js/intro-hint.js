// The first-visit hint over the map: what the map shows. Once dismissed, it stays hidden in this browser.
const DISMISSED_KEY = 'railradius.introHintDismissed';

export function initIntroHint() {
    document.getElementById('introHint').hidden = wasDismissed();
    document.getElementById('introHintClose').addEventListener('click', dismissIntroHint);
}

/** Closed by its button or by the first search: either way the user no longer needs it. */
export function dismissIntroHint() {
    document.getElementById('introHint').hidden = true;
    try {
        localStorage.setItem(DISMISSED_KEY, 'true');
    } catch {
        // Storage blocked (private mode): the hint comes back on the next visit
    }
}

function wasDismissed() {
    try {
        return localStorage.getItem(DISMISSED_KEY) !== null;
    } catch {
        return false;
    }
}
