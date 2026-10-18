import test, { after } from 'node:test';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import assert from 'node:assert/strict';
import { isDeepStrictEqual } from 'node:util';
import { createHash } from 'node:crypto';
import { readFile, readdir } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { readArchive, writeArchive } from '../core/archive.mjs';
import { createIdentity, restoreIdentity } from '../core/identity.mjs';
import { transformAabEntries } from '../core/transform.mjs';
import { entries, NEW } from './transform-fixtures.mjs';

const api = await import('../core/pipeline.mjs').catch(error => {
  if (error.code === 'ERR_MODULE_NOT_FOUND') return {};
  throw error;
});
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const password = 'disposable-unit-test-password';
let carplayFixtureDir;
after(() => {
  if (carplayFixtureDir) rmSync(carplayFixtureDir, { recursive: true, force: true });
});
function carplayFixture(name) {
  if (!carplayFixtureDir) {
    assert.ok(process.env.TMPDIR, 'Set TMPDIR to scratch outside the checkout');
    carplayFixtureDir = mkdtempSync(path.join(process.env.TMPDIR, 'pipeline-carplay-synthetic-'));
    const run = spawnSync(process.env.CARPLAY_FIXTURE_PYTHON || '/hermes_work/oal-builder-verify-venv/bin/python',
      [fileURLToPath(new URL('./carplay-fixtures/generate.py', import.meta.url)), carplayFixtureDir],
      { stdio: 'pipe', timeout: 30000 });
    assert.equal(run.status, 0, 'Synthetic fixture generation requires Python cryptography');
  }
  return new Uint8Array(readFileSync(path.join(carplayFixtureDir, name)));
}
const carplayNames = ['base/assets/offline-mfi/identity.pk8', 'base/assets/offline-mfi/certificate.p7b'];
function assertCarplayAssets(result, carplay) {
  const output = readArchive(result.aabBytes);
  assert.deepEqual(output.filter(e => e.name.includes('offline-mfi')).map(e => e.name), carplayNames);
  const manifest = new TextDecoder().decode(output.find(e => e.name === 'META-INF/MANIFEST.MF').data);
  for (const [name, bytes] of [[carplayNames[0], carplay.keyBytes], [carplayNames[1], carplay.certificateBytes]]) {
    // Do not print even synthetic credential bytes on assertion failure.
    const actual = output.find(e => e.name === name).data;
    assert.ok(actual.length === bytes.length && actual.every((byte, index) => byte === bytes[index]), 'original CarPlay bytes preserved');
    const digest = createHash('sha256').update(bytes).digest('base64');
    assert.ok(manifest.includes(`Name: ${name}\r\nSHA-256-Digest: ${digest}\r\n`), 'CarPlay asset covered by JAR manifest');
  }
  return output;
}
function fixture() {
  const templateBytes = writeArchive(entries());
  return { templateBytes, descriptor: {
    schemaVersion: 1, formatVersion: 1, releaseTag: 'v0.1.528', sourceCommit: 'a'.repeat(40),
    templateApplicationId: 'com.openautolink.template', transformContract: 'oal-identity-v1',
    templateAsset: { name: 'openautolink-template-v0.1.528.aab', size: templateBytes.length, sha256: hash(templateBytes) },
  } };
}

