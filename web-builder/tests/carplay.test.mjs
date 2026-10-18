import test from 'node:test';
import assert from 'node:assert/strict';
import forge from 'node-forge';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { after } from 'node:test';

assert.ok(process.env.TMPDIR, 'Set TMPDIR to a scratch directory outside the checkout');
const fixtures = mkdtempSync(join(process.env.TMPDIR, 'carplay-synthetic-'));
after(() => rmSync(fixtures, { recursive: true, force: true }));
try {
  execFileSync(process.env.CARPLAY_FIXTURE_PYTHON || '/hermes_work/oal-builder-verify-venv/bin/python',
    [fileURLToPath(new URL('./carplay-fixtures/generate.py', import.meta.url)), fixtures], { stdio: 'pipe' });
} catch {
  rmSync(fixtures, { recursive: true, force: true });
  throw new Error('Synthetic fixture generation failed; configure CARPLAY_FIXTURE_PYTHON with Python cryptography');
}
const fixture = name => new Uint8Array(readFileSync(join(fixtures, name)));
const sameBytes = (actual, expected, message = 'byte snapshots match') => {
  assert.ok(actual instanceof Uint8Array && actual.length === expected.length && actual.every((b, i) => b === expected[i]), message);
};
const decode = bytes => forge.asn1.fromDer(String.fromCharCode(...bytes), { decodeBitStrings: false });
const encode = node => Uint8Array.from(forge.asn1.toDer(node).getBytes(), c => c.charCodeAt(0));
const mutate = (name, fn) => { const node = decode(fixture(name)); fn(node); return encode(node); };

const { validateCarplayPair, injectCarplayEntries } = await import('../core/carplay.mjs');

test('accepts a matching P-256 PKCS8 and single DER X509 certificate', async () => {
  const keyBytes = fixture('p256.pk8'), certificateBytes = fixture('p256.der');
  const pair = await validateCarplayPair(keyBytes, certificateBytes);
  assert.ok(pair, 'valid pair is returned');
  sameBytes(pair.keyBytes, keyBytes);
  sameBytes(pair.certificateBytes, certificateBytes);
});

test('rejects keys other than canonical unencrypted DER PKCS8 P-256', async () => {
  for (const name of ['sec1.der', 'encrypted.der', 'key.pem', 'p384.pk8', 'rsa.pk8']) {
    await assert.rejects(validateCarplayPair(fixture(name), fixture('p256.der')), /CarPlay files must be/);
  }
  const key = fixture('p256.pk8');
  for (const bytes of [key.slice(0, -1), Uint8Array.of(...key, 0), new Uint8Array(1)]) {
    await assert.rejects(validateCarplayPair(bytes, fixture('p256.der')), /CarPlay files must be/);
  }
});

test('rejects malformed or mismatched X509 certificates', async () => {
  const cert = fixture('p256.der');
  for (const bytes of [fixture('other.der'), fixture('p384.der'), fixture('rsa.der'),
    fixture('certificate.pem'), fixture('p256.pk8'), cert.slice(0, -1), Uint8Array.of(...cert, 0), new Uint8Array(1)]) {
    await assert.rejects(validateCarplayPair(fixture('p256.pk8'), bytes), /CarPlay files must be/);
  }
});

test('accepts DER PKCS7 containing exactly one matching certificate without converting its bytes', async () => {
  const certificateBytes = fixture('single.p7b');
  const pair = await validateCarplayPair(fixture('p256.pk8'), certificateBytes);
  sameBytes(pair.certificateBytes, certificateBytes);
});

test('rejects zero, duplicate, multiple and malformed PKCS7 certificate bags', async () => {
  const changed = fn => mutate('single.p7b', root => fn(root.value[1].value[0].value));
  const invalid = [changed(s => s[3].value.push(s[3].value[0])),
    changed(s => { s[3].value = []; }), fixture('multiple.p7b'),
    changed(s => s.pop()), changed(s => s.push(s[4])),
    changed(s => { s[2].value = []; }),
    changed(s => { s[3].value[0] = decode(fixture('other.der')); }),
    Uint8Array.of(...fixture('single.p7b'), 0)];
  for (const bytes of invalid) await assert.rejects(validateCarplayPair(fixture('p256.pk8'), bytes), /CarPlay files must be/);
});

