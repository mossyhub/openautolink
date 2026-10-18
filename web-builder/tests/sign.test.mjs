import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import { zipSync } from 'fflate';
import forge from 'node-forge';
import { createIdentity, validateIdentity, backupIdentity, restoreIdentity } from '../core/identity.mjs';

const api = await import('../core/sign.mjs').catch(error => {
  if (error.code === 'ERR_MODULE_NOT_FOUND') return {};
  throw error;
});
const encode = text => new TextEncoder().encode(text);
const text = bytes => new TextDecoder('utf-8', { fatal: true }).decode(bytes);
const digest = bytes => createHash('sha256').update(bytes).digest('base64');
const jarsigner = process.env.JARSIGNER || (process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin/jarsigner') : 'jarsigner');
const hasJava = spawnSync(jarsigner, ['-help']).status === 0;

async function verifyJar(entries, identity, mutate = false) {
  const directory = await mkdtemp(join(process.env.TMPDIR || tmpdir(), 'oal-jar-test-'));
  try {
    const certificate = join(directory, 'upload.pem');
    const store = join(directory, 'trust.p12');
    const jar = join(directory, 'bundle.aab');
    await writeFile(certificate, identity.certificatePem);
    const keytool = process.env.KEYTOOL || (jarsigner.includes('/') ? join(dirname(jarsigner), 'keytool') : 'keytool');
    const imported = spawnSync(keytool, ['-importcert', '-noprompt', '-alias', 'upload', '-file', certificate,
      '-keystore', store, '-storetype', 'PKCS12', '-storepass', 'test-password'], { encoding: 'utf8' });
    assert.equal(imported.status, 0, imported.stdout + imported.stderr);
    const files = Object.fromEntries(entries.map(entry => [entry.name, entry.data]));
    if (mutate) files['base/dex/classes.dex'] = encode('tampered');
    await writeFile(jar, zipSync(files));
    return spawnSync(jarsigner, ['-verify', '-strict', '-verbose', '-certs', '-keystore', store,
      '-storepass', 'test-password', jar], { encoding: 'utf8' });
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
}

function withValidity(identity, notBefore, notAfter) {
  const certificate = forge.pki.certificateFromPem(identity.certificatePem);
  certificate.validity.notBefore = new Date(notBefore);
  certificate.validity.notAfter = new Date(notAfter);
  certificate.sign(forge.pki.privateKeyFromPem(identity.privateKeyPem), forge.md.sha256.create());
  return { ...identity, certificatePem: forge.pki.certificateToPem(certificate) };
}

async function javaCheckValidity(identity) {
  const directory = await mkdtemp(join(process.env.TMPDIR || tmpdir(), 'oal-validity-test-'));
  try {
    const certificate = join(directory, 'upload.pem');
    const source = join(directory, 'CheckValidity.java');
    await writeFile(certificate, identity.certificatePem);
    await writeFile(source, `import java.nio.file.*;
import java.security.cert.*;
class CheckValidity {
  public static void main(String[] args) throws Exception {
    try (var input = Files.newInputStream(Path.of(args[0]))) {
      var cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
      cert.verify(cert.getPublicKey());
      cert.checkValidity();
    }
  }
}`);
    const java = jarsigner.includes('/') ? join(dirname(jarsigner), 'java') : 'java';
    return spawnSync(java, [source, certificate], { encoding: 'utf8' });
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
}

test('signing rejects invalid certificate validity while backup/restore preserves the exact recoverable identity', async t => {
  const original = { ...await createIdentity('com.example.validity'), versionCodeHighWaterMark: 123 };
  const now = Date.now();
  const day = 86400000;
  for (const [name, before, after] of [
    ['expired', now - 2 * day, now - day],
    ['future', now + day, now + 2 * day],
    ['reversed', now + day, now - day],
  ]) {
    await t.test(name, async t => {
      const identity = withValidity(original, before, after);
      const snapshot = structuredClone(identity);
      assert.strictEqual(await validateIdentity(identity), identity);
      const restored = await restoreIdentity(await backupIdentity(identity, 'valid password'), 'valid password');
      assert.deepEqual(restored, snapshot);
      if (hasJava) {
        const result = await javaCheckValidity(identity);
        assert.notEqual(result.status, 0);
        assert.match(result.stdout + result.stderr, /CertificateExpiredException|CertificateNotYetValidException/);
        t.diagnostic(`Java independently rejected ${name} validity`);
      }
      let returned;
      await assert.rejects(async () => { returned = await api.signEntries([{ name: 'file', data: encode('payload') }], restored); }, /validity|expired|not yet valid/i);
      assert.equal(returned, undefined, 'no signed artifact may escape');
      assert.deepEqual(identity, snapshot);
      assert.deepEqual(restored, snapshot);
    });
  }
});

test('signing checks the clock after asynchronous hashing and accepts inclusive validity boundaries', async t => {
  const original = await createIdentity('com.example.signingtime');
  const start = Math.floor(Date.now() / 1000) * 1000;
  const end = start + 86400000;
  const identity = withValidity(original, start, end);
  const snapshot = structuredClone(identity);
  const source = [{ name: 'file', data: encode('payload') }];
  let clock = start;
  t.mock.method(Date, 'now', () => clock);
  for (const time of [start, end]) {
    clock = time;
    assert.ok((await api.signEntries(source, identity)).some(entry => entry.name === 'META-INF/OAL.RSA'));
  }
  for (const time of [start - 1, end + 1]) {
    clock = time;
    await assert.rejects(api.signEntries(source, identity), /validity/i);
  }
  clock = start;
  const originalDigest = crypto.subtle.digest.bind(crypto.subtle);
  t.mock.method(crypto.subtle, 'digest', (...args) => {
    clock = end + 1;
    return originalDigest(...args);
  });
  await assert.rejects(api.signEntries(source, identity), /validity/i);
  assert.deepEqual(identity, snapshot);
});

test('signing never returns an RSA artifact for corrupted private CRT components', async t => {
  const original = await createIdentity('com.example.crtfault');
  const source = [{ name: 'file', data: encode('fault containment payload') }];
  for (const component of ['p', 'q', 'dP', 'dQ', 'qInv']) {
    await t.test(component, async t => {
      const key = forge.pki.privateKeyFromPem(original.privateKeyPem);
      key[component] = key[component].add(new forge.jsbn.BigInteger('2'));
      const bad = { ...original, privateKeyPem: forge.pki.privateKeyInfoToPem(
        forge.pki.wrapRsaPrivateKey(forge.pki.privateKeyToAsn1(key))) };
      const snapshot = structuredClone(bad);
      // Some WebCrypto engines repair/ignore bad CRT values for their own RSA
      // operation. A successful key proof does not validate forge's later RSA.
      try {
        await validateIdentity(bad);
        t.diagnostic(`WebCrypto identity proof tolerated mutated ${component}`);
      } catch {
        t.diagnostic(`WebCrypto identity proof rejected mutated ${component}`);
      }
      let returned;
      await assert.rejects(async () => { returned = await api.signEntries(source, bad); }, /identity|key|signature/i);
      assert.equal(returned, undefined, 'a faulty RSA signature must never leave signEntries');
      assert.deepEqual(bad, snapshot);
    });
  }
  const signed = await api.signEntries(source, original);
  const sf = signed.find(entry => entry.name === 'META-INF/OAL.SF').data;
  const rsa = signed.find(entry => entry.name === 'META-INF/OAL.RSA').data;
  const pkcs7 = forge.asn1.fromDer(Buffer.from(rsa).toString('binary'));
  const signerInfos = pkcs7.value[1].value[0].value.at(-1).value;
  assert.equal(signerInfos.length, 1);
  const signer = signerInfos[0].value;
  assert.equal(signer.length, 5, 'no authenticated attributes: signature covers SF bytes directly');
  const signature = Buffer.from(signer[4].value, 'binary');
  const cert = forge.pki.certificateFromPem(original.certificatePem);
  const publicKey = await crypto.subtle.importKey('spki', Buffer.from(forge.asn1.toDer(
    forge.pki.publicKeyToAsn1(cert.publicKey)).getBytes(), 'binary'),
  { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['verify']);
  assert.equal(await crypto.subtle.verify('RSASSA-PKCS1-v1_5', publicKey, signature, sf), true);
  assert.equal(await crypto.subtle.verify('RSASSA-PKCS1-v1_5', publicKey, signature, encode('wrong SF')), false);
  if (hasJava) {
    const result = await verifyJar(signed, original);
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.match(result.stdout, /jar verified\./);
    t.diagnostic('Unmutated control: WebCrypto and Java jarsigner verified the actual artifact');
  }
});

test('signing snapshots identity and entry bytes before yielding', async () => {
  const identity = await createIdentity('com.example.snapshot');
  const entry = { name: 'file.txt', data: encode('original') };
  const pending = api.signEntries([entry], identity);
  identity.privateKeyPem = '';
  identity.certificatePem = '';
  entry.name = 'changed.txt';
  entry.data.fill(0);
  const signed = await pending;
  assert.equal(text(signed.find(e => e.name === 'file.txt').data), 'original');
});

test('re-signing removes only root signing metadata and signs all remaining files', async () => {
  const identity = { ...await createIdentity('com.example.resign'), versionCodeHighWaterMark: 123 };
  const before = JSON.stringify(identity);
  const source = ['META-INF/MANIFEST.MF', 'meta-inf/old.sf', 'META-INF/OLD.RSA', 'META-INF/OLD.DSA',
    'META-INF/OLD.EC', 'META-INF/SIG-OLD', 'META-INF/services/a.RSA', 'META-INF/LICENSE',
    'base/dex/classes.dex'].map(name => ({ name, data: encode(name) }));
  source.push({ name: 'base/', data: new Uint8Array() });
  const signed = await api.signEntries(source, identity);
  assert.deepEqual(signed.map(e => e.name), ['META-INF/MANIFEST.MF', 'META-INF/OAL.SF', 'META-INF/OAL.RSA',
    'META-INF/services/a.RSA', 'META-INF/LICENSE', 'base/dex/classes.dex', 'base/']);
  assert.equal(JSON.stringify(identity), before);
  const manifest = text(signed[0].data);
  assert.ok(!manifest.includes('Name: base/\r\n'));
  assert.ok(manifest.includes('Name: META-INF/services/a.RSA\r\n'));
  if (hasJava) {
    const result = await verifyJar(signed, identity);
    assert.equal(result.status, 0, result.stdout + result.stderr);
  }
});

test('signing rejects missing identity and ambiguous or unsafe ZIP names without regeneration', async () => {
  const identity = await createIdentity('com.example.paths');
  const entry = { name: 'base/dex/classes.dex', data: encode('dex') };
  await assert.rejects(api.signEntries([entry], null), /identity/i);
  for (const name of ['', '../escape', '/absolute', 'base/../escape', 'base/./file', 'base//file',
    'bad\\path', 'C:drive', 'bad\nName: evil', 'bad\rline', 'bad\0nul', '\ud800', 'x'.repeat(65536)]) {
    await assert.rejects(api.signEntries([{ ...entry, name }], identity), /entry|name|path/i);
  }
  for (const entries of [null, [], [entry, entry], [{ name: 'file', data: 'text' }],
    [{ name: 'base/', data: encode('not a directory') }]]) {
    await assert.rejects(api.signEntries(entries, identity), /entry|entries|duplicate|directory/i);
  }
});

test('UTF-8 manifest wrapping preserves names and hashes the exact CRLF section bytes', async t => {
  const identity = await createIdentity('com.example.unicode');
  const source = [{ name: `base/assets/${'界😀é'.repeat(35)}.txt`, data: encode('unicode payload') },
    { name: `base/assets/${'a'.repeat(80)}.txt`, data: new Uint8Array() }];
  const signed = await api.signEntries(source, identity);
  const manifest = signed.find(e => e.name === 'META-INF/MANIFEST.MF').data;
  const sf = signed.find(e => e.name === 'META-INF/OAL.SF').data;
  for (const bytes of [manifest, sf]) {
    const value = text(bytes);
    assert.ok(!value.replaceAll('\r\n', '').includes('\n'));
    assert.ok(value.endsWith('\r\n\r\n'));
    for (const line of value.split('\r\n')) assert.ok(encode(line).length <= 72, `long line: ${line}`);
    for (const entry of source) assert.ok(value.replaceAll('\r\n ', '').includes(`Name: ${entry.name}\r\n`));
  }
  const manifestSections = text(manifest).split('\r\n\r\n').slice(1, -1);
  const sfSections = text(sf).replaceAll('\r\n ', '').split('\r\n\r\n').slice(1, -1);
  for (let index = 0; index < source.length; index++) {
    assert.ok(sfSections[index].includes(`SHA-256-Digest: ${digest(encode(manifestSections[index] + '\r\n\r\n'))}`));
  }
  if (hasJava) {
    const result = await verifyJar(signed, identity);
    assert.equal(result.status, 0, result.stdout + result.stderr);
    t.diagnostic('Java jarsigner -verify -strict accepted Unicode/long-name fixture');
  }
});

test('signEntries creates SHA256 manifest, detached SF/RSA and preserves payload bytes', async t => {
  assert.equal(typeof api.signEntries, 'function');
  const identity = await createIdentity('com.example.sign');
  const source = [{ name: 'base/dex/classes.dex', data: encode('dex\n035\0fixture') },
    { name: 'BundleConfig.pb', data: new Uint8Array([8, 1, 18, 0]) }];
  const snapshot = structuredClone(source);
  const signed = await api.signEntries(source, identity);
  assert.deepEqual(source, snapshot);
  assert.deepEqual(signed.filter(entry => !entry.name.startsWith('META-INF/')), source);
  const get = name => signed.find(entry => entry.name === name)?.data;
  assert.ok(get('META-INF/MANIFEST.MF') instanceof Uint8Array);
  assert.ok(get('META-INF/OAL.SF') instanceof Uint8Array);
  assert.ok(get('META-INF/OAL.RSA') instanceof Uint8Array);
  const manifest = text(get('META-INF/MANIFEST.MF'));
  assert.ok(manifest.startsWith('Manifest-Version: 1.0\r\n'));
  for (const entry of source) assert.ok(manifest.includes(`Name: ${entry.name}\r\nSHA-256-Digest: ${digest(entry.data)}\r\n\r\n`));
  assert.ok(text(get('META-INF/OAL.SF')).includes(`SHA-256-Digest-Manifest: ${digest(get('META-INF/MANIFEST.MF'))}\r\n`));
  if (!hasJava) return t.diagnostic('Independent Java verification unavailable; set JARSIGNER or JAVA_HOME');
  const result = await verifyJar(signed, identity);
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.match(result.stdout, /jar verified\./);
  t.diagnostic(result.stdout);
  const tampered = await verifyJar(signed, identity, true);
  assert.notEqual(tampered.status, 0);
  assert.match(tampered.stdout + tampered.stderr, /digest error|SecurityException/i);
});
