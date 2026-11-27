import { test } from 'node:test';
import assert from 'node:assert/strict';
import { otherTheme, resolveTheme } from '../js/theme-rules.js';

test('without a stored choice the system setting decides', () => {
    assert.equal(resolveTheme(null, true), 'dark');
    assert.equal(resolveTheme(null, false), 'light');
});

test('a stored choice overrides the system setting', () => {
    assert.equal(resolveTheme('light', true), 'light');
    assert.equal(resolveTheme('dark', false), 'dark');
});

test('an unknown stored value falls back to the system setting', () => {
    assert.equal(resolveTheme('sepia', true), 'dark');
});

test('the toggle flips between the two themes', () => {
    assert.equal(otherTheme('light'), 'dark');
    assert.equal(otherTheme('dark'), 'light');
});
