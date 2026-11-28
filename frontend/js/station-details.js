// The selected station's journey, in its own column beside the map so it never covers the route drawn on it (F22).
import { countryBadgeHtml, escapeHtml, fmtChanges, fmtGtfsClock } from './util.js';
import { icon } from './icons.js';
import { clearJourney, hasCoordinates, MAX_TRANSFER_LEVEL, resizeMap, showJourney } from './map.js';
import { journeyRoute } from './journey.js';
import { getStation } from './reachability.js';

const panel = document.getElementById('stationDetails');
const body = document.getElementById('stationDetailsBody');
const closeButton = document.getElementById('stationDetailsClose');

export function initStationDetails() {
    closeButton.addEventListener('click', closeStationDetails);
    document.addEventListener('keydown', e => {
        if (e.key === 'Escape') closeStationDetails();
    });
}

/** Lists the journey in the journey column and draws it on the map, hiding all stations not on it. */
export function showStationDetails(station) {
    body.innerHTML = stationDetailsHtml(station);
    body.scrollTop = 0;
    panel.hidden = false;
    closeButton.focus();
    showJourney(journeyRoute(station, stopCoordinates));
    resizeMap();
}

/** Every stop on the way is a reachable station itself, so its coordinates come from the results. */
function stopCoordinates(stopId) {
    const station = getStation(stopId);
    return station && hasCoordinates(station.latitude, station.longitude)
        ? [station.longitude, station.latitude] : null;
}

export function closeStationDetails() {
    if (panel.hidden) return;
    panel.hidden = true;
    clearJourney();
    resizeMap();
}

function stationDetailsHtml(station) {
    return summaryHtml(station)
        + runningDaysHtml(station)
        + roundTripHtml(station)
        + journeyHtml(station);
}

function summaryHtml(station) {
    const trainLegs = (station.legs ?? []).filter(leg => leg.type === 'TRAIN');
    const times = trainLegs.length
        ? `${fmtGtfsClock(trainLegs[0].departureTime)} – ${fmtGtfsClock(trainLegs.at(-1).arrivalTime)}` : '';
    const meta = [times, station.totalDuration, changesText(station.transfers)].filter(Boolean).join(' · ');
    return '<div class="journey-summary">'
        + `<div class="journey-dest">${escapeHtml(station.stopName)}${countryBadgeHtml(station.country)}</div>`
        + (meta ? `<div class="journey-meta">${escapeHtml(meta)}</div>` : '')
        + '</div>';
}

function changesText(transfers) {
    if (transfers == null) return '';
    if (transfers === 0) return 'direct';
    return fmtChanges(transfers);
}

/**
 * The search compares the trains' days by weekday and 6-week period only, so a journey can pass whose trains never all
 * run on one date (B33).
 */
function runningDaysHtml(station) {
    if (station.hasCommonDay === false) {
        return noteHtml('triangle-alert', 'No single day on which all these trains run');
    }
    if (!station.runningDays) return '';
    return noteHtml('calendar', escapeHtml(station.runningDays));
}

/**
 * The wait limit applies to each transfer, so the search can bridge a longer wait by riding away and back (F24).
 * Such a journey arrives no earlier than waiting would.
 */
function roundTripHtml(station) {
    if (!station.roundTripStation) return '';
    return noteHtml('triangle-alert',
        `Contains a round trip: passes ${escapeHtml(station.roundTripStation)} twice`);
}

function noteHtml(iconName, textHtml) {
    return `<div class="journey-note">${icon(iconName)}<span>${textHtml}</span></div>`;
}

function journeyHtml(station) {
    if (station.legs?.length) return tableHtml(legsHtml(station.legs));
    if (station.path?.length > 1) return tableHtml(pathHtml(station));
    return '';
}

function tableHtml(rowsHtml) {
    return '<div class="journey-thead"><span class="board-label">Time</span><span class="board-label">Station</span>'
        + '<span class="board-label journey-train">Train</span></div>'
        + rowsHtml;
}

function legsHtml(legs) {
    let trainIndex = 0;
    return legs.map(leg => {
        if (leg.type === 'TRAIN') return trainLegHtml(leg, trainIndex++);
        if (leg.type === 'TRANSFER') return changeHtml(leg.fromStation, leg.duration);
        return '';
    }).join('');
}

/** Train number, times and direction, so the train can be found on the operator's site. */
function trainLegHtml(leg, trainIndex) {
    const sub = [leg.headsign && `towards ${leg.headsign}`, leg.operator].filter(Boolean).join(' · ');
    const from = `<span class="journey-from">${escapeHtml(leg.fromStation)}</span>${countryBadgeHtml(leg.fromCountry)}`
        + (sub ? `<div class="journey-sub">${escapeHtml(sub)}</div>` : '');
    // Same index journeyRoute gives the train's line, so the badge matches its colour on the map
    const level = Math.min(trainIndex, MAX_TRANSFER_LEVEL);
    const badge = `<span class="train-badge level-${level}">${escapeHtml(trainTitle(leg))}</span>`;
    const duration = `<span class="journey-duration">${escapeHtml(leg.duration)}</span>`;
    return rowHtml(timeHtml(leg.departureTime), from, badge)
        + rowHtml(timeHtml(leg.arrivalTime), arrivalHtml(leg.toStation), duration);
}

function rowHtml(timeCellHtml, stationHtml, trainHtml) {
    return `<div class="journey-row"><div>${timeCellHtml}</div><div class="journey-station">${stationHtml}</div>`
        + `<div class="journey-train">${trainHtml}</div></div>`;
}

function arrivalHtml(stationName) {
    return `<span class="journey-to">→ ${escapeHtml(stationName)}</span>`;
}

function changeHtml(stationName, duration) {
    const text = [`Change at ${stationName}`, duration].filter(Boolean).join(' · ');
    return `<div class="journey-change">${escapeHtml(text)}</div>`;
}

/** fmtGtfsClock appends the day offset as " +N"; the board shows it smaller beside the clock. */
function timeHtml(gtfsTime) {
    const [clock, dayOffset] = fmtGtfsClock(gtfsTime).split(' ');
    return `<span class="journey-time">${clock}</span>`
        + (dayOffset ? `<small class="journey-day">${dayOffset}</small>` : '');
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
    const names = station.path.map((id, i) => station.pathNames?.[i] || id);
    const noTime = '<span class="journey-time">—</span>';
    return rowHtml(noTime, `<span class="journey-from">${escapeHtml(names[0])}</span>`, '')
        + names.slice(1, -1).map(name => changeHtml(name)).join('')
        + rowHtml(noTime, arrivalHtml(names.at(-1)), '');
}