test('rejects tampering with an embedded key public point, even when the private scalar still matches', async () => {
  const key = decode(fixture('p256.pk8')), other = decode(fixture('other.pk8'));
  const inner = decode(Uint8Array.from(key.value[2].value, c => c.charCodeAt(0)));
  const otherInner = decode(Uint8Array.from(other.value[2].value, c => c.charCodeAt(0)));
  inner.value[2] = otherInner.value[2];
  key.value[2].value = String.fromCharCode(...encode(inner));
  await assert.rejects(validateCarplayPair(encode(key), fixture('p256.der')), /CarPlay files must be/);
});

test('snapshots inputs before the first await and exposes independent output copies', async () => {
  const originalKey = fixture('p256.pk8'), originalCert = fixture('single.p7b');
  // Buffer.slice() aliases: use Buffers and subarray views to exercise real copying.
  const key = Buffer.from(originalKey), cert = Buffer.from(originalCert);
  const pending = validateCarplayPair(key.subarray(0), cert.subarray(0));
  key.fill(0); cert.fill(0);
  const pair = await pending;
  sameBytes(pair.keyBytes, originalKey);
  sameBytes(pair.certificateBytes, originalCert);
  pair.keyBytes.fill(0); pair.certificateBytes.fill(0);
  sameBytes(pair.keyBytes, originalKey, 'each access is a fresh snapshot');
  sameBytes(pair.certificateBytes, originalCert);
  assert.ok(Object.isFrozen(pair));
  assert.equal(JSON.stringify(pair), '{}', 'credentials must not enter JSON backups');
  assert.deepEqual(structuredClone(pair), {}, 'credentials must not enter cloned identity backups');
});

test('injects canonical CarPlay entries into a copied transformed entry list', async () => {
  const entries = [{ name: 'base/dex/classes.dex', data: Uint8Array.of(1, 2, 3) }];
  const pair = await validateCarplayPair(fixture('p256.pk8'), fixture('single.p7b'));
  const output = injectCarplayEntries(entries, pair);
  assert.deepEqual(output.map(e => e.name), ['base/dex/classes.dex',
    'base/assets/offline-mfi/identity.pk8', 'base/assets/offline-mfi/certificate.p7b']);
  sameBytes(output[1].data, fixture('p256.pk8'));
  sameBytes(output[2].data, fixture('single.p7b'));
  assert.equal(entries.length, 1);
  output[0].data.fill(0); output[1].data.fill(0); output[2].data.fill(0);
  sameBytes(entries[0].data, Uint8Array.of(1, 2, 3));
  sameBytes(pair.keyBytes, fixture('p256.pk8'));
  sameBytes(pair.certificateBytes, fixture('single.p7b'));
  assert.deepEqual(injectCarplayEntries(entries, null), entries);
});

test('refuses to overwrite or alias any existing CarPlay asset namespace', async () => {
  const pair = await validateCarplayPair(fixture('p256.pk8'), fixture('p256.der'));
  for (const name of ['base/assets/offline-mfi/identity.pk8', 'base/assets/offline-mfi/certificate.p7b',
    'base/assets/OFFLINE-MFI/Identity.pk8', 'base/assets/offline-mfi/', 'base/assets/offline-mfi/other',
    'base/assets', 'base', 'BASE/assets/unrelated.txt', 'base/assets/../assets/offline-mfi/identity.pk8']) {
    const entries = [{ name, data: new Uint8Array() }];
    assert.throws(() => injectCarplayEntries(entries, pair), /CarPlay /);
    assert.equal(entries.length, 1);
  }
  assert.throws(() => injectCarplayEntries([{ name: 'base/assets/offline-mfi/identity.pk8', data: new Uint8Array() }], null), /CarPlay /);
});

test('only the original validated pair can be injected; copied and forged objects fail closed', async () => {
  const pair = await validateCarplayPair(fixture('p256.pk8'), fixture('p256.der'));
  for (const invalid of [{ keyBytes: fixture('p256.pk8'), certificateBytes: fixture('other.der') },
    structuredClone(pair), { ...pair }, Object.create(pair), false, 'secret sentinel']) {
    assert.throws(() => injectCarplayEntries([], invalid), /CarPlay pair must be validated/);
  }
});