test('pipeline signs optional CarPlay first/update builds without placing credentials in identity backups', async () => {
  const input = fixture();
  const identity = await createIdentity(NEW);
  const before = structuredClone({ ...input, identity });
  let current = identity;
  for (const [versionCode, certificate] of [[42, 'p256.der'], [43, 'single.p7b']]) {
    // Worker messages transport raw bytes; validation provenance is local to the pipeline.
    const raw = { keyBytes: carplayFixture('p256.pk8'), certificateBytes: carplayFixture(certificate) };
    const carplay = structuredClone(raw, { transfer: [raw.keyBytes.buffer, raw.certificateBytes.buffer] });
    assert.equal(raw.keyBytes.byteLength, 0, 'source key buffer transferred');
    assert.equal(raw.certificateBytes.byteLength, 0, 'source certificate buffer transferred');
    const result = await api.buildPersonalizedAab({ ...input, identity: current, versionCode, backupPassword: password, carplay });
    const output = assertCarplayAssets(result, carplay);
    assert.deepEqual(output.slice(3, -2), transformAabEntries(readArchive(input.templateBytes), {
      applicationId: NEW, versionCode, versionName: '0.1.528',
    }));
    const expectedIdentity = { ...identity, versionCodeHighWaterMark: versionCode };
    assert.deepEqual(result.identity, expectedIdentity, 'identity contains only upload signing state');
    current = await restoreIdentity(result.backupBytes, password);
    assert.deepEqual(current, expectedIdentity, 'decrypted backup excludes both CarPlay files');
    assert.deepEqual(Object.keys(result).sort(), ['aabBytes', 'backupBytes', 'identity']);
  }
  // Restoring the upload identity must not implicitly restore CarPlay assets.
  for (const carplay of [undefined, null]) {
    const result = await api.buildPersonalizedAab({ ...input, identity: current, versionCode: 44, backupPassword: password, carplay });
    assert.ok(readArchive(result.aabBytes).every(e => !e.name.includes('offline-mfi')));
    assert.deepEqual(await restoreIdentity(result.backupBytes, password), { ...identity, versionCodeHighWaterMark: 44 });
  }
  assert.deepEqual({ ...input, identity }, before);
});

test('pipeline snapshots raw CarPlay bytes and their container synchronously before yielding', async () => {
  const input = fixture();
  const identity = await createIdentity(NEW);
  const expected = { keyBytes: carplayFixture('p256.pk8'), certificateBytes: carplayFixture('single.p7b') };
  // Buffer subarray/slice aliases expose accidental zero-copy snapshots.
  const key = Buffer.from(expected.keyBytes), certificate = Buffer.from(expected.certificateBytes);
  const carplay = { keyBytes: key.subarray(0), certificateBytes: certificate.subarray(0) };
  const pending = api.buildPersonalizedAab({ ...input, identity, versionCode: 42, backupPassword: password, carplay });
  key.fill(0);
  certificate.fill(0);
  carplay.keyBytes = null;
  carplay.certificateBytes = null;
  assertCarplayAssets(await pending, expected);
});

test('pipeline snapshots shared byte views rather than structured-clone aliases', async () => {
  const input = fixture();
  const identity = await createIdentity(NEW);
  const expected = { keyBytes: carplayFixture('p256.pk8'), certificateBytes: carplayFixture('p256.der') };
  const carplay = Object.fromEntries(Object.entries(expected).map(([name, bytes]) => {
    const view = new Uint8Array(new SharedArrayBuffer(bytes.length + 2), 1, bytes.length);
    view.set(bytes);
    return [name, view];
  }));
  const pending = api.buildPersonalizedAab({ ...input, identity, versionCode: 42, backupPassword: password, carplay });
  carplay.keyBytes.fill(0);
  carplay.certificateBytes.fill(0);
  assertCarplayAssets(await pending, expected);
});

