import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
    contentVersionOf, engineWaitMs, MAX_ENGINE_WAIT_MS, matchesServer, mayRunEngine, maxJourneyMinutesOf,
    NO_JOURNEY_LIMIT_MINUTES, timeLimited
} from '../js/engine-rules.js';

function desktop(overrides = {}) {
    return { mobile: false, finePointer: true, deviceMemoryGb: 8, hasWorker: true, hasWebAssembly: true,
        ...overrides };
}

test('a desktop with enough memory searches in the browser', () => {
    assert.equal(mayRunEngine(desktop()), true);
});

test('a browser that reports no memory tries', () => {
    assert.equal(mayRunEngine(desktop({ deviceMemoryGb: undefined })), true);
});

test('too little memory stays on the server', () => {
    assert.equal(mayRunEngine(desktop({ deviceMemoryGb: 4 })), false);
});

test('phones and touch-only devices stay on the server', () => {
    assert.equal(mayRunEngine(desktop({ mobile: true })), false);
    assert.equal(mayRunEngine(desktop({ finePointer: false })), false);
});

test('a browser without workers or WebAssembly stays on the server', () => {
    assert.equal(mayRunEngine(desktop({ hasWorker: false })), false);
    assert.equal(mayRunEngine(desktop({ hasWebAssembly: false })), false);
});

test('the journey limit is cut to whole minutes like the server does, none is no limit', () => {
    assert.equal(maxJourneyMinutesOf({ maxJourneyHours: 1.51 }), 90);
    assert.equal(maxJourneyMinutesOf({}), NO_JOURNEY_LIMIT_MINUTES);
});

test('the browser matches the server only with the same data, engine and today', () => {
    const server = { version: 'aaaa', engineVersion: 'eeee', today: '2026-11-24', path: '/x', sizeBytes: 1 };
    const loaded = { version: 'aaaa', engineVersion: 'eeee', today: '2026-11-24' };
    assert.equal(matchesServer(loaded, server), true);
    assert.equal(matchesServer({ ...loaded, version: 'bbbb' }, server), false);
    assert.equal(matchesServer({ ...loaded, engineVersion: 'ffff' }, server), false);
    assert.equal(matchesServer({ ...loaded, today: '2026-11-25' }, server), false);
    assert.equal(matchesServer(null, server), false);
});

test('a content version is the first 8 bytes of the SHA-256 in hex, as the server names files', async () => {
    assert.equal(await contentVersionOf(new TextEncoder().encode('abc')), 'ba7816bf8f01cfea');
});

test('a search waits for the engine until five seconds after its load started, then not at all', () => {
    assert.equal(MAX_ENGINE_WAIT_MS, 5000);
    assert.equal(engineWaitMs(10_000, 14_000), 1000);
    assert.equal(engineWaitMs(10_000, 16_000), 0);
});

test('an answer within the time limit comes through', async () => {
    assert.equal(await timeLimited(Promise.resolve(42), 50, 'too slow'), 42);
});

test('no answer within the time limit fails with its message', async () => {
    await assert.rejects(timeLimited(new Promise(() => {}), 10, 'too slow'), { message: 'too slow' });
});
