import test from 'node:test';
import assert from 'node:assert/strict';
import * as transform from '../core/transform.mjs';
import { entries } from './transform-fixtures.mjs';
import { readFile } from 'node:fs/promises';
import { runInNewContext } from 'node:vm';
import { createIdentity, backupIdentity, restoreIdentity } from '../core/identity.mjs';

const incompatibleIds = [
  'com.example.MyCar', 'com.openautolink.app', 'com.example.class',
  'com.openautolink.template', 'com.openautolink',
  'com.openautolink.template.extra', 'xcom.openautolink.templatex',
  'com.' + 'a'.repeat(147), 'com.1car', 'com.car-', 'com.car\n',
  'com', '', null, undefined, 42,
];
const compatibleIds = [
  'com.example.car', 'com.example.my_car2', 'com.example.classy',
  'com.openautolink.app.personal', 'com.' + 'a'.repeat(146),
];

test('shared personal application ID preflight preserves exact transformer constraints', () => {
  assert.equal(typeof transform.validatePersonalApplicationId, 'function');
  for (const applicationId of incompatibleIds) {
    assert.throws(() => transform.validatePersonalApplicationId(applicationId), { message: 'OAL_PERSONALIZE_IDENTITY' });
    assert.throws(() => transform.transformAabEntries(entries(), { applicationId, versionCode: 1 }), { message: 'OAL_PERSONALIZE_IDENTITY' });
  }
  for (const applicationId of compatibleIds) {
    assert.doesNotThrow(() => transform.validatePersonalApplicationId(applicationId));
    assert.doesNotThrow(() => transform.transformAabEntries(entries(), { applicationId, versionCode: 1 }));
  }
});

// Execute the actual app with a minimal DOM and injected module bindings. This
// observes event-handler effects without adding test/debug APIs to shipping code.
async function identityForm({ applicationId, updating = false, backup }) {
  const controls = new Map();
  const $ = selector => {
    if (!controls.has(selector)) controls.set(selector, {
      value: '', files: [], hidden: true, disabled: false,
      addEventListener() {}, focus() {},
    });
    return controls.get(selector);
  };
  const password = 'disposable preflight password';
  $('#password').value = $('#confirm-password').value = password;
  $('input[name=mode]:checked').value = updating ? 'update' : 'create';
  $('#play-version').value = '7';
  $('#prepare');
  if (backup) $('#restore-file').files = [{ size: backup.length, arrayBuffer: async () => backup.slice().buffer }];
  const calls = { create: 0, backup: 0 };
  const source = await readFile(new URL('../app.mjs', import.meta.url), 'utf8');
  const context = {
    document: { querySelector: $, querySelectorAll: selector => selector === '#steps li' ? [] : [...controls.values()] },
    window: { addEventListener() {} },
    TextEncoder, Uint8Array, AbortController,
    newApplicationId: () => applicationId,
    validatePersonalApplicationId: transform.validatePersonalApplicationId,
    createIdentity: async id => { calls.create++; return createIdentity(id); },
    restoreIdentity: async (...args) => { calls.restored = await restoreIdentity(...args); return calls.restored; },
    backupIdentity: async (...args) => { calls.backup++; return backupIdentity(...args); },
  };
  const prepare = runInNewContext(source.replace(/^import .*;\n/gm, '') + `
    template = { release: { sourceRef: 'local-test' } };
    prepareIdentity;
  `, context);
  await prepare({ preventDefault() {} });
  return { calls, controls, prepared: runInNewContext('identity', context) };
}

test('UI explicit creation rejects incompatible IDs before generating a key or backup', async t => {
  for (const applicationId of ['com.example.MyCar', 'com.openautolink.app', 'com.example.class']) {
    await t.test(applicationId, async () => {
      const form = await identityForm({ applicationId });
      assert.equal(form.calls.create, 0);
      assert.equal(form.calls.backup, 0);
      assert.equal(form.prepared, null);
      assert.match(form.controls.get('#error').textContent, /application ID.*not compatible/i);
      assert.equal(form.controls.get('#prepare').disabled, false);
    });
  }
});

test('UI rejects a restored incompatible ID without replacing its key or preparing another backup', async t => {
  for (const applicationId of ['com.example.MyCar', 'com.openautolink.app', 'com.example.class']) {
    await t.test(applicationId, async () => {
      const original = await createIdentity(applicationId);
      original.versionCodeHighWaterMark = 7;
      const backup = await backupIdentity(original, 'disposable preflight password');
      const before = backup.slice();
      const form = await identityForm({ updating: true, backup });
      assert.equal(form.calls.create, 0);
      assert.deepEqual(form.calls.restored, original);
      assert.equal(form.calls.backup, 0);
      assert.equal(form.prepared, null);
      assert.deepEqual(backup, before);
      assert.equal(form.controls.get('#error').hidden, false);
      assert.match(form.controls.get('#error').textContent, /application ID.*not compatible/i);
      assert.equal(form.controls.get('#prepare').disabled, false);
    });
  }
});

test('UI compatible creation and restoration still prepare the exact identity and encrypted backup', async () => {
  const created = await identityForm({ applicationId: 'com.example.car' });
  assert.equal(created.calls.create, 1);
  assert.equal(created.calls.backup, 1);
  assert.equal(created.prepared.applicationId, 'com.example.car');
  assert.equal(created.controls.get('#error').hidden, true);
  assert.match(created.controls.get('#status').textContent, /Identity prepared/);
  const backup = await backupIdentity(created.prepared, 'disposable preflight password');
  const restored = await identityForm({ updating: true, backup });
  assert.equal(restored.calls.create, 0);
  assert.equal(restored.calls.backup, 1);
  assert.deepEqual(restored.prepared, created.prepared);
  assert.equal(restored.controls.get('#error').hidden, true);
  assert.match(restored.controls.get('#status').textContent, /Identity prepared/);
});
