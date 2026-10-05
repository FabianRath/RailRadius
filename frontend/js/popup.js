import { countryBadgeHtml, escapeHtml, fmtGtfsClock } from './util.js';

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
        if (leg.type === 'TRAIN') return trainLegHtml(leg, duration);
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

/** Train number, times and direction, so the train can be found on the operator's site. */
function trainLegHtml(leg, duration) {
    const { title, line } = trainTitle(leg);
    const lineHtml = line ? ` <span class="leg-detail">line ${escapeHtml(line)}</span>` : '';
    const times = `${fmtGtfsClock(leg.departureTime)} ${escapeHtml(leg.fromStation)}`
        + ` → ${fmtGtfsClock(leg.arrivalTime)} ${escapeHtml(leg.toStation)}`;
    const details = [leg.headsign && `towards ${leg.headsign}`, leg.operator].filter(Boolean);
    const detailsHtml = details.length
        ? `<div class="leg-detail">${escapeHtml(details.join(' · '))}</div>` : '';
    return `<div class="leg leg-train"><div>🚂 ${escapeHtml(title)}${lineHtml}${duration}</div>`
        + `<div class="leg-times">${times}</div>${detailsHtml}</div>`;
}

/**
 * Feeds split a train's name differently between route name and train number: "Ex 42" + "42", "ICE" + "2",
 * "A10-1" + "RJ 658", "33" + "2310". Shows the route name as a line only when it adds something.
 */
function trainTitle(leg) {
    const name = leg.trainName;
    const number = leg.trainNumber;
    if (!number) return { title: name || 'Train' };
    const numeric = /^\d+$/.test(number);
    const numberTitle = numeric ? `Train ${number}` : number;
    if (!name || number.split(/\s+/).includes(name)) return { title: numberTitle };
    if (name.split(/\s+/).includes(number)) return { title: name };
    if (numeric && /^\p{L}+$/u.test(name)) return { title: `${name} ${number}` };
    return { title: numberTitle, line: name };
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
