#!/usr/bin/env node
/** Local real-template integration only; not browser/live-release/Play proof.
 * Usage: OAL_WEB_TEMPLATE=/path/template.aab OAL_WEB_TOOLS=/path/tools node scripts/qualify.mjs
 * OAL_WEB_DESCRIPTOR defaults to openautolink-personalization.json beside the AAB.
 * Tools directory contains jdk-17/bin/{java,keytool,jarsigner}, bundletool-1.18.3.jar.
 * Generates a NEW disposable identity in memory. Never opens an existing key.
 * Retains public artifacts and encrypted backups only; generated test passwords
 * are never printed or saved, so retained backups are intentionally unrecoverable.
 */
import assert from 'node:assert/strict';
import { readFile, writeFile, mkdir, mkdtemp, rm } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { createHash, randomBytes, X509Certificate } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { buildPersonalizedAab } from '../core/pipeline.mjs';
import { createIdentity, restoreIdentity } from '../core/identity.mjs';
import { readArchive, writeArchive } from '../core/archive.mjs';
import { validateDescriptor, verifyTemplateBytes, MAX_DESCRIPTOR_SIZE } from '../core/releases.mjs';

const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const root = fileURLToPath(new URL('../qualification-output/', import.meta.url));
let phase = 'configuration';

