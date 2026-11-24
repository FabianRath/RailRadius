import { test } from 'node:test';
import assert from 'node:assert/strict';
import { withLatinLabels } from '../js/map-labels.js';

const WITHOUT_LOCAL_SCRIPT = ['coalesce', ['get', 'name_en'], ['get', 'name']];
const BOTH_SCRIPTS = ['case', ['has', 'name:nonlatin'],
    ['concat', ['get', 'name:latin'], '\n', ['get', 'name:nonlatin']], WITHOUT_LOCAL_SCRIPT];

function style(...layers) {
    return { version: 8, sources: {}, layers };
}

test('a label in two scripts keeps only its Latin name', () => {
    const [layer] = withLatinLabels(style({ id: 'place_city', type: 'symbol', layout: { 'text-field': BOTH_SCRIPTS,
        'text-font': ['Noto Sans Regular'] } })).layers;

    assert.deepEqual(layer.layout['text-field'],
        ['case', ['has', 'name:nonlatin'], ['get', 'name:latin'], WITHOUT_LOCAL_SCRIPT]);
    assert.deepEqual(layer.layout['text-font'], ['Noto Sans Regular']);
});

test('other labels and layers stay as they are', () => {
    const motorway = { id: 'highway_name_motorway', type: 'symbol', layout: { 'text-field': ['to-string', ['get', 'ref']] } };
    const water = { id: 'water', type: 'fill', paint: { 'fill-color': 'blue' } };

    assert.deepEqual(withLatinLabels(style(motorway, water)).layers, [motorway, water]);
});
