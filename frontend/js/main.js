import { isUpdatingTimetables } from './server-status.js';
import { initMap, LAYERS, onEmptyMapClick, onPointClick, onZoomEnd } from './map.js';
import { closeStationDetails, initStationDetails, showStationDetails } from './station-details.js';
import { initSearch, prefillStation } from './search.js';
import { initSidebarToggle } from './sidebar.js';
import { initQueryForm, showQueryForm } from './query-form.js';
import { getJourney, initReachability, refreshCoverage } from './reachability.js';
import { initDebugOverlay, startDebugPolling, stopDebugPolling } from './debug.js';
import { initDevAuth } from './dev-auth.js';
import { hideAdminPanel, initAdminPanel, showAdminEntry } from './admin-panel.js';
import { showStatus } from './util.js';
import { renderStaticIcons } from './icons.js';
import { initAbout } from './about.js';
import { initIntroHint } from './intro-hint.js';

const COVERAGE_REDRAW_DEBOUNCE_MS = 300;
// Pre-filled for new visitors: small enough that its 1-transfer map isn't cluttered
const EXAMPLE_STATION_NAME = 'Krems Bahnhof';

let selectedStation = null;

isUpdatingTimetables();

renderStaticIcons();
initSidebarToggle();
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
prefillStation(EXAMPLE_STATION_NAME, selectStation);
initQueryForm(refreshCoverage);
initReachability(() => selectedStation);

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
