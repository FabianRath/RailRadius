import { isUpdatingTimetables } from './server-status.js';
import { initMap, jumpToBounds, LAYERS, onEmptyMapClick, onPointClick, onZoomEnd } from './map.js';
import { closeStationDetails, initStationDetails, showStationDetails } from './station-details.js';
import { initSearch, prefillStation, setStation } from './search.js';
import { applyQuery, initQueryForm, showQueryForm } from './query-form.js';
import { calculate, getJourney, initReachability, refreshCoverage } from './reachability.js';
import { initDebugOverlay, startDebugPolling, stopDebugPolling } from './debug.js';
import { initDevAuth } from './dev-auth.js';
import { hideAdminPanel, initAdminPanel, showAdminEntry } from './admin-panel.js';
import { showStatus } from './util.js';
import { renderStaticIcons } from './icons.js';
import { initAbout } from './about.js';
import { initIntroHint } from './intro-hint.js';
import { fetchCoverage, fetchStation } from './api.js';
import { boundsOfCountries } from './country-bounds.js';
import { readDeepLink } from './deep-link.js';

const COVERAGE_REDRAW_DEBOUNCE_MS = 300;
// Pre-filled for new visitors: small enough that its 1-transfer map isn't cluttered
const EXAMPLE_STATION_NAME = 'Krems Bahnhof';

let selectedStation = null;

isUpdatingTimetables();

renderStaticIcons();
// Requested alongside the map load; until it answers, the map shows the default view
const coverageBounds = fetchCoverage()
    .then(({ countries }) => boundsOfCountries(countries))
    .catch(error => {
        console.error('Coverage request failed:', error);
        return null;
    });
await initMap('map');
initAbout();
initStationDetails();
onPointClick(LAYERS.reachable, async ({ stopId }) => {
    try {
        showStationDetails(await getJourney(stopId));
    } catch (error) {
        console.error('Journey request failed:', error);
        if (await isUpdatingTimetables()) return;
        showStatus('error', 'Could not load the journey to this station');
    }
});
onEmptyMapClick(closeStationDetails);

function selectStation(station) {
    selectedStation = station;
    showQueryForm();
}

initIntroHint();
initSearch(selectStation);
initQueryForm(refreshCoverage);
initReachability(() => selectedStation);

const deepLink = readDeepLink();
const searchedFromLink = deepLink ? searchFromLink(deepLink) : Promise.resolve(false);
searchedFromLink.then(searched => {
    if (!searched) prefillStation(EXAMPLE_STATION_NAME, selectStation);
});
// A linked search fits the view to its results, so a late coverage reply must not jump away from them
Promise.all([coverageBounds, searchedFromLink]).then(([bounds, searched]) => {
    if (bounds && !searched) jumpToBounds(bounds);
});

/** Runs the search a shared link describes; answers whether it could, i.e. whether its station exists. */
async function searchFromLink({ stationId, query }) {
    let station;
    try {
        station = await fetchStation(stationId);
    } catch (error) {
        console.error('Linked station lookup failed:', error);
        if (!await isUpdatingTimetables()) showStatus('error', 'Station from the link not found');
        return false;
    }
    applyQuery(query);
    setStation(station, selectStation);
    calculate(station);
    return true;
}

// Coverage buffers depend on the zoom level
let zoomTimer;
onZoomEnd(() => {
    clearTimeout(zoomTimer);
    zoomTimer = setTimeout(refreshCoverage, COVERAGE_REDRAW_DEBOUNCE_MS);
});

initDebugOverlay();
initAdminPanel();
initDevAuth(
    () => {
        startDebugPolling();
        showAdminEntry();
    },
    () => {
        stopDebugPolling();
        hideAdminPanel();
    });