test('pipeline rejects malformed CarPlay requests without partial output or caller-state changes', async t => {
  const input = fixture();
  const identity = await createIdentity(NEW);
  const before = structuredClone({ ...input, identity });
  const keyBytes = carplayFixture('p256.pk8'), certificateBytes = carplayFixture('p256.der');
  const cases = [
    ['empty object', {}], ['boolean', false], ['string', 'not raw bytes'], ['array', []],
    ['empty pair', { keyBytes: null, certificateBytes: null }],
    ['missing certificate', { keyBytes }], ['missing key', { certificateBytes }],
    ['mismatched X509', { keyBytes, certificateBytes: carplayFixture('other.der') }],
    ['multiple PKCS7 certificates', { keyBytes, certificateBytes: carplayFixture('multiple.p7b') }],
    ['malformed key', { keyBytes: Uint8Array.of(0), certificateBytes }],
    ['malformed certificate', { keyBytes, certificateBytes: Uint8Array.of(0) }],
    ['empty key', { keyBytes: new Uint8Array(), certificateBytes }],
    ['oversized certificate', { keyBytes, certificateBytes: new Uint8Array(16385) }],
    ['non-byte key', { keyBytes: 'not bytes', certificateBytes }],
    ['non-view certificate', { keyBytes, certificateBytes: certificateBytes.buffer }],
  ];
  for (const [label, carplay] of cases) await t.test(label, async () => {
    const rawBefore = structuredClone(carplay);
    let result;
    await assert.rejects(async () => {
      result = await api.buildPersonalizedAab({ ...input, identity, versionCode: 42, backupPassword: password, carplay });
    }, /^Error: CarPlay /);
    assert.equal(result, undefined, 'no AAB, backup or updated identity escapes a rejected request');
    assert.deepEqual({ ...input, identity }, before);
    assert.ok(isDeepStrictEqual(carplay, rawBefore), 'raw CarPlay inputs unchanged');
  });
});

test('pipeline verifies exact template SHA before interpreting its archive', async () => {
  const input = fixture();
  const identity = await createIdentity(NEW);
  input.templateBytes[0] ^= 1;
  await assert.rejects(api.buildPersonalizedAab({ ...input, identity, versionCode: 42, backupPassword: password }), /checksum mismatch/);
});

test('pipeline requires an explicit increasing Android version and preserves state on rejection', async () => {
  const input = fixture();
  const identity = { ...await createIdentity(NEW), versionCodeHighWaterMark: 42 };
  const before = structuredClone(identity);
  for (const versionCode of [42, 41, 0, -1, 1.5, '43', undefined, NaN, Infinity, 2100000001]) {
    await assert.rejects(api.buildPersonalizedAab({ ...input, identity, versionCode, backupPassword: password }), /version/i);
    assert.deepEqual(identity, before);
  }
  const result = await api.buildPersonalizedAab({ ...input, identity, versionCode: 2100000000, backupPassword: password });
  assert.equal(result.identity.versionCodeHighWaterMark, 2100000000);
  await assert.rejects(api.buildPersonalizedAab({ ...input, identity: result.identity, versionCode: 2100000000, backupPassword: password }), /version/i);
});

test('pipeline snapshots caller-owned bytes, descriptor and identity before yielding', async () => {
  const input = fixture();
  const identity = await createIdentity(NEW);
  const before = structuredClone(identity);
  const pending = api.buildPersonalizedAab({ ...input, identity, versionCode: 42, backupPassword: password });
  input.templateBytes.fill(0);
  input.descriptor.releaseTag = 'v0.0.0';
  input.descriptor.templateAsset.sha256 = '0'.repeat(64);
  identity.privateKeyPem = '';
  identity.versionCodeHighWaterMark = 99;
  const result = await pending;
  assert.deepEqual(result.identity, { ...before, versionCodeHighWaterMark: 42 });
});

test('pipeline rejects unsupported descriptor contracts even when bytes match', async () => {
  const input = fixture();
  const identity = await createIdentity(NEW);
  for (const patch of [{ schemaVersion: 2 }, { formatVersion: 2 }, { transformContract: 'unknown' },
    { templateApplicationId: 'com.example.other' }, { sourceCommit: 'moving-branch' }]) {
    await assert.rejects(api.buildPersonalizedAab({ ...input, descriptor: { ...input.descriptor, ...patch },
      identity, versionCode: 42, backupPassword: password }), /descriptor/i);
  }
});

test('pipeline refuses missing or corrupt established identities without replacing them', async () => {
  const input = fixture();
  const valid = await createIdentity(NEW);
  for (const identity of [undefined, null, {}, { ...valid, privateKeyPem: '' },
    { ...valid, versionCodeHighWaterMark: -1 }]) {
    const before = structuredClone(identity);
    await assert.rejects(api.buildPersonalizedAab({ ...input, identity, versionCode: 42, backupPassword: password }), /identity/i);
    assert.deepEqual(identity, before);
  }
  await assert.rejects(api.buildPersonalizedAab({ ...input, identity: valid, versionCode: 42, backupPassword: '' }), /password/i);
  assert.equal(valid.versionCodeHighWaterMark, 0);
});

