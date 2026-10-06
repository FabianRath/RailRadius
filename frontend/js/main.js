import { fetchHealth } from './api.js';
import { initMap, LAYERS, onEmptyMapClick, onPointClick, onZoomEnd } from './map.js';
import { closeStationDetails, initStationDetails, showStationDetails } from './station-details.js';
import { initSearch } from './search.js';
import { initSidebarToggle } from './sidebar.js';
import { initQueryForm, showQueryForm } from './query-form.js';
import { getJourney, initReachability, refreshCoverage } from './reachability.js';
import { initDebugOverlay, startDebugPolling, stopDebugPolling } from './debug.js';
import { initDevAuth } from './dev-auth.js';
import { hideAdminPanel, initAdminPanel, showAdminEntry } from './admin-panel.js';
import { showStatus } from './util.js';
import { renderStaticIcons } from './icons.js';

const COVERAGE_REDRAW_DEBOUNCE_MS = 300;

let selectedStation = null;

fetchHealth()
    .then(health => console.log('Backend health:', health))
    .catch(error => {
        console.error('Backend connection failed:', error);
        showStatus('error', 'Backend not reachable');
    });

renderStaticIcons();
initSidebarToggle();
await initMap('map');
initStationDetails();
onPointClick(LAYERS.reachable, async ({ stopId }) => {
    try {
        showStationDetails(await getJourney(stopId));
    } catch (error) {
        console.error('Journey request failed:', error);
        showStatus('error', 'Could not load the journey to this station');
    }
});
onEmptyMapClick(closeStationDetails);

initSearch(station => {
    selectedStation = station;
    showQueryForm();
});
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
