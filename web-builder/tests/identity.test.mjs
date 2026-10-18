import test from 'node:test';
import assert from 'node:assert/strict';
import { sign as nodeSign } from 'node:crypto';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
const getForge = async () => (await import('node-forge')).default;

const api = await import('../core/identity.mjs').catch(error => {
  if (error.code === 'ERR_MODULE_NOT_FOUND') return {};
  throw error;
});

const jarsigner = process.env.JARSIGNER;
const keytool = process.env.KEYTOOL || (jarsigner ? join(dirname(jarsigner), 'keytool') : 'keytool');
const hasJava = spawnSync(keytool, ['-help']).status === 0;

async function javaReadCertificate(certificatePem) {
  const directory = await mkdtemp(join(process.env.TMPDIR || tmpdir(), 'oal-cert-test-'));
  try {
    const file = join(directory, 'upload.pem');
    await writeFile(file, certificatePem);
    return spawnSync(keytool, ['-printcert', '-file', file, '-debug'], { encoding: 'utf8' });
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
}

test('identity rejects inner/outer certificate signature algorithm mismatch even with a valid SHA256 signature', async t => {
  const original = await api.createIdentity('com.example.algorithms');
  const forge = await getForge();
  const asn1 = forge.asn1.fromDer(forge.pem.decode(original.certificatePem)[0].body);
  // Generated certificates are v3: explicit version, serial, then TBS algorithm.
  asn1.value[0].value[2].value[0].value = forge.asn1.oidToDer(forge.pki.oids.sha1WithRSAEncryption).getBytes();
  const tbs = Buffer.from(forge.asn1.toDer(asn1.value[0]).getBytes(), 'binary');
  const signature = nodeSign('RSA-SHA256', tbs, original.privateKeyPem);
  asn1.value[2].value = '\x00' + signature.toString('binary');
  const bad = { ...original, certificatePem: forge.pem.encode({ type: 'CERTIFICATE', body: forge.asn1.toDer(asn1).getBytes() }) };
  const parsed = forge.pki.certificateFromPem(bad.certificatePem);
  const publicKey = await crypto.subtle.importKey('spki', Buffer.from(forge.asn1.toDer(
    forge.pki.publicKeyToAsn1(parsed.publicKey)).getBytes(), 'binary'),
  { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['verify']);
  assert.equal(await crypto.subtle.verify('RSASSA-PKCS1-v1_5', publicKey, signature, tbs), true);
  if (hasJava) {
    const result = await javaReadCertificate(bad.certificatePem);
    assert.notEqual(result.status, 0);
    assert.match(result.stdout + result.stderr, /Signature algorithm mismatch/i);
    t.diagnostic('Java rejected the re-signed certificate: Signature algorithm mismatch');
  } else t.diagnostic('Independent Java certificate check unavailable');
  const snapshot = structuredClone(bad);
  await assert.rejects(api.validateIdentity(bad), /identity|certificate/i);
  const { signEntries } = await import('../core/sign.mjs');
  await assert.rejects(signEntries([{ name: 'file', data: new Uint8Array([1]) }], bad), /identity|certificate/i);
  await assert.rejects(api.backupIdentity(bad, 'valid password'), /identity|certificate/i);
  assert.deepEqual(bad, snapshot);
});


let identity;

test('backup captures key and version state before asynchronous encryption', async () => {
  const original = await api.createIdentity('com.example.snapshot');
  original.versionCodeHighWaterMark = 42;
  const expected = structuredClone(original);
  const pending = api.backupIdentity(original, 'valid password');
  original.versionCodeHighWaterMark = 1;
  original.privateKeyPem = '';
  assert.deepEqual(await api.restoreIdentity(await pending, 'valid password'), expected);
});

test('identity validation fails closed without generating replacement keys', async () => {
  assert.equal(typeof api.validateIdentity, 'function');
  for (const appId of ['', 'com', 'com.bad-name', 'com.2bad', 'com.example\nInjected', null]) {
    await assert.rejects(api.createIdentity(appId), /application/i);
  }
  const original = await api.createIdentity('com.example.validation');
  assert.deepEqual(await api.validateIdentity(original), original);
  for (const bad of [null, {}, { ...original, formatVersion: 2 }, { ...original, versionCodeHighWaterMark: -1 },
    { ...original, versionCodeHighWaterMark: 1.5 }, { ...original, versionCodeHighWaterMark: 2100000001 },
    { ...original, privateKeyPem: '' }, { ...original, applicationId: 'bad' },
    { ...original, privateKeyPem: (await api.createIdentity('com.example.other')).privateKeyPem },
    { ...original, excess: 'x'.repeat(65536) }]) {
    await assert.rejects(api.backupIdentity(bad, 'valid password'), /identity|key|certificate|application|version|size/i);
  }
});

test('backup v1 rejects unsupported headers, malformed envelopes, size and password abuse', async () => {
  const original = await api.createIdentity('com.example.limits');
  await assert.rejects(api.backupIdentity(original, ''), /password/i);
  await assert.rejects(api.backupIdentity(original, 'x'.repeat(1025)), /password/i);
  const bytes = await api.backupIdentity(original, 'valid password');
  const envelope = JSON.parse(new TextDecoder().decode(bytes));
  for (const [field, value] of Object.entries({ format: 'desktop', formatVersion: 2,
    iterations: 999999999, cipher: 'AES-CBC', kdf: 'scrypt', salt: 'AA==', iv: 'bad', ciphertext: 'AA==' })) {
    await assert.rejects(api.restoreIdentity(new TextEncoder().encode(JSON.stringify({ ...envelope, [field]: value })), 'valid password'));
  }
  await assert.rejects(api.restoreIdentity(new Uint8Array(262145), 'valid password'), /size/i);
  await assert.rejects(api.restoreIdentity(bytes.buffer, 'valid password'), /Uint8Array/i);
  await assert.rejects(api.restoreIdentity(new TextEncoder().encode('{'), 'valid password'), /backup/i);
  const corrupt = { ...envelope, ciphertext: (envelope.ciphertext[0] === 'A' ? 'B' : 'A') + envelope.ciphertext.slice(1) };
  await assert.rejects(api.restoreIdentity(new TextEncoder().encode(JSON.stringify(corrupt)), 'valid password'), /password|damaged/i);
});

test('encrypted web backup restores the exact key and version high water mark', async () => {
  assert.equal(typeof api.backupIdentity, 'function');
  const original = { ...await api.createIdentity('com.example.backup'), versionCodeHighWaterMark: 9876 };
  const bytes = await api.backupIdentity(original, 'correct horse battery staple');
  assert.ok(bytes instanceof Uint8Array);
  const envelope = JSON.parse(new TextDecoder().decode(bytes));
  assert.equal(envelope.format, 'oal-web-upload-backup');
  assert.equal(envelope.formatVersion, 1);
  assert.equal(envelope.iterations, 600000);
  assert.equal(envelope.kdf, 'PBKDF2-SHA256');
  assert.equal(envelope.cipher, 'AES-256-GCM');
  assert.ok(!new TextDecoder().decode(bytes).includes('PRIVATE KEY'));
  assert.deepEqual(await api.restoreIdentity(bytes, 'correct horse battery staple'), original);
  const again = await api.backupIdentity(original, 'correct horse battery staple');
  assert.notDeepEqual(again, bytes);
  await assert.rejects(api.restoreIdentity(bytes, 'wrong password'), /password|damaged/i);
});
test('createIdentity generates a serializable RSA upload identity and self-signed SHA256 certificate', async () => {
  assert.equal(typeof api.createIdentity, 'function');
  identity = await api.createIdentity('com.example.browser');
  const forge = await getForge();
  assert.deepEqual(JSON.parse(JSON.stringify(identity)), identity);
  assert.equal(identity.format, 'oal-web-upload-identity');
  assert.equal(identity.formatVersion, 1);
  assert.equal(identity.applicationId, 'com.example.browser');
  assert.equal(identity.versionCodeHighWaterMark, 0);
  const key = forge.pki.privateKeyFromPem(identity.privateKeyPem);
  const cert = forge.pki.certificateFromPem(identity.certificatePem);
  assert.ok(key.n.bitLength() >= 2048);
  assert.equal(cert.signatureOid, forge.pki.oids.sha256WithRSAEncryption);
  assert.equal(cert.publicKey.n.toString(16), key.n.toString(16));
  const publicKey = await crypto.subtle.importKey('spki', Uint8Array.from(
    forge.asn1.toDer(forge.pki.publicKeyToAsn1(cert.publicKey)).getBytes(), ch => ch.charCodeAt(0)),
  { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['verify']);
  assert.equal(await crypto.subtle.verify('RSASSA-PKCS1-v1_5', publicKey,
    Uint8Array.from(cert.signature, ch => ch.charCodeAt(0)), Uint8Array.from(
      forge.asn1.toDer(forge.pki.getTBSCertificate(cert)).getBytes(), ch => ch.charCodeAt(0))), true);
  assert.ok(cert.validity.notAfter.getUTCFullYear() >= new Date().getUTCFullYear() + 25);
});
