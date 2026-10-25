import { fmtMins } from './util.js';

const DEFAULT_MAX_TRANSFER_MINUTES = 180;
const TRANSFER_TIME_LIMIT_DEFAULT = true;

const calculateBtn = document.getElementById('calculateBtn');
const transferInput = document.getElementById('transferInput');
const transferMinus = document.getElementById('transferMinus');
const transferPlus = document.getElementById('transferPlus');
const maxJourneyTimeInput = document.getElementById('maxJourneyTime');
const transferTimeToggle = document.getElementById('transferTimeToggle');
const transferTimeHint = document.getElementById('transferTimeToggleHint');
const transferTimeWrap = document.getElementById('transferTimeInputWrap');
const minTransferTimeInput = document.getElementById('minTransferTime');
const maxTransferTimeInput = document.getElementById('maxTransferTime');
const transferTimeError = document.getElementById('transferTimeError');
const areasToggle = document.getElementById('showMergedZones');
const optionsCell = document.querySelector('.board-options');
const optionsToggle = document.getElementById('optionsToggle');
const optionsPanel = document.getElementById('optionsPanel');

let transferTimeLimitEnabled = TRANSFER_TIME_LIMIT_DEFAULT;

/** onAreasToggle(visible) runs now and whenever the "areas" option changes; the browser may restore it on reload. */
export function initQueryForm(onAreasToggle) {
    transferMinus.addEventListener('click', () => { transferInput.stepDown(); updateStepperButtons(); });
    transferPlus.addEventListener('click', () => { transferInput.stepUp(); updateStepperButtons(); });
    transferInput.addEventListener('input', updateStepperButtons);
    transferTimeToggle.addEventListener('click', () => setTransferTimeLimitEnabled(!transferTimeLimitEnabled));
    [minTransferTimeInput, maxTransferTimeInput].forEach(input => input.addEventListener('input', () => {
        updateTransferTimeHint();
        clearWaitRangeErrorIfValid();
    }));
    areasToggle.addEventListener('change', () => onAreasToggle(areasToggle.checked));
    onAreasToggle(areasToggle.checked);
    initOptionsPanel();
    updateStepperButtons();
    updateTransferTimeHint();
}

export function showQueryForm() {
    calculateBtn.disabled = false;
}

// The backend rejects values outside the fields' min/max (B41), so typed values are clamped to them. With the wait
// limit off, the whole range the backend allows is searched.
export function readQuery() {
    return queryFromFields(input => input.value, transferTimeLimitEnabled);
}

/**
 * Whether readQuery() is a search the backend accepts. If not, opens the Wait panel at the message saying why, since
 * the backend would only answer with its validation error (B52).
 */
export function checkQuery() {
    const valid = waitRangeIsValid();
    showWaitRangeError(!valid);
    if (!valid) setOptionsOpen(true);
    return valid;
}

/** The query the untouched form sends. */
export function defaultQuery() {
    return queryFromFields(input => input.defaultValue, TRANSFER_TIME_LIMIT_DEFAULT);
}

/**
 * Sets the fields from untrusted text, e.g. a link's parameters (null for a missing one). A value that is no number
 * keeps the field's default; the others are clamped to the field's bounds like typed values. The wait range the
 * limit off sends turns the limit off.
 */
export function applyQuery(query) {
    setField(transferInput, parseInt(query.maxTransfers, 10));
    setField(maxJourneyTimeInput, parseFloat(query.maxJourneyHours));
    setField(minTransferTimeInput, parseInt(query.minTransferMinutes, 10));
    setField(maxTransferTimeInput, parseInt(query.maxTransferMinutes, 10));
    restoreTransferTimeLimit();
    updateStepperButtons();
}

// A link holds no toggle state (B56). Turned off, the fields go back to their defaults, so turning the limit on
// again gives the usual range rather than the whole one.
function restoreTransferTimeLimit() {
    const unlimited = unlimitedWaitRange();
    const limitOff = Number(minTransferTimeInput.value) === unlimited.minTransferMinutes
        && Number(maxTransferTimeInput.value) === unlimited.maxTransferMinutes;
    if (limitOff) {
        [minTransferTimeInput, maxTransferTimeInput].forEach(input => { input.value = input.defaultValue; });
    }
    setTransferTimeLimitEnabled(!limitOff);
}

function setField(input, value) {
    input.value = Number.isNaN(value) ? input.defaultValue : clampToField(input, value);
}