test('qualification CLI independently validates a real first/update pair with disposable identity', {
  skip: process.env.OAL_WEB_QUALIFY !== '1', timeout: 240000,
}, async () => {
  const script = fileURLToPath(new URL('../scripts/qualify.mjs', import.meta.url));
  const run = spawnSync(process.execPath, [script], { env: process.env, encoding: 'utf8', timeout: 230000, maxBuffer: 1024 * 1024 });
  assert.equal(run.status, 0, run.stdout + run.stderr);
  const { outputDir } = JSON.parse(run.stdout);
  const evidence = JSON.parse(await readFile(path.join(outputDir, 'evidence.json'), 'utf8'));
  assert.equal(evidence.mode, 'local-template-integration');
  assert.equal(evidence.sameIdentity, true);
  assert.equal(evidence.backupRestore, true);
  assert.equal(evidence.templatePreserved, true);
  assert.equal(evidence.unchangedPayloads, true);
  assert.equal(evidence.tamperRejected, true);
  assert.ok(evidence.codeNativeCount > 0);
  assert.equal(evidence.builds.length, 2);
  for (const item of evidence.builds) {
    assert.equal(item.bundletoolValidate, true);
    assert.equal(item.jarsignerStrict, true);
    assert.equal(item.applicationId, evidence.applicationId);
    assert.equal(item.certificateSha256, evidence.certificateSha256);
    assert.equal(hash(await readFile(path.join(outputDir, item.file))), item.sha256);
  }
  assert.ok(evidence.builds[1].versionCode > evidence.builds[0].versionCode);
  assert.deepEqual((await readdir(outputDir)).sort(), [
    'evidence.json', 'first.aab', 'first.backup.json', 'update.aab', 'update.backup.json', 'upload-certificate.pem',
  ]);
  for (const name of ['evidence.json', 'first.backup.json', 'update.backup.json']) {
    assert.doesNotMatch(await readFile(path.join(outputDir, name), 'utf8'), /PRIVATE KEY|privateKeyPem|backupPassword/);
  }
});

test('pipeline signs first build and restored update without mutating template or identity', async () => {
  assert.equal(typeof api.buildPersonalizedAab, 'function');
  const input = fixture();
  const identity = await createIdentity(NEW);
  const before = structuredClone({ ...input, identity });
  const first = await api.buildPersonalizedAab({ ...input, identity, versionCode: 42, backupPassword: password });
  assert.ok(first.aabBytes instanceof Uint8Array);
  assert.ok(first.backupBytes instanceof Uint8Array);
  assert.notEqual(first.identity, identity);
  assert.deepEqual(first.identity, { ...identity, versionCodeHighWaterMark: 42 });
  const restored = await restoreIdentity(first.backupBytes, password);
  assert.deepEqual(restored, first.identity);
  const update = await api.buildPersonalizedAab({ ...input, identity: restored, versionCode: 43, backupPassword: password });
  assert.deepEqual(update.identity, { ...identity, versionCodeHighWaterMark: 43 });
  assert.deepEqual(await restoreIdentity(update.backupBytes, password), update.identity);
  for (const [result, versionCode] of [[first, 42], [update, 43]]) {
    const output = readArchive(result.aabBytes);
    assert.deepEqual(output.slice(0, 3).map(e => e.name), ['META-INF/MANIFEST.MF', 'META-INF/OAL.SF', 'META-INF/OAL.RSA']);
    assert.deepEqual(output.slice(3), transformAabEntries(readArchive(input.templateBytes), {
      applicationId: NEW, versionCode, versionName: '0.1.528',
    }));
    assert.ok(!new TextDecoder().decode(result.backupBytes).includes('PRIVATE KEY'));
  }
  assert.deepEqual({ ...input, identity }, before);
  assert.equal(restored.versionCodeHighWaterMark, 42);
});
