import { searchStations } from './api.js';
import { countryBadgeHtml, escapeHtml } from './util.js';
import { icon } from './icons.js';

const SEARCH_DEBOUNCE_MS = 300;
const MIN_QUERY_LENGTH = 2;

const searchInput = document.getElementById('searchInput');
const stationResults = document.getElementById('stationResults');

/** onSelect receives { id, name } of the clicked station. */
export function initSearch(onSelect) {
    let debounceTimer;
    searchInput.addEventListener('input', () => {
        clearTimeout(debounceTimer);
        debounceTimer = setTimeout(() => runSearch(searchInput.value), SEARCH_DEBOUNCE_MS);
    });
    stationResults.addEventListener('click', e => {
        const item = e.target.closest('.station-item');
        if (!item) return;
        searchInput.value = item.dataset.name;
        stationResults.innerHTML = '';
        onSelect({ id: item.dataset.id, name: item.dataset.name });
    });
}

/** Selects the station with exactly this name as an example, unless the user has started typing meanwhile. */
export async function prefillStation(name, onSelect) {
    try {
        const station = (await searchStations(name)).find(s => s.name === name);
        if (!station || searchInput.value) return;
        searchInput.value = station.name;
        onSelect({ id: station.id, name: station.name });
    } catch (error) {
        console.error('Example station lookup failed:', error);
    }
}

async function runSearch(query) {
    if (!query || query.length < MIN_QUERY_LENGTH) {
        stationResults.innerHTML = '';
        return;
    }
    try {
        const stations = await searchStations(query);
        stationResults.innerHTML = stations.length === 0
            ? '<div class="no-results">No stations found</div>'
            : `<div class="station-list">${stations.map(stationItemHtml).join('')}</div>`;
    } catch (error) {
        console.error('Search error:', error);
        stationResults.innerHTML = `<div class="no-results">${icon('triangle-alert')} Backend connection failed</div>`;
    }
}

function stationItemHtml(station) {
    const detail = station.connections != null
        ? `${station.connections} connection${station.connections !== 1 ? 's' : ''}`
        : station.id;
    return `<div class="station-item" data-id="${escapeHtml(station.id)}" data-name="${escapeHtml(station.name)}">`
        + `<div class="station-name">${escapeHtml(station.name)}${countryBadgeHtml(station.country)}</div>`
        + `<div class="station-id">${escapeHtml(detail)}</div>`
        + '</div>';
}
