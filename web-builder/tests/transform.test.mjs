import test from 'node:test';
import assert from 'node:assert/strict';
import {OLD, NEW, ANDROID, MANIFEST, RESOURCES, options, enc, cat, field, unknown, attr, node, prim, str, manifest, resources, entries} from './transform-fixtures.mjs';
let transformAabEntries;
try { ({transformAabEntries} = await import('../core/transform.mjs')); }
catch (error) { if (error.code !== 'ERR_MODULE_NOT_FOUND') throw error; }

test('rejects invalid personal identity and version before entries processing', () => {
  assert.equal(typeof transformAabEntries, 'function');
  for (const applicationId of ['com.openautolink', 'com.openautolink.template.user', 'com.openautolink.app', 'Com.example.id', 'com.class.id', 'single', '', null]) {
    assert.throws(() => transformAabEntries([], {applicationId, versionCode: 2}), /OAL_PERSONALIZE_IDENTITY/);
  }
  for (const versionCode of [0, -1, 2100000001, 1.5, '2', true, null]) {
    assert.throws(() => transformAabEntries([], {applicationId:'com.example.personal', versionCode}), /OAL_PERSONALIZE_VERSION/);
  }
});

test('rewrites exactly six manifest identities and resource package while preserving raw unknown bytes', () => {
  const input = entries();
  const snapshot = input.map(e=>({name:e.name,data:e.data.slice()}));
  const output = transformAabEntries(input,options);
  assert.deepEqual(output.map(e=>e.name), input.map(e=>e.name));
  assert.deepEqual(output[0].data, manifest({old:NEW,version:42}));
  assert.deepEqual(output[1].data, resources(NEW));
  for(let i=2;i<input.length;i++) assert.deepEqual(output[i].data,input[i].data);
  assert.deepEqual(input,snapshot);
});
