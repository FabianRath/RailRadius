import { test } from 'node:test';
import assert from 'node:assert/strict';
import { journeyRoute } from '../js/journey.js';

const COORDINATES = { A: [16.0, 48.0], B: [16.1, 48.0], C: [16.2, 48.0], D: [16.3, 48.0], X: null };
const coordinatesOf = stopId => COORDINATES[stopId];

function train(fromStopId, toStopId, track) {
    return { type: 'TRAIN', fromStopId, toStopId, track };
}

test('without a track a train is one solid line through its stops', () => {
    const route = journeyRoute({ path: ['A', 'B', 'C'], legs: [train('A', 'C')] }, coordinatesOf);

    assert.deepEqual(route.lines, [{ coordinates: [[16.0, 48.0], [16.1, 48.0], [16.2, 48.0]], level: 0,
        unmatched: false }]);
});

test('stop pairs along the tracks join into one line', () => {
    const track = [[[16.0, 48.0], [16.05, 48.1], [16.1, 48.0]], [[16.1, 48.0], [16.15, 48.1], [16.2, 48.0]]];
    const route = journeyRoute({ path: ['A', 'B', 'C'], legs: [train('A', 'C', track)] }, coordinatesOf);

    assert.deepEqual(route.lines, [{ coordinates: [[16.0, 48.0], [16.05, 48.1], [16.1, 48.0], [16.15, 48.1],
        [16.2, 48.0]], level: 0, unmatched: false }]);
});

test('a stop pair off the tracks is a straight line of its own, marked unmatched', () => {
    const track = [[[16.0, 48.0], [16.05, 48.1], [16.1, 48.0]], null, null];
    const route = journeyRoute({ path: ['A', 'B', 'C', 'D'], legs: [train('A', 'D', track)] }, coordinatesOf);

    assert.deepEqual(route.lines.map(line => [line.unmatched, line.coordinates.length]), [[false, 3], [true, 3]]);
    assert.deepEqual(route.lines[1].coordinates, [[16.1, 48.0], [16.2, 48.0], [16.3, 48.0]]);
});

test('each train gets the level of its index, and a stop without coordinates draws no line', () => {
    const legs = [train('A', 'B', [null]), { type: 'TRANSFER', fromStopId: 'B', toStopId: 'B' }, train('B', 'X', [null])];
    const route = journeyRoute({ path: ['A', 'B', 'X'], legs }, coordinatesOf);

    assert.deepEqual(route.lines, [{ coordinates: [[16.0, 48.0], [16.1, 48.0]], level: 0, unmatched: true }]);
    assert.deepEqual(route.stopIds, ['A', 'B', 'X']);
});
