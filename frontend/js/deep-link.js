import { defaultQuery } from './query-form.js';

const STATION_PARAM = 'from';
const TRANSFERS_PARAM = 'transfers';
// Options at their default are left out of a link, so the usual one is just the station and the changes
const OPTION_PARAMS = {
    maxJourneyHours: 'hours',
    minTransferMinutes: 'minwait',
    maxTransferMinutes: 'maxwait'
};

/**
 * The search in the page's URL as { stationId, query }, or null without a start station. The query holds the
 * parameters as raw text (null if missing), for applyQuery to validate.
 */
export function readDeepLink() {
    const params = new URLSearchParams(location.search);
    const stationId = params.get(STATION_PARAM);
    if (!stationId) return null;
    const query = { maxTransfers: params.get(TRANSFERS_PARAM) };
    Object.entries(OPTION_PARAMS).forEach(([key, name]) => { query[key] = params.get(name); });
    return { stationId, query };
}

/** Puts the search into the URL without a history entry, so the URL always shows the last search that ran. */
export function writeDeepLink(stationId, query) {
    const defaults = defaultQuery();
    const params = new URLSearchParams({ [STATION_PARAM]: stationId, [TRANSFERS_PARAM]: query.maxTransfers });
    Object.entries(OPTION_PARAMS).forEach(([key, name]) => {
        if (query[key] != null && query[key] !== defaults[key]) params.set(name, query[key]);
    });
    // Stop IDs are full of colons, which a query string may hold as they are
    const search = params.toString().replaceAll('%3A', ':');
    history.replaceState(null, '', `${location.pathname}?${search}${location.hash}`);
}
