import { fmtMins } from './util.js';

const HINT_MIN_TRANSFER_MINUTES = 5;
const DEFAULT_MAX_TRANSFER_MINUTES = 180;

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
const coverageToggle = document.getElementById('showMergedZones');
const optionsCell = document.querySelector('.board-options');
const optionsToggle = document.getElementById('optionsToggle');
const optionsPanel = document.getElementById('optionsPanel');

let transferTimeLimitEnabled = true;

export function initQueryForm(onCoverageToggle) {
    transferMinus.addEventListener('click', () => { transferInput.stepDown(); updateStepperButtons(); });
    transferPlus.addEventListener('click', () => { transferInput.stepUp(); updateStepperButtons(); });
    transferInput.addEventListener('input', updateStepperButtons);
    transferTimeToggle.addEventListener('click', toggleTransferTimeLimit);
    minTransferTimeInput.addEventListener('input', updateTransferTimeHint);
    maxTransferTimeInput.addEventListener('input', updateTransferTimeHint);
    coverageToggle.addEventListener('change', onCoverageToggle);
    initOptionsPanel();
    updateStepperButtons();
    updateTransferTimeHint();
}

export function showQueryForm() {
    calculateBtn.disabled = false;
}

export function coverageEnabled() {
    return coverageToggle.checked;
}

// The backend rejects values outside the fields' min/max (B41), so typed values are clamped to them. With the wait
// limit off, the whole range the backend allows is searched.
export function readQuery() {
    return {
        maxTransfers: clampTransfers(transferInput.value),
        maxJourneyHours: maxJourneyTimeInput.value
            ? clampToField(maxJourneyTimeInput, parseFloat(maxJourneyTimeInput.value))
            : null,
        minTransferMinutes: transferTimeLimitEnabled
            ? clampToField(minTransferTimeInput, parseInt(minTransferTimeInput.value) || 0)
            : 0,
        maxTransferMinutes: transferTimeLimitEnabled
            ? clampToField(maxTransferTimeInput, parseInt(maxTransferTimeInput.value) || DEFAULT_MAX_TRANSFER_MINUTES)
            : Number(maxTransferTimeInput.max)
    };
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

function toggleTransferTimeLimit() {
    transferTimeLimitEnabled = !transferTimeLimitEnabled;
    transferTimeToggle.classList.toggle('is-on', transferTimeLimitEnabled);
    transferTimeToggle.setAttribute('aria-checked', String(transferTimeLimitEnabled));
    transferTimeWrap.hidden = !transferTimeLimitEnabled;
    updateTransferTimeHint();
}

function updateTransferTimeHint() {
    transferTimeHint.classList.toggle('is-off', !transferTimeLimitEnabled);
    if (!transferTimeLimitEnabled) {
        // Off still has the backend's ceiling, the field's max (B41)
        transferTimeHint.textContent = `max ${fmtMins(Number(maxTransferTimeInput.max))}`;
        return;
    }
    const minMins = parseInt(minTransferTimeInput.value) || HINT_MIN_TRANSFER_MINUTES;
    const maxMins = parseInt(maxTransferTimeInput.value) || DEFAULT_MAX_TRANSFER_MINUTES;
    transferTimeHint.textContent = `${minMins}–${maxMins} min`;
}
