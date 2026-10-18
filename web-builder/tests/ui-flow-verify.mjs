// Independent verification of downloaded, disposable UI test artifacts only.
// Never point this at a user's backup. The password below belongs to the tests.
import assert from 'node:assert/strict';
import { readFile, writeFile, mkdtemp, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { restoreIdentity } from '../core/identity.mjs';
import { readArchive } from '../core/archive.mjs';
const output = process.env.OAL_UI_OUTPUT;
const tools = process.env.OAL_WEB_TOOLS;
assert.ok(output && tools && process.env.OAL_WEB_TEMPLATE, 'Set OAL_UI_OUTPUT, OAL_WEB_TOOLS and OAL_WEB_TEMPLATE to disposable test paths');
const suffix = process.platform === 'win32' ? '.exe' : '';
const exe = name => join(tools, 'jdk-17', 'bin', name + suffix);
const run = (command, args) => {
  const result = spawnSync(command, args, { timeout: 120000, encoding: 'utf8', maxBuffer: 4 * 1024 ** 2 });
  assert.equal(result.status, 0, 'Independent Java verification failed (no private data logged)');
  return result.stdout + result.stderr;
};
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const template = readArchive(new Uint8Array(await readFile(process.env.OAL_WEB_TEMPLATE)));
const scratch = await mkdtemp(join(process.env.TMPDIR || tmpdir(), 'ui-test-java-'));
const builds = [];
try {
  for (const [name, initialName, versionCode] of [['first', 'first-initial', 1], ['update', 'update-initial', 31], ['carplay', 'carplay-initial', 1]]) {
    const identity = await restoreIdentity(new Uint8Array(await readFile(join(output, name + '.backup.json'))), 'disposable UI integration password');
    const initial = await restoreIdentity(new Uint8Array(await readFile(join(output, initialName + '.backup.json'))), 'disposable UI integration password');
    assert.equal(identity.applicationId, initial.applicationId);
    assert.equal(identity.certificatePem, initial.certificatePem);
    assert.equal(identity.versionCodeHighWaterMark, versionCode);
    const pem = join(scratch, name + '.pem'), trust = join(scratch, name + '.p12');
    await writeFile(pem, identity.certificatePem);
    run(exe('keytool'), ['-importcert', '-noprompt', '-alias', 'upload', '-file', pem, '-keystore', trust, '-storetype', 'PKCS12', '-storepass', 'disposable-test-trust']);
    const aab = join(output, name + '.aab');
    run(exe('java'), ['-jar', join(tools, 'bundletool-1.18.3.jar'), 'validate', '--bundle=' + aab]);
    assert.match(run(exe('jarsigner'), ['-verify', '-strict', '-keystore', trust, '-storepass', 'disposable-test-trust', aab]), /jar verified/);
    const manifest = run(exe('java'), ['-jar', join(tools, 'bundletool-1.18.3.jar'), 'dump', 'manifest', '--bundle=' + aab]);
    assert.ok(manifest.includes('package="' + identity.applicationId + '"'));
    assert.ok(manifest.includes('android:versionCode="' + versionCode + '"'));
    const bytes = await readFile(aab), entries = readArchive(new Uint8Array(bytes));
    for (const entry of template) if (!['base/manifest/AndroidManifest.xml', 'base/resources.pb'].includes(entry.name)) {
      assert.equal(hash(entries.find(e => e.name === entry.name).data), hash(entry.data));
    }
    builds.push({ file: name + '.aab', versionCode, applicationId: identity.applicationId,
      certificateSha256: hash(identity.certificatePem), sha256: hash(bytes), bundletoolValidated: true,
      jarsignerStrictVerified: true, unchangedTemplatePayloads: true, sameIdentityAsInitialBackup: true });
  }
  const evidence = { scope: 'Local-template Playwright UI tests with intercepted release metadata; NOT live release qualification, Play acceptance or vehicle/Apple compatibility', builds };
  await writeFile(join(output, 'java-verification.json'), JSON.stringify(evidence, null, 2));
  console.log(JSON.stringify(evidence, null, 2));
} finally { await rm(scratch, { recursive: true, force: true }); }
