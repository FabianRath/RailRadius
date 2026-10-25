// The info button under the zoom buttons: which countries the data covers, that connections may be wrong, and a
// Sources tab crediting every feed's publisher and licence.
import { fetchCoverage } from './api.js';
import { addMapButton } from './map.js';
import { closeOnBackdropClick, selectTab } from './util.js';

const COUNTRY_NAMES = new Intl.DisplayNames(['en'], { type: 'region' });

const dialog = document.getElementById('aboutPanel');
const countryList = document.getElementById('aboutCountries');
const tabs = dialog.querySelectorAll('[data-tab]');
const views = dialog.querySelectorAll('[data-view]');

let countriesLoaded = false;

export function initAbout() {
    const openButton = document.getElementById('aboutOpen');
    addMapButton(openButton);
    openButton.hidden = false;
    openButton.addEventListener('click', () => {
        dialog.showModal();
        if (!countriesLoaded) loadCountries();
    });
    document.getElementById('aboutClose').addEventListener('click', () => dialog.close());
    closeOnBackdropClick(dialog);
    tabs.forEach(tab => tab.addEventListener('click', () => selectTab(tabs, views, tab.dataset.tab)));
}

async function loadCountries() {
    try {
        const { countries } = await fetchCoverage();
        countryList.replaceChildren(...countries.map(countryName).sort().map(listItem));
        countriesLoaded = true;
    } catch (e) {
        console.error('Coverage request failed:', e);
        countryList.replaceChildren(listItem('Country list unavailable'));
    }
}

// Codes the browser doesn't know (made-up or non-ISO ones) are shown as they are
function countryName(code) {
    try {
        return COUNTRY_NAMES.of(code) ?? code;
    } catch {
        return code;
    }
}

function listItem(text) {
    const item = document.createElement('li');
    item.textContent = text;
    return item;
}
