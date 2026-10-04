import { fetchHealth } from './api.js';
import { bindPopup, initMap, LAYERS, onZoomEnd } from './map.js';
import { stationPopupHtml } from './popup.js';
import { initSearch } from './search.js';
import { initSidebarToggle } from './sidebar.js';
import { initQueryForm, showQueryForm } from './query-form.js';
import { getStation, initReachability, refreshCoverage } from './reachability.js';
import { startDebugPolling } from './debug.js';
import { showStatus } from './util.js';

const COVERAGE_REDRAW_DEBOUNCE_MS = 300;

let selectedStation = null;

fetchHealth()
    .then(health => console.log('Backend health:', health))
    .catch(error => {
        console.error('Backend connection failed:', error);
        showStatus('error', 'Backend not reachable');
    });

initSidebarToggle();
await initMap('map');
bindPopup(LAYERS.reachable, ({ stopId }) => stationPopupHtml(getStation(stopId)));

initSearch(station => {
    selectedStation = station;
    showQueryForm();
    showStatus('info', `Selected: ${station.name}`);
});
initQueryForm(refreshCoverage);
initReachability(() => selectedStation);

// Coverage buffers depend on the zoom level
let zoomTimer;
onZoomEnd(() => {
    clearTimeout(zoomTimer);
    zoomTimer = setTimeout(refreshCoverage, COVERAGE_REDRAW_DEBOUNCE_MS);
});

startDebugPolling();
