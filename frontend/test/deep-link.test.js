import { test } from 'node:test';
import assert from 'node:assert/strict';
import { formatLink, parseLink } from '../js/deep-link.js';
import { defaultQuery } from '../js/query.js';

const STATION = 'austria:1290401';

test('a link at the defaults holds only the station and the changes', () => {
    assert.equal(formatLink(STATION, defaultQuery()), 'from=austria:1290401&transfers=1');
});

test('a link holds the values that differ from the defaults', () => {
    const query = { ...defaultQuery(), maxTransfers: 3, maxJourneyHours: 2.5, minWaitMinutes: 10 };
    assert.equal(formatLink(STATION, query), 'from=austria:1290401&transfers=3&hours=2.5&minwait=10');
});

test('the wait limit switched off is written as the whole wait range (B56)', () => {
    const query = { ...defaultQuery(), waitLimited: false, minWaitMinutes: 10, maxWaitMinutes: 60 };
    assert.equal(formatLink(STATION, query), 'from=austria:1290401&transfers=1&minwait=0&maxwait=360');
});

test('a link without a start station is no search', () => {
    assert.equal(parseLink('?transfers=2'), null);
});

test('a link with only the station opens the default query', () => {
    assert.deepEqual(parseLink('?from=austria:1290401'), { stationId: STATION, query: defaultQuery() });
});

test('a link with the whole wait range opens with the limit off (B56)', () => {
    const { query } = parseLink('?from=austria:1290401&transfers=2&minwait=0&maxwait=360');
    assert.deepEqual(query, { ...defaultQuery(), maxTransfers: 2, waitLimited: false });
});

test('linked values are clamped, and ones that are no number keep the default', () => {
    const { query } = parseLink('?from=austria:1290401&transfers=9&hours=abc&minwait=-5&maxwait=120');
    assert.deepEqual(query, { ...defaultQuery(), maxTransfers: 5, minWaitMinutes: 0, maxWaitMinutes: 120 });
});

test('a query survives the round trip through a link', () => {
    const queries = [
        defaultQuery(),
        { ...defaultQuery(), waitLimited: false },
        { maxTransfers: 0, maxJourneyHours: 24, waitLimited: true, minWaitMinutes: 200, maxWaitMinutes: 100 }
    ];
    queries.forEach(query => {
        assert.deepEqual(parseLink(`?${formatLink(STATION, query)}`), { stationId: STATION, query });
    });
});