async function qualify() {
  const template = process.env.OAL_WEB_TEMPLATE;
  const tools = process.env.OAL_WEB_TOOLS;
  if (!template || !tools) throw new Error('Configuration missing');
  const descriptorFile = process.env.OAL_WEB_DESCRIPTOR || path.join(path.dirname(template), 'openautolink-personalization.json');
  const java = path.join(tools, 'jdk-17/bin/java');
  const keytool = path.join(tools, 'jdk-17/bin/keytool');
  const jarsigner = path.join(tools, 'jdk-17/bin/jarsigner');
  const bundletool = path.join(tools, 'bundletool-1.18.3.jar');
  // This protects a public-certificate-only truststore, NOT a private signing key.
  const trustPassword = randomBytes(32).toString('hex');
  const run = (bin, args, expectedFailure = false) => {
    const result = spawnSync(bin, args, { encoding: 'utf8', timeout: 120000,
      maxBuffer: 16 * 1024 * 1024, env: { ...process.env, OAL_QUALIFICATION_TRUST_PASSWORD: trustPassword } });
    assert.ok(!result.error && result.signal === null, 'Tool execution failed');
    if (!expectedFailure) assert.equal(result.status, 0, 'Tool verification failed');
    return result;
  };
  const bundle = args => run(java, ['-Xmx512m', '-jar', bundletool, ...args]).stdout;

  phase = 'verify-local-descriptor';
  const descriptorBytes = await readFile(descriptorFile);
  assert.ok(descriptorBytes.length > 0 && descriptorBytes.length <= MAX_DESCRIPTOR_SIZE);
  const parsed = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(descriptorBytes));
  // Explicitly synthetic release metadata, bound to the retained public fixture.
  // Does NOT claim that GitHub serves this tuple or these bytes today.
  const asset = parsed.templateAsset;
  const url = name => `https://github.com/mossyhub/openautolink/releases/download/${parsed.releaseTag}/${name}`;
  const descriptor = validateDescriptor(parsed, {
    sourceRef: parsed.releaseTag, sourceCommit: parsed.sourceCommit,
    assets: {
      'openautolink-personalization.json': { size: descriptorBytes.length, browser_download_url: url('openautolink-personalization.json') },
      [asset.name]: { size: asset.size, digest: `sha256:${asset.sha256}`, browser_download_url: url(asset.name) },
    },
  });
  const originalBytes = new Uint8Array(await readFile(template));
  const templateBytes = await verifyTemplateBytes(originalBytes, descriptor);
  const sourceEntries = readArchive(templateBytes);
  const sourceHashes = Object.fromEntries(sourceEntries.map(e => [e.name, digest(e.data)]));
  const sourceManifest = bundle(['dump', 'manifest', '--bundle=' + template]);
  const sourceVersion = Number(sourceManifest.match(/\bandroid:versionCode="([0-9]+)"/)?.[1]);
  assert.ok(Number.isInteger(sourceVersion) && sourceVersion > 0);

  phase = 'create-disposable-identity';
  const applicationId = 'com.example.oalqualification';
  const identity = await createIdentity(applicationId);
  const initialIdentity = structuredClone(identity);
  const backupPassword = randomBytes(32).toString('hex');
  const certificateSha256 = digest(new X509Certificate(identity.certificatePem).raw);
  await mkdir(root, { recursive: true });
  const outputDir = await mkdtemp(path.join(root, 'pipeline-'));
  const certificateFile = path.join(outputDir, 'upload-certificate.pem');
  const store = path.join(outputDir, 'public-trust.p12');
  const tamperedFile = path.join(outputDir, 'tampered.aab');
  await writeFile(certificateFile, identity.certificatePem, { flag: 'wx' });
  const trustArgs = ['-keystore', store, '-storepass:env', 'OAL_QUALIFICATION_TRUST_PASSWORD'];
  try {
    phase = 'create-public-truststore';
    run(keytool, ['-importcert', '-noprompt', '-alias', 'disposable-upload', '-file', certificateFile,
      '-storetype', 'PKCS12', ...trustArgs]);
    const builds = [];
    const manifests = [];
    const payloadHashes = {};
    let currentIdentity = identity;
    let lastEntries;
    for (const [label, versionCode] of [['first', 12345], ['update', 12346]]) {
      phase = label + '-pipeline';
      const before = structuredClone(currentIdentity);
      const result = await buildPersonalizedAab({ templateBytes, descriptor, identity: currentIdentity,
        versionCode, backupPassword });
      assert.deepEqual(currentIdentity, before);
      assert.equal(result.identity.applicationId, applicationId);
      assert.equal(result.identity.certificatePem, initialIdentity.certificatePem);
      assert.equal(result.identity.privateKeyPem, initialIdentity.privateKeyPem);
      assert.equal(result.identity.versionCodeHighWaterMark, versionCode);
      const restored = await restoreIdentity(result.backupBytes, backupPassword);
      assert.deepEqual(restored, result.identity);
      await assert.rejects(restoreIdentity(result.backupBytes, backupPassword + '-wrong'));
      currentIdentity = restored; // Update actually uses the restored first backup.
      const file = label + '.aab';
      const bundleFile = path.join(outputDir, file);
      await writeFile(bundleFile, result.aabBytes, { flag: 'wx' });
      await writeFile(path.join(outputDir, label + '.backup.json'), result.backupBytes, { flag: 'wx' });
      const entries = readArchive(new Uint8Array(await readFile(bundleFile)));
      const signedNames = new Set(['META-INF/MANIFEST.MF', 'META-INF/OAL.SF', 'META-INF/OAL.RSA']);
      assert.deepEqual(entries.filter(e => !signedNames.has(e.name)).map(e => e.name), sourceEntries.map(e => e.name));
      const changed = [];
      payloadHashes[label] = {};
      for (const entry of entries) {
        if (signedNames.has(entry.name)) continue;
        const hash = digest(entry.data);
        if (hash !== sourceHashes[entry.name]) changed.push(entry.name);
        if (!['base/manifest/AndroidManifest.xml', 'base/resources.pb'].includes(entry.name)) {
          assert.equal(hash, sourceHashes[entry.name]);
          payloadHashes[label][entry.name] = hash;
        }
      }
      assert.deepEqual(changed.sort(), ['base/manifest/AndroidManifest.xml', 'base/resources.pb']);
      phase = label + '-bundletool';
      bundle(['validate', '--bundle=' + bundleFile]);
      const manifest = bundle(['dump', 'manifest', '--bundle=' + bundleFile]);
      const expectedManifest = sourceManifest.replaceAll(descriptor.templateApplicationId, applicationId)
        .replace(`android:versionCode="${sourceVersion}"`, `android:versionCode="${versionCode}"`);
      assert.equal(manifest, expectedManifest, 'All manifest identities/versions and other semantics must match');
      assert.match(manifest, new RegExp(`package="${applicationId.replaceAll('.', '\\.')}"`));
      assert.ok(manifest.includes(applicationId + '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'));
      assert.ok(manifest.includes(applicationId + '.androidx-startup'));
      assert.ok(manifest.includes(applicationId + '.templates'));
      manifests.push(manifest);
      phase = label + '-jarsigner';
      const verified = run(jarsigner, ['-verify', '-strict', '-verbose', '-certs', ...trustArgs, bundleFile]);
      assert.match(verified.stdout, /jar verified\./);
      // Read actual signer from the retained AAB, not merely our intended key.
      const pem = run(keytool, ['-printcert', '-rfc', '-jarfile', bundleFile]).stdout;
      const actualCertificate = new X509Certificate(pem.match(/-----BEGIN CERTIFICATE-----[\s\S]+?-----END CERTIFICATE-----/)?.[0] ?? '');
      assert.equal(digest(actualCertificate.raw), certificateSha256);
      builds.push({ file, sha256: digest(await readFile(bundleFile)), versionCode, applicationId,
        versionName: descriptor.releaseTag.slice(1), certificateSha256, bundletoolValidate: true,
        jarsignerStrict: true, manifestSha256: digest(manifest), changedEntries: changed });
      lastEntries = entries;
    }
    assert.deepEqual(payloadHashes.first, payloadHashes.update);
    assert.equal(manifests[0].replace('android:versionCode="12345"', 'android:versionCode="12346"'), manifests[1]);
    assert.deepEqual(identity, initialIdentity);
    assert.equal(digest(templateBytes), descriptor.templateAsset.sha256);
    assert.equal(digest(await readFile(template)), descriptor.templateAsset.sha256);
    phase = 'tampered-payload-negative-control';
    const dex = lastEntries.find(e => e.name === 'base/dex/classes.dex');
    assert.ok(dex?.data.length);
    dex.data[0] ^= 1;
    await writeFile(tamperedFile, writeArchive(lastEntries), { flag: 'wx' });
    const rejected = run(jarsigner, ['-verify', '-strict', ...trustArgs, tamperedFile], true);
    assert.notEqual(rejected.status, 0);
    assert.match(rejected.stdout + rejected.stderr, /digest error|SecurityException/i);
    const codeNative = Object.keys(sourceHashes).filter(name => /^base\/(dex|lib)\//.test(name));
    assert.ok(codeNative.length > 0);
    const evidence = {
      mode: 'local-template-integration', runtime: 'node', releaseMetadata: 'local-fixture-not-live-GitHub',
      releaseTag: descriptor.releaseTag, sourceCommit: descriptor.sourceCommit,
      templateSha256: descriptor.templateAsset.sha256, descriptorSha256: digest(descriptorBytes),
      applicationId, certificateSha256, sameIdentity: true, backupRestore: true,
      templatePreserved: true, unchangedPayloads: true, tamperRejected: true,
      inputMemberCount: sourceEntries.length, unchangedPayloadCount: Object.keys(payloadHashes.first).length,
      codeNativeCount: codeNative.length, payloadHashes: payloadHashes.first, builds,
      limitations: ['Not browser execution', 'Not live release download', 'Not APK generation or installed update', 'Not Play upload'],
    };
    phase = 'write-evidence';
    await writeFile(path.join(outputDir, 'evidence.json'), JSON.stringify(evidence, null, 2) + '\n', { flag: 'wx' });
    return { outputDir, evidenceFile: path.join(outputDir, 'evidence.json') };
  } finally {
    await rm(store, { force: true });
    await rm(tamperedFile, { force: true });
  }
}

try {
  console.log(JSON.stringify(await qualify()));
} catch {
  // Do not export arbitrary exception/tool strings or private identity objects.
  console.error(JSON.stringify({ status: 'failed', phase }));
  process.exitCode = 1;
}
