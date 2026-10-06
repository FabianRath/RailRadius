import { getStation } from './reachability.js';
import { hasCoordinates } from './map.js';

/**
 * The journey to a reachable station as map lines: one per train through every stop it calls at, with the train's
 * index as level so it gets the colour of the stations reached with that many transfers. Every stop on the way is a
 * reachable station itself, so its coordinates come from the results.
 */
export function journeyRoute(station) {
    const path = station.path;
    const trainLegs = station.legs.filter(leg => leg.type === 'TRAIN');
    // A train can pass a station twice, so each leg is looked up after the previous one
    let searchFrom = 0;
    const lines = trainLegs.map((leg, level) => {
        const fromIndex = path.indexOf(leg.fromStopId, searchFrom);
        const toIndex = path.indexOf(leg.toStopId, fromIndex + 1);
        searchFrom = toIndex;
        return line(path.slice(fromIndex, toIndex + 1), level);
    });
    return { stopIds: path, lines };
}

function line(stopIds, level) {
    const coordinates = stopIds
        .map(getStation)
        .filter(s => s && hasCoordinates(s.latitude, s.longitude))
        .map(s => [s.longitude, s.latitude]);
    return { coordinates, level };
}
