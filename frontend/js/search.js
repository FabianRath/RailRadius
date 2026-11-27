import { searchStations } from './api.js';
import { countryBadgeHtml, escapeHtml } from './util.js';
import { icon } from './icons.js';
import { isUpdatingTimetables } from './server-status.js';

const SEARCH_DEBOUNCE_MS = 300;
const MIN_QUERY_LENGTH = 2;

const searchInput = document.getElementById('searchInput');
const searchCountry = document.getElementById('searchCountry');
const stationResults = document.getElementById('stationResults');
const searchCell = searchInput.closest('.board-from');
// The shown results by id, so a click hands on the whole station, coordinates included
const stationsById = new Map();

/** onSelect receives the clicked station as the search returns it (id, name, latitude, longitude, …). */
export function initSearch(onSelect) {
    let debounceTimer;
    searchInput.addEventListener('input', () => {
        searchCountry.textContent = '';
        searchInput.title = '';
        clearTimeout(debounceTimer);
        debounceTimer = setTimeout(() => runSearch(searchInput.value), SEARCH_DEBOUNCE_MS);
    });
    stationResults.addEventListener('click', e => {
        const item = e.target.closest('.station-item');
        if (!item) return;
        stationResults.innerHTML = '';
        const station = stationsById.get(item.dataset.id);
        if (station) setStation(station, onSelect);
    });
    // The list covers the map, so it closes like a dropdown; stopPropagation keeps Escape from also closing the journey
    searchInput.addEventListener('keydown', e => {
        if (e.key !== 'Escape') return;
        e.stopPropagation();
        stationResults.innerHTML = '';
    });
    document.addEventListener('click', e => {
        if (!searchCell.contains(e.target)) stationResults.innerHTML = '';
    });
}

/** Selects the station with exactly this name as an example, unless the user has started typing meanwhile. */
export async function prefillStation(name, onSelect) {
    try {
        const station = (await searchStations(name)).find(s => s.name === name);
        if (!station || searchInput.value) return;
        setStation(station, onSelect);
    } catch (error) {
        console.error('Example station lookup failed:', error);
    }
}

/** Shows the station in the search field and selects it, as if it had been picked from the results. */
export function setStation(station, onSelect) {
    searchInput.value = station.name;
    // The field fits 99 % of the names; the rest are cut off with an ellipsis
    searchInput.title = station.name;
    searchCountry.textContent = station.country ?? '';
    onSelect(station);
}

async function runSearch(query) {
    if (!query || query.length < MIN_QUERY_LENGTH) {
        stationResults.innerHTML = '';
        return;
    }
    try {
        const stations = await searchStations(query);
        stationsById.clear();
        stations.forEach(s => stationsById.set(s.id, s));
        // During a reload the index is empty; the notice explains that better than "No stations found"
        if (stations.length === 0 && await isUpdatingTimetables()) {
            stationResults.innerHTML = '';
            return;
        }
        stationResults.innerHTML = stations.length === 0
            ? '<div class="no-results">No stations found</div>'
            : `<div class="station-list">${stations.map(stationItemHtml).join('')}</div>`;
    } catch (error) {
        console.error('Search error:', error);
        if (await isUpdatingTimetables()) {
            stationResults.innerHTML = '';
            return;
        }
        stationResults.innerHTML = `<div class="no-results">${icon('triangle-alert')} Backend connection failed</div>`;
    }
}

function stationItemHtml(station) {
    const detail = `${station.connections} connection${station.connections !== 1 ? 's' : ''}`;
    return `<div class="station-item" data-id="${escapeHtml(station.id)}" data-name="${escapeHtml(station.name)}">`
        + `<div class="station-name">${escapeHtml(station.name)}${countryBadgeHtml(station.country)}</div>`
        + `<div class="station-id">${escapeHtml(detail)}</div>`
        + '</div>';
}
