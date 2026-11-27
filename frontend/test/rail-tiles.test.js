import { test } from 'node:test';
import assert from 'node:assert/strict';
import { railTileSource } from '../js/rail-tiles.js';

const TILE_JSON = {
    tilejson: '3.0.0', tiles: ['/tiles/rail/{z}/{x}/{y}.pbf'], minzoom: 4, maxzoom: 12, bounds: [9.5, 45.7, 22.9, 49.6]
};

test('the source requests the tiles from the page origin, keeping the template placeholders', () => {
    const source = railTileSource(TILE_JSON, 'http://localhost:8081');
    assert.deepEqual(source.tiles, ['http://localhost:8081/tiles/rail/{z}/{x}/{y}.pbf']);
});

test('the source keeps the zoom range and bounds, so MapLibre requests no tile outside them', () => {
    const source = railTileSource(TILE_JSON, 'https://example.org');
    assert.equal(source.type, 'vector');
    assert.equal(source.minzoom, 4);
    assert.equal(source.maxzoom, 12);
    assert.deepEqual(source.bounds, [9.5, 45.7, 22.9, 49.6]);
});
