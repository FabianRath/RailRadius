import { countryBadgeHtml, escapeHtml } from './util.js';

export function stationPopupHtml(station) {
    return `<div class="popup-station">${escapeHtml(station.stopName)}${countryBadgeHtml(station.country)}</div>`
        + `<div class="popup-meta">Transfers: ${station.transfers}</div>`
        + journeyHtml(station);
}

function journeyHtml(station) {
    if (station.legs?.length) return legsHtml(station);
    if (station.path?.length > 1) return pathHtml(station);
    return '';
}

function legsHtml(station) {
    const legs = station.legs.map(leg => {
        const duration = leg.duration ? ` <span class="leg-duration">(${escapeHtml(leg.duration)})</span>` : '';
        if (leg.type === 'TRAIN') {
            return `<div class="leg leg-train">🚂 ${escapeHtml(leg.trainName || 'Train')}${duration}</div>`;
        }
        if (leg.type === 'TRANSFER') {
            const station = escapeHtml(leg.fromStation) + countryBadgeHtml(leg.fromCountry);
            return `<div class="leg leg-transfer">🔄 Transfer at ${station}${duration}</div>`;
        }
        return '';
    }).join('');
    return '<div class="popup-path">'
        + `<div class="popup-total">⏱️ Total Journey Time: ${escapeHtml(station.totalDuration || 'N/A')}</div>`
        + '<div class="popup-heading">Journey details</div>'
        + `<div class="leg leg-stop">🚉 START: ${escapeHtml(station.pathNames?.[0] || 'Start')}`
        + `${countryBadgeHtml(station.legs[0].fromCountry)}</div>`
        + legs
        + `<div class="leg leg-stop">🎯 DESTINATION: ${escapeHtml(station.stopName)}`
        + `${countryBadgeHtml(station.country)}</div>`
        + '</div>';
}

/** Fallback when the backend sent no legs: only the station sequence is known. */
function pathHtml(station) {
    const lastIndex = station.path.length - 1;
    const stops = station.path.map((id, i) => {
        const name = escapeHtml(station.pathNames?.[i] || id);
        if (i === 0) return `<div class="leg leg-stop">🚉 START: ${name}</div>`;
        if (i === lastIndex) return `<div class="leg leg-stop">🎯 DESTINATION: ${name}</div>`;
        return `<div class="leg leg-transfer">🔄 TRANSFER: ${name}</div>`;
    }).join('');
    return `<div class="popup-path"><div class="popup-heading">Connection</div>${stops}</div>`;
}
