import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const css = (await readFile(new URL('../styles.css', import.meta.url), 'utf8'))
  .replace(/\/\*[\s\S]*?\*\//g, '');

// Read the real base rules, including the variable used by the input border.
function declarations(selector) {
  const rules = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)]
    .filter(([, selectors]) => selectors.split(',').some(value => value.trim() === selector));
  assert.ok(rules.length, `Missing CSS rule: ${selector}`);
  return Object.fromEntries(rules.flatMap(([, , body]) => body.split(';')
    .filter(value => value.trim()).map(value => {
      const colon = value.indexOf(':');
      return [value.slice(0, colon).trim(), value.slice(colon + 1).trim()];
    })));
}
const root = declarations(':root');
const input = declarations('input:not([type=radio]):not([type=checkbox])');
const panel = declarations('.work-area');
function resolve(value) {
  return value.replace(/var\((--[\w-]+)\)/g, (_, name) => {
    assert.ok(root[name], `Missing CSS token: ${name}`);
    return root[name];
  });
}
function luminance(color) {
  assert.match(color, /^#[\da-f]{6}$/i, `Expected opaque six-digit CSS color: ${color}`);
  const channels = color.slice(1).match(/../g).map(hex => {
    const value = parseInt(hex, 16) / 255;
    return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  });
  return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722;
}
function contrast(first, second) {
  const a = luminance(first), b = luminance(second);
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
}

for (const [name, background] of [['panel', panel.background], ['input fill', input.background]]) {
  test(`input boundary has at least 3:1 contrast against ${name}`, t => {
    const border = resolve(input['border-color'] ?? input.border);
    const match = border.match(/^(?:[1-9]\d*(?:\.\d+)?px solid )?(#[\da-f]{6})$/i);
    assert.ok(match, `Expected a visible opaque input border, got: ${border}`);
    const adjacent = resolve(background);
    const ratio = contrast(match[1], adjacent);
    t.diagnostic(`${match[1]} against ${adjacent}: ${ratio.toFixed(3)}:1`);
    assert.ok(ratio >= 3, `${name}: ${match[1]} against ${adjacent} is ${ratio.toFixed(3)}:1; requires >= 3:1`);
  });
}
