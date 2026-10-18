import test from 'node:test';
import assert from 'node:assert/strict';
import { transformAabEntries } from '../core/transform.mjs';
import { entries } from './transform-fixtures.mjs';
const api = await import('../core/ui-state.mjs').catch(e => {
  if (e.code === 'ERR_MODULE_NOT_FOUND') return {};
  throw e;
});
test('new application IDs are unique lowercase Android IDs accepted by the real transformer', () => {
  assert.equal(typeof api.newApplicationId, 'function');
  const ids = new Set();
  for (let i = 0; i < 100; i++) {
    const id = api.newApplicationId();
    assert.match(id, /^com\.personal\.oal\.user[a-z]{32}$/);
    assert.doesNotThrow(() => transformAabEntries(entries(), { applicationId: id, versionCode: 1 }));
    ids.add(id);
  }
  assert.equal(ids.size, 100);
});
