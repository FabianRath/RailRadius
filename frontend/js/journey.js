/**
 * The journey to a reachable station as map lines, with the train's index as level so each train gets the colour of
 * the stations reached with that many transfers. coordinatesOf(stopId) gives a stop's [lon, lat], or null.
 *
 * A leg with a track (#110) follows it: the stop pairs the tracks join as one line along them, each pair they don't
 * as a straight line marked unmatched, so the map can draw it apart. A leg without one (the server has no track
 * network) is one straight line through every stop it calls at, as before.
 */
export function journeyRoute(station, coordinatesOf) {
    const path = station.path;
    const trainLegs = station.legs.filter(leg => leg.type === 'TRAIN');
    // A train can pass a station twice, so each leg is looked up after the previous one
    let searchFrom = 0;
    const lines = trainLegs.flatMap((leg, level) => {
        const fromIndex = path.indexOf(leg.fromStopId, searchFrom);
        const toIndex = path.indexOf(leg.toStopId, fromIndex + 1);
        searchFrom = toIndex;
        const stops = path.slice(fromIndex, toIndex + 1).map(coordinatesOf);
        return leg.track ? trackLines(stops, leg.track, level) : [stopLine(stops, level)];
    });
    return { stopIds: path, lines: lines.filter(line => line.coordinates.length > 1) };
}

/** Consecutive pairs of one kind become one line, so a train's line along the tracks has no joints. */
function trackLines(stops, track, level) {
    const lines = [];
    track.forEach((pairLine, i) => {
        const unmatched = !pairLine;
        const coordinates = unmatched ? [stops[i], stops[i + 1]].filter(Boolean) : pairLine;
        const current = lines.at(-1);
        if (current && current.unmatched === unmatched) {
            current.coordinates.push(...coordinates.slice(1));
        } else {
            lines.push({ coordinates: [...coordinates], level, unmatched });
        }
    });
    return lines;
}

function stopLine(stops, level) {
    return { coordinates: stops.filter(Boolean), level, unmatched: false };
}
