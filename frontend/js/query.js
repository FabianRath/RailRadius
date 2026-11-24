import { fmtMins } from './util.js';

// The longest wait at a change the backend allows (SearchOptions.MAX_TRANSFER_WAIT_MINUTES)
const MAX_WAIT_MINUTES = 360;

/** Each number's bounds. The backend rejects values outside them (B41), so values are clamped to them. */
export const QUERY_BOUNDS = {
    maxTransfers: { min: 0, max: 5 },
    maxJourneyHours: { min: 0.5, max: 24 },
    minWaitMinutes: { min: 0, max: MAX_WAIT_MINUTES },
    maxWaitMinutes: { min: 1, max: MAX_WAIT_MINUTES }
};

// Links leave out values at their default, so changing one changes what existing links mean. They differ from the
// backend's on purpose: those only fill in what a request leaves out, and the form always sends the waits.
const DEFAULT_QUERY = {
    maxTransfers: 1,
    maxJourneyHours: null,
    waitLimited: true,
    minWaitMinutes: 5,
    maxWaitMinutes: 180
};

// An empty field means no transfers and no min wait, but the usual max wait rather than its smallest (B57)
const EMPTY_FIELD_QUERY = {
    maxTransfers: QUERY_BOUNDS.maxTransfers.min,
    maxJourneyHours: null,
    minWaitMinutes: QUERY_BOUNDS.minWaitMinutes.min,
    maxWaitMinutes: DEFAULT_QUERY.maxWaitMinutes
};

const PARSERS = {
    maxTransfers: parseWhole,
    maxJourneyHours: parseFloat,
    minWaitMinutes: parseWhole,
    maxWaitMinutes: parseWhole
};

/** The query the untouched form holds. */
export function defaultQuery() {
    return { ...DEFAULT_QUERY };
}

/**
 * The query from the form's field texts (keyed like a query) and its wait-limit switch. Text that is no number
 * gives the empty field's value; a number, also 0, is clamped to its bounds.
 */
export function parseQuery(texts, waitLimited) {
    return queryOf(texts, waitLimited, EMPTY_FIELD_QUERY);
}

/**
 * The query from untrusted request parameters (requestParamsOf's keys, a missing one null), as a link holds them.
 * Text that is no number keeps the default. A link holds no switch (B56): the whole wait range is what the limit
 * switched off sends, so it turns the limit off, with the waits back at their defaults for when it is turned on.
 */
export function parseLinkedQuery(texts) {
    const query = queryOf({
        maxTransfers: texts.maxTransfers,
        maxJourneyHours: texts.maxJourneyHours,
        minWaitMinutes: texts.minTransferMinutes,
        maxWaitMinutes: texts.maxTransferMinutes
    }, true, DEFAULT_QUERY);
    const limitOff = query.minWaitMinutes === 0 && query.maxWaitMinutes === MAX_WAIT_MINUTES;
    if (!limitOff) return query;
    return {
        ...query,
        waitLimited: false,
        minWaitMinutes: DEFAULT_QUERY.minWaitMinutes,
        maxWaitMinutes: DEFAULT_QUERY.maxWaitMinutes
    };
}

/** Whether the backend accepts the query's waits: the min not above the max (B52). Off, the range is always valid. */
export function waitRangeIsValid(query) {
    return !query.waitLimited || query.minWaitMinutes <= query.maxWaitMinutes;
}

/** The wait range the search will use, in short. */
export function waitHint(query) {
    // Off still has the backend's ceiling (B41)
    if (!query.waitLimited) return `max ${fmtMins(MAX_WAIT_MINUTES)}`;
    return `${query.minWaitMinutes}–${query.maxWaitMinutes} min`;
}

/** The search options from the start station; with the wait limit off, the whole range the backend allows. */
export function searchOptionsOf(stationId, query) {
    return {
        stationId,
        maxTransfers: query.maxTransfers,
        minTransferMinutes: query.waitLimited ? query.minWaitMinutes : 0,
        maxTransferMinutes: query.waitLimited ? query.maxWaitMinutes : MAX_WAIT_MINUTES
    };
}

/** What a search request sends: the search options, plus the journey-hours limit when there is one. */
export function requestParamsOf(stationId, query) {
    const params = searchOptionsOf(stationId, query);
    if (query.maxJourneyHours != null) params.maxJourneyHours = query.maxJourneyHours;
    return params;
}

function queryOf(texts, waitLimited, fallback) {
    const query = { waitLimited };
    Object.entries(QUERY_BOUNDS).forEach(([key, bounds]) => {
        const value = PARSERS[key](texts[key]);
        query[key] = Number.isNaN(value) ? fallback[key] : Math.min(bounds.max, Math.max(bounds.min, value));
    });
    return query;
}

function parseWhole(text) {
    return parseInt(text, 10);
}
