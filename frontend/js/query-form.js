import { fmtMins } from './util.js';

const HINT_MIN_TRANSFER_MINUTES = 5;
const DEFAULT_MAX_TRANSFER_MINUTES = 180;
// Sent when the wait-time limit is switched off; large enough to never constrain a search
const UNLIMITED_TRANSFER_MINUTES = 99999;

const calculateBtn = document.getElementById('calculateBtn');
const transferSlider = document.getElementById('transferSlider');
const transferValue = document.getElementById('transferValue');
const maxJourneyTimeInput = document.getElementById('maxJourneyTime');
const transferTimeToggle = document.getElementById('transferTimeToggle');
const transferTimeHint = document.getElementById('transferTimeToggleHint');
const transferTimeWrap = document.getElementById('transferTimeInputWrap');
const minTransferTimeInput = document.getElementById('minTransferTime');
const maxTransferTimeInput = document.getElementById('maxTransferTime');
const coverageToggle = document.getElementById('showMergedZones');

let transferTimeLimitEnabled = true;

export function initQueryForm(onCoverageToggle) {
    transferSlider.addEventListener('input', () => { transferValue.textContent = transferSlider.value; });
    transferTimeToggle.addEventListener('click', toggleTransferTimeLimit);
    minTransferTimeInput.addEventListener('input', updateTransferTimeHint);
    maxTransferTimeInput.addEventListener('input', updateTransferTimeHint);
    coverageToggle.addEventListener('change', onCoverageToggle);
    updateTransferTimeHint();
}

export function showQueryForm() {
    calculateBtn.disabled = false;
}

export function coverageEnabled() {
    return coverageToggle.checked;
}

export function readQuery() {
    return {
        maxTransfers: parseInt(transferSlider.value),
        maxJourneyHours: maxJourneyTimeInput.value ? parseFloat(maxJourneyTimeInput.value) : null,
        minTransferMinutes: transferTimeLimitEnabled ? (parseInt(minTransferTimeInput.value) || 0) : 0,
        maxTransferMinutes: transferTimeLimitEnabled
            ? (parseInt(maxTransferTimeInput.value) || DEFAULT_MAX_TRANSFER_MINUTES)
            : UNLIMITED_TRANSFER_MINUTES
    };
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
        transferTimeHint.textContent = 'off';
        return;
    }
    const minMins = parseInt(minTransferTimeInput.value) || HINT_MIN_TRANSFER_MINUTES;
    const maxMins = parseInt(maxTransferTimeInput.value) || DEFAULT_MAX_TRANSFER_MINUTES;
    transferTimeHint.textContent = `${fmtMins(minMins)} – ${fmtMins(maxMins)}`;
}
