// The selected station's journey, shown over the side panel so it never covers the route drawn on the map (F22).
import { countryBadgeHtml, escapeHtml, fmtGtfsClock } from './util.js';
import { icon } from './icons.js';
import { clearJourney, showJourney } from './map.js';
import { journeyRoute } from './journey.js';
import { expandSidebar } from './sidebar.js';

const panel = document.getElementById('stationDetails');
const body = document.getElementById('stationDetailsBody');
const closeButton = document.getElementById('stationDetailsClose');

export function initStationDetails() {
    closeButton.addEventListener('click', closeStationDetails);
    document.addEventListener('keydown', e => {
        if (e.key === 'Escape') closeStationDetails();
    });
}

/** Lists the journey in the side panel and draws it on the map, hiding all stations not on it. */
export function showStationDetails(station) {
    body.innerHTML = stationDetailsHtml(station);
    body.scrollTop = 0;
    panel.hidden = false;
    expandSidebar();
    closeButton.focus();
    showJourney(journeyRoute(station));
}

export function closeStationDetails() {
    if (panel.hidden) return;
    panel.hidden = true;
    clearJourney();
}

function stationDetailsHtml(station) {
    return `<div class="popup-station">${escapeHtml(station.stopName)}${countryBadgeHtml(station.country)}</div>`
        + `<div class="popup-meta">Transfers: ${station.transfers}</div>`
        + runningDaysHtml(station)
        + journeyHtml(station);
}

/** The search only checks consecutive trains, so a journey can pass whose trains never all run on one day (B25). */
function runningDaysHtml(station) {
    if (station.hasCommonDay === false) {
        return `<div class="popup-meta popup-warning">${icon('triangle-alert')}`
            + ' No single day on which all these trains run</div>';
    }
    if (!station.runningDays) return '';
    return `<div class="popup-meta">${icon('calendar')} ${escapeHtml(station.runningDays)}</div>`;
}

function journeyHtml(station) {
    if (station.legs?.length) return legsHtml(station);
    if (station.path?.length > 1) return pathHtml(station);
    return '';
}

function legsHtml(station) {
    const legs = station.legs.map(leg => {
        const duration = leg.duration ? ` <span class="leg-duration">(${escapeHtml(leg.duration)})</span>` : '';
        if (leg.type === 'TRAIN') return trainLegHtml(leg, duration);
        if (leg.type === 'TRANSFER') {
            const station = escapeHtml(leg.fromStation) + countryBadgeHtml(leg.fromCountry);
            return `<div class="leg leg-transfer">${icon('arrow-left-right')} Transfer at ${station}${duration}</div>`;
        }
        return '';
    }).join('');
    return '<div class="popup-path">'
        + `<div class="popup-total">${icon('clock')} Total Journey Time: ${escapeHtml(station.totalDuration || 'N/A')}</div>`
        + '<div class="popup-heading">Journey details</div>'
        + `<div class="leg leg-stop">${icon('map-pin')} START: ${escapeHtml(station.pathNames?.[0] || 'Start')}`
        + `${countryBadgeHtml(station.legs[0].fromCountry)}</div>`
        + legs
        + `<div class="leg leg-stop">${icon('flag')} DESTINATION: ${escapeHtml(station.stopName)}`
        + `${countryBadgeHtml(station.country)}</div>`
        + '</div>';
}

/** Train number, times and direction, so the train can be found on the operator's site. */
function trainLegHtml(leg, duration) {
    const times = `${fmtGtfsClock(leg.departureTime)} ${escapeHtml(leg.fromStation)}`
        + ` → ${fmtGtfsClock(leg.arrivalTime)} ${escapeHtml(leg.toStation)}`;
    const details = [leg.headsign && `towards ${leg.headsign}`, leg.operator].filter(Boolean);
    const detailsHtml = details.length
        ? `<div class="leg-detail">${escapeHtml(details.join(' · '))}</div>` : '';
    return `<div class="leg leg-train"><div>${icon('train-front')} ${escapeHtml(trainTitle(leg))}${duration}</div>`
        + `<div class="leg-times">${times}</div>${detailsHtml}</div>`;
}

/**
 * Feeds split a train's name differently between route name and train number: "Ex 42" + "42", "ICE" + "2",
 * "A10-1" + "RJ 658", "33" + "ICE 2310". Shows what is written on the train; line numbers like "A10-1" or "33"
 * mean nothing to passengers, so a route name that is no category is dropped.
 */
function trainTitle(leg) {
    const name = leg.trainName;
    const number = leg.trainNumber;
    if (!number) return name || 'Train';
    const numeric = /^\d+$/.test(number);
    if (name?.split(/\s+/).includes(number)) return name;
    if (numeric && name && /^\p{L}+$/u.test(name)) return `${name} ${number}`;
    return numeric ? `Train ${number}` : number;
}

/** Fallback when the backend sent no legs: only the station sequence is known. */
function pathHtml(station) {
    const lastIndex = station.path.length - 1;
    const stops = station.path.map((id, i) => {
        const name = escapeHtml(station.pathNames?.[i] || id);
        if (i === 0) return `<div class="leg leg-stop">${icon('map-pin')} START: ${name}</div>`;
        if (i === lastIndex) return `<div class="leg leg-stop">${icon('flag')} DESTINATION: ${name}</div>`;
        return `<div class="leg leg-transfer">${icon('arrow-left-right')} TRANSFER: ${name}</div>`;
    }).join('');
    return `<div class="popup-path"><div class="popup-heading">Connection</div>${stops}</div>`;
}
