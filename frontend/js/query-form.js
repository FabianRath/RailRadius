import { defaultQuery, parseQuery, QUERY_BOUNDS, waitHint, waitRangeIsValid } from './query.js';

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

// Each query number's field
const FIELDS = {
    maxTransfers: transferInput,
    maxJourneyHours: maxJourneyTimeInput,
    minWaitMinutes: minTransferTimeInput,
    maxWaitMinutes: maxTransferTimeInput
};

let transferTimeLimitEnabled = defaultQuery().waitLimited;

/** onAreasToggle(visible) runs now and whenever the "areas" option changes; the browser may restore it on reload. */
export function initQueryForm(onAreasToggle) {
    initFields();
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

/** The query the form holds; typed values are clamped to their bounds. */
export function readQuery() {
    const texts = Object.fromEntries(Object.entries(FIELDS).map(([key, input]) => [key, input.value]));
    return parseQuery(texts, transferTimeLimitEnabled);
}

/**
 * Whether readQuery() is a search the backend accepts. If not, opens the Wait panel at the message saying why, since
 * the backend would only answer with its validation error (B52).
 */
export function checkQuery() {
    const valid = waitRangeIsValid(readQuery());
    showWaitRangeError(!valid);
    if (!valid) setOptionsOpen(true);
    return valid;
}

/** Sets the fields and the wait-limit switch to a query, e.g. a link's. */
export function applyQuery(query) {
    Object.entries(FIELDS).forEach(([key, input]) => { input.value = query[key] ?? ''; });
    setTransferTimeLimitEnabled(query.waitLimited);
    updateStepperButtons();
}

// Sets the default rather than the value, so a value the browser restores on reload stays
function initFields() {
    const defaults = defaultQuery();
    Object.entries(FIELDS).forEach(([key, input]) => {
        input.min = QUERY_BOUNDS[key].min;
        input.max = QUERY_BOUNDS[key].max;
        input.defaultValue = defaults[key] ?? '';
    });
}

// stepUp()/stepDown() fire no input event, so the buttons call this themselves
function updateStepperButtons() {
    const transfers = readQuery().maxTransfers;
    transferMinus.disabled = transfers <= QUERY_BOUNDS.maxTransfers.min;
    transferPlus.disabled = transfers >= QUERY_BOUNDS.maxTransfers.max;
}

function showWaitRangeError(shown) {
    transferTimeError.hidden = !shown;
    [minTransferTimeInput, maxTransferTimeInput].forEach(input => {
        input.classList.toggle('is-invalid', shown);
        input.setAttribute('aria-invalid', String(shown));
    });
}

function clearWaitRangeErrorIfValid() {
    if (waitRangeIsValid(readQuery())) showWaitRangeError(false);
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
    transferTimeHint.textContent = waitHint(readQuery());
}
