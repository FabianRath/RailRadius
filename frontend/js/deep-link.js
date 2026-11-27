import { defaultQuery, parseLinkedQuery, requestParamsOf } from './query.js';

const STATION_PARAM = 'from';
const TRANSFERS_PARAM = 'transfers';
// Request params at their default are left out of a link, so the usual one is just the station and the changes
const OPTION_PARAMS = {
    maxJourneyHours: 'hours',
    minTransferMinutes: 'minwait',
    maxTransferMinutes: 'maxwait'
};

/** The search in the page's URL as { stationId, query }, or null without a start station. */
export function readDeepLink() {
    return parseLink(location.search);
}

/** Puts the search into the URL without a history entry, so the URL always shows the last search that ran. */
export function writeDeepLink(stationId, query) {
    // PROTOTYPE: keep the variant and basemap switches in the URL
    const keep = new URLSearchParams(location.search);
    const proto = ['variant', 'basemap'].filter(key => keep.has(key)).map(key => `&${key}=${keep.get(key)}`).join('');
    history.replaceState(null, '', `${location.pathname}?${formatLink(stationId, query)}${proto}${location.hash}`);
}

/** The search in a URL's query string as { stationId, query }, or null without a start station. */
export function parseLink(search) {
    const params = new URLSearchParams(search);
    const stationId = params.get(STATION_PARAM);
    if (!stationId) return null;
    const texts = { maxTransfers: params.get(TRANSFERS_PARAM) };
    Object.entries(OPTION_PARAMS).forEach(([key, name]) => { texts[key] = params.get(name); });
    return { stationId, query: parseLinkedQuery(texts) };
}

/** The URL query string, without its "?", for a search from the station. */
export function formatLink(stationId, query) {
    const request = requestParamsOf(stationId, query);
    const defaults = requestParamsOf(stationId, defaultQuery());
    const params = new URLSearchParams({ [STATION_PARAM]: stationId, [TRANSFERS_PARAM]: request.maxTransfers });
    Object.entries(OPTION_PARAMS).forEach(([key, name]) => {
        if (request[key] != null && request[key] !== defaults[key]) params.set(name, request[key]);
    });
    // Stop IDs are full of colons, which a query string may hold as they are
    return params.toString().replaceAll('%3A', ':');
}