test('injection rejects signed, malformed, duplicate and unsafe entries before changing anything', async () => {
  const pair = await validateCarplayPair(fixture('p256.pk8'), fixture('p256.der'));
  const entry = (name, data = new Uint8Array()) => ({ name, data });
  for (const entries of [null, {}, [null], [entry(42)], [entry('x', 'bytes')],
    [entry('META-INF/OAL.SF')], [entry('META-INF/MANIFEST.MF')],
    [entry('base/a'), entry('base/a')], [entry('base/a'), entry('base/A')],
    [entry('base/assets/file', Uint8Array.of(1)), entry('base/assets/file/nested')],
    [entry('base/assets/unsafe\\name')], [entry('base/assets/space /name')]]) {
    assert.throws(() => injectCarplayEntries(entries, pair), /CarPlay injection/);
  }
});

test('rejects X509 structural tampering rather than merely extracting a matching SPKI', async () => {
  const changed = fn => mutate('p256.der', fn);
  for (const cert of [changed(r => { r.value[0].value[4].value = []; }),
    changed(r => { r.value[1].value = []; }),
    changed(r => { r.value[0].value[3].value = [r.value[0].value[1]]; }),
    changed(r => { r.value[0].value.push(r.value[0].value[1]); }),
    changed(r => { r.value[0].value[0].value[0].value = '\x03'; }),
    changed(r => { r.value[2].value = '\x01\x01'; })]) {
    await assert.rejects(validateCarplayPair(fixture('p256.pk8'), cert), /CarPlay files must be/);
  }
});

test('accepts the 16384-byte certificate boundary and makes no certificate trust or signature claim', async () => {
  const max = fixture('max.der');
  assert.equal(max.length, 16384);
  sameBytes((await validateCarplayPair(fixture('p256.pk8'), max)).certificateBytes, max);
  // Every fixture is expired and signed by an unrelated synthetic issuer.
  // A signature-only alteration is intentionally NOT an authentication test.
  const signatureOnly = fixture('p256.der');
  signatureOnly[signatureOnly.length - 1] ^= 1;
  assert.ok(await validateCarplayPair(fixture('p256.pk8'), signatureOnly));
});

test('rejects scalar or certificate-public-key tampering and noncanonical DER lengths', async () => {
  const tamperedScalar = mutate('p256.pk8', root => {
    const inner = decode(Uint8Array.from(root.value[2].value, c => c.charCodeAt(0)));
    inner.value[1].value = '\x00'.repeat(32);
    root.value[2].value = String.fromCharCode(...encode(inner));
  });
  await assert.rejects(validateCarplayPair(tamperedScalar, fixture('p256.der')), /CarPlay files must be/);
  const point = mutate('p256.der', root => { root.value[0].value[6].value[1].value = '\x00\x04' + '\x00'.repeat(64); });
  await assert.rejects(validateCarplayPair(fixture('p256.pk8'), point), /CarPlay files must be/);
  for (const name of ['p256.der', 'single.p7b']) {
    const bytes = fixture(name);
    const noncanonical = Uint8Array.of(bytes[0], bytes[1] + 1, 0, ...bytes.slice(2));
    await assert.rejects(validateCarplayPair(fixture('p256.pk8'), noncanonical), /CarPlay files must be/);
  }
});

test('CarPlay is optional when both inputs are absent', async () => {
  assert.equal(await validateCarplayPair(), null);
  assert.equal(await validateCarplayPair(null, null), null);
});

test('partial pairs and invalid byte inputs fail closed with bounded safe errors', async () => {
  for (const [key, cert] of [[new Uint8Array(1), null], [null, new Uint8Array(1)],
    [new Uint8Array(0), new Uint8Array(1)], [new Uint8Array(1), new Uint8Array(0)],
    [new Uint8Array(16385), new Uint8Array(1)], [new Uint8Array(1), new Uint8Array(16385)],
    ['secret sentinel', new Uint8Array(1)], [new Uint8Array(1), new ArrayBuffer(1)]]) {
    await assert.rejects(validateCarplayPair(key, cert), /^Error: CarPlay /);
  }
});