function queryFromFields(valueOf, limitEnabled) {
    const journeyHours = valueOf(maxJourneyTimeInput);
    const waitRange = limitEnabled
        ? {
            minTransferMinutes: minTransferMinutesOf(valueOf(minTransferTimeInput)),
            maxTransferMinutes: maxTransferMinutesOf(valueOf(maxTransferTimeInput))
        }
        : unlimitedWaitRange();
    return {
        maxTransfers: clampTransfers(valueOf(transferInput)),
        maxJourneyHours: journeyHours ? clampToField(maxJourneyTimeInput, parseFloat(journeyHours)) : null,
        ...waitRange
    };
}

// With the limit off, the search sends the whole range the backend allows
function unlimitedWaitRange() {
    return { minTransferMinutes: 0, maxTransferMinutes: Number(maxTransferTimeInput.max) };
}

// stepUp()/stepDown() fire no input event, so the buttons call this themselves
function updateStepperButtons() {
    const transfers = clampTransfers(transferInput.value);
    transferMinus.disabled = transfers <= Number(transferInput.min);
    transferPlus.disabled = transfers >= Number(transferInput.max);
}

// The field accepts any typed text; empty or invalid input means the minimum
function clampTransfers(value) {
    const transfers = parseInt(value, 10);
    if (Number.isNaN(transfers)) return Number(transferInput.min);
    return clampToField(transferInput, transfers);
}

// The search and the hint both read the wait fields through these, so the hint shows what the search will use
function minTransferMinutesOf(text) {
    return clampToField(minTransferTimeInput, parseInt(text, 10) || 0);
}

function maxTransferMinutesOf(text) {
    return clampToField(maxTransferTimeInput, parseInt(text, 10) || DEFAULT_MAX_TRANSFER_MINUTES);
}

// With the limit off, the search sends the whole range, which is always valid
function waitRangeIsValid() {
    return !transferTimeLimitEnabled
        || minTransferMinutesOf(minTransferTimeInput.value) <= maxTransferMinutesOf(maxTransferTimeInput.value);
}

function showWaitRangeError(shown) {
    transferTimeError.hidden = !shown;
    [minTransferTimeInput, maxTransferTimeInput].forEach(input => {
        input.classList.toggle('is-invalid', shown);
        input.setAttribute('aria-invalid', String(shown));
    });
}

function clearWaitRangeErrorIfValid() {
    if (waitRangeIsValid()) showWaitRangeError(false);
}

function clampToField(input, value) {
    return Math.min(Number(input.max), Math.max(Number(input.min), value));
}

function initOptionsPanel() {
    optionsToggle.addEventListener('click', () => setOptionsOpen(optionsPanel.hidden));
    // Listens on the cell, so stopPropagation keeps the document-level Escape from also closing the journey
    optionsCell.addEventListener('keydown', e => {
        if (e.key !== 'Escape' || optionsPanel.hidden) return;
        e.stopPropagation();
        setOptionsOpen(false);
        optionsToggle.focus();
    });
    document.addEventListener('pointerdown', e => {
        if (!optionsCell.contains(e.target)) setOptionsOpen(false);
    });
}

function setOptionsOpen(open) {
    optionsPanel.hidden = !open;
    optionsToggle.setAttribute('aria-expanded', String(open));
}

function setTransferTimeLimitEnabled(enabled) {
    transferTimeLimitEnabled = enabled;
    transferTimeToggle.classList.toggle('is-on', transferTimeLimitEnabled);
    transferTimeToggle.setAttribute('aria-checked', String(transferTimeLimitEnabled));
    transferTimeWrap.hidden = !transferTimeLimitEnabled;
    updateTransferTimeHint();
    clearWaitRangeErrorIfValid();
}

function updateTransferTimeHint() {
    transferTimeHint.classList.toggle('is-off', !transferTimeLimitEnabled);
    if (!transferTimeLimitEnabled) {
        // Off still has the backend's ceiling, the field's max (B41)
        transferTimeHint.textContent = `max ${fmtMins(Number(maxTransferTimeInput.max))}`;
        return;
    }
    const minMins = minTransferMinutesOf(minTransferTimeInput.value);
    const maxMins = maxTransferMinutesOf(maxTransferTimeInput.value);
    transferTimeHint.textContent = `${minMins}–${maxMins} min`;
}
