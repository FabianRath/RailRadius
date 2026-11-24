import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
    defaultQuery, parseLinkedQuery, parseQuery, requestParamsOf, searchOptionsOf, waitHint, waitRangeIsValid
} from '../js/query.js';

const STATION = 'austria:1290401';

function fields(overrides = {}) {
    return { maxTransfers: '1', maxJourneyHours: '', minWaitMinutes: '5', maxWaitMinutes: '180', ...overrides };
}

test('the untouched form parses to the default query', () => {
    assert.deepEqual(parseQuery(fields(), true), defaultQuery());
});

test('typed numbers are clamped to their bounds', () => {
    const query = parseQuery(fields({ maxTransfers: '9', maxJourneyHours: '0.1', minWaitMinutes: '-3',
        maxWaitMinutes: '999' }), true);
    assert.equal(query.maxTransfers, 5);
    assert.equal(query.maxJourneyHours, 0.5);
    assert.equal(query.minWaitMinutes, 0);
    assert.equal(query.maxWaitMinutes, 360);
});

test('empty fields mean no transfers, no journey limit, no min wait and the usual max wait', () => {
    const query = parseQuery({ maxTransfers: '', maxJourneyHours: '', minWaitMinutes: '', maxWaitMinutes: '' }, true);
    assert.deepEqual(query, {
        maxTransfers: 0, maxJourneyHours: null, waitLimited: true, minWaitMinutes: 0, maxWaitMinutes: 180
    });
});

test('a typed 0 max wait is clamped to the smallest max, not read as empty (B57)', () => {
    assert.equal(parseQuery(fields({ maxWaitMinutes: '0' }), true).maxWaitMinutes, 1);
});

test('a min wait above the max is invalid only while the limit is on (B52)', () => {
    const texts = fields({ minWaitMinutes: '200', maxWaitMinutes: '100' });
    assert.equal(waitRangeIsValid(parseQuery(texts, true)), false);
    assert.equal(waitRangeIsValid(parseQuery(texts, false)), true);
    assert.equal(waitRangeIsValid(defaultQuery()), true);
});

test('the limit switched off searches the whole wait range but keeps the typed waits', () => {
    const query = parseQuery(fields({ minWaitMinutes: '10', maxWaitMinutes: '60' }), false);
    assert.equal(query.minWaitMinutes, 10);
    assert.deepEqual(searchOptionsOf(STATION, query),
        { stationId: STATION, maxTransfers: 1, minTransferMinutes: 0, maxTransferMinutes: 360 });
});

test('request params add the journey-hours limit only when there is one', () => {
    assert.equal('maxJourneyHours' in requestParamsOf(STATION, defaultQuery()), false);
    const query = parseQuery(fields({ maxJourneyHours: '2.5' }), true);
    assert.deepEqual(requestParamsOf(STATION, query), {
        stationId: STATION, maxTransfers: 1, minTransferMinutes: 5, maxTransferMinutes: 180, maxJourneyHours: 2.5
    });
});

test('the hint shows the range the search will use', () => {
    assert.equal(waitHint(parseQuery(fields({ maxWaitMinutes: '' }), true)), '5–180 min');
    assert.equal(waitHint({ ...defaultQuery(), waitLimited: false }), 'max 6h');
});

test('linked values that are no number keep the default, others are clamped', () => {
    const query = parseLinkedQuery({ maxTransfers: 'x', maxJourneyHours: null, minTransferMinutes: '',
        maxTransferMinutes: '0' });
    assert.deepEqual(query, { ...defaultQuery(), maxWaitMinutes: 1 });
});

test('a linked whole wait range turns the limit off with the waits at their defaults (B56)', () => {
    const query = parseLinkedQuery({ maxTransfers: '2', maxJourneyHours: null, minTransferMinutes: '0',
        maxTransferMinutes: '360' });
    assert.deepEqual(query, { ...defaultQuery(), maxTransfers: 2, waitLimited: false });
});

test('a query survives the round trip through its request params', () => {
    const queries = [
        defaultQuery(),
        { ...defaultQuery(), waitLimited: false },
        { maxTransfers: 3, maxJourneyHours: 4.5, waitLimited: true, minWaitMinutes: 0, maxWaitMinutes: 90 }
    ];
    queries.forEach(query => {
        const texts = Object.fromEntries(Object.entries(requestParamsOf(STATION, query))
            .map(([key, value]) => [key, String(value)]));
        assert.deepEqual(parseLinkedQuery({ maxJourneyHours: null, ...texts }), query);
    });
});
