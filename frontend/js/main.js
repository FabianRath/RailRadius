import { fetchHealth } from './api.js';
import { bindPopup, clearJourney, initMap, LAYERS, onZoomEnd, showJourney } from './map.js';
import { journeyRoute } from './journey.js';
import { stationPopupHtml } from './popup.js';
import { initSearch } from './search.js';
import { initSidebarToggle } from './sidebar.js';
import { initQueryForm, showQueryForm } from './query-form.js';
import { getStation, initReachability, refreshCoverage } from './reachability.js';
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
bindPopup(LAYERS.reachable, ({ stopId }) => {
    const station = getStation(stopId);
    showJourney(journeyRoute(station));
    return stationPopupHtml(station);
}, clearJourney);

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
