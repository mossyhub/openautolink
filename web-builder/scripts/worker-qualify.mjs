#!/usr/bin/env node
/** Real Chromium dedicated-worker qualification against an ALREADY running Vite
 * on http://127.0.0.1:5187. Never starts a server or opens existing private keys.
 * OAL_WEB_TEMPLATE, OAL_WEB_TOOLS required; OAL_WEB_DESCRIPTOR defaults beside
 * template; OAL_BROWSER selects Chromium. Saves only AABs, encrypted backups,
 * public certificate and sanitized evidence under qualification-output/worker.
 * Generated signing/backup/truststore passwords remain in memory, never on disk.
 */
import assert from 'node:assert/strict';
import { chromium } from '@playwright/test';
import { readFile, writeFile, mkdir, mkdtemp, rm } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join, dirname } from 'node:path';
import { createHash, randomBytes, X509Certificate } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { validateDescriptor, verifyTemplateBytes, MAX_DESCRIPTOR_SIZE } from '../core/releases.mjs';
import { readArchive } from '../core/archive.mjs';
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const root = fileURLToPath(new URL('../qualification-output/worker/', import.meta.url));
const origin = 'http://127.0.0.1:5187';
let phase = 'configuration';

async function qualify() {
  const templatePath = process.env.OAL_WEB_TEMPLATE, tools = process.env.OAL_WEB_TOOLS;
  assert.ok(templatePath && tools);
  phase = 'fixture';
  const template = await readFile(templatePath);
  const descriptorBytes = await readFile(process.env.OAL_WEB_DESCRIPTOR || join(dirname(templatePath), 'openautolink-personalization.json'));
  assert.ok(descriptorBytes.length <= MAX_DESCRIPTOR_SIZE);
  const parsed = JSON.parse(descriptorBytes.toString('utf8'));
  const asset = parsed.templateAsset;
  const url = name => `https://github.com/mossyhub/openautolink/releases/download/${parsed.releaseTag}/${name}`;
  // Synthetic release metadata for a real local publication fixture; NOT live GitHub evidence.
  const descriptor = validateDescriptor(parsed, { sourceRef: parsed.releaseTag, sourceCommit: parsed.sourceCommit, assets: {
    'openautolink-personalization.json': { size: descriptorBytes.length, browser_download_url: url('openautolink-personalization.json') },
    [asset.name]: { size: asset.size, digest: `sha256:${asset.sha256}`, browser_download_url: url(asset.name) },
  } });
  await verifyTemplateBytes(new Uint8Array(template), descriptor);
  await mkdir(root, { recursive: true });
  const outputDir = await mkdtemp(join(root, 'run-'));
  const requests = [], unexpected = [], saved = new Set();
  phase = 'chromium-launch';
  const browser = await chromium.launch({ executablePath: process.env.OAL_BROWSER, headless: true,
    args: ['--no-sandbox', '--disable-dev-shm-usage'] });
  let result;
  try {
    const context = await browser.newContext({ serviceWorkers: 'block' });
    await context.route('**/*', async route => {
      const request = route.request(), target = new URL(request.url());
      if (target.origin !== origin || request.method() !== 'GET') {
        unexpected.push({ method: request.method(), external: target.origin !== origin });
        return route.abort();
      }
      requests.push({ method: request.method(), path: target.pathname });
      if (target.pathname === '/') return route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>Worker qualification</title>' });
      if (target.pathname === '/qualification/template.aab') return route.fulfill({ contentType: 'application/octet-stream', body: template });
      return route.continue();
    });
    const page = await context.newPage();
    let workerCount = 0, closedWorkerCount = 0;
    const workerTimings = [];
    const workerClosures = [];
    page.on('worker', worker => {
      workerCount++;
      const timing = { index: workerCount, opened: performance.now() }; workerTimings.push(timing);
      workerClosures.push(new Promise(resolve => worker.once('close', () => { closedWorkerCount++; timing.closed = performance.now(); resolve(); })));
    });
    await page.exposeFunction('savePublicArtifact', async (name, base64) => {
      assert.ok(['first.aab', 'update.aab', 'first.backup.json', 'update.backup.json', 'upload.pem'].includes(name));
      assert.ok(!saved.has(name)); saved.add(name);
      await writeFile(join(outputDir, name), Buffer.from(base64, 'base64'), { flag: 'wx' });
    });
    await page.exposeFunction('qualificationPhase', value => {
      assert.ok(['first-build', 'first-backup', 'update-build', 'update-backup', 'cancel', 'timeout', 'worker-count'].includes(value));
      phase = value;
    });
    await page.goto(origin);
    phase = 'browser-worker-builds';
    result = await page.evaluate(async descriptor => {
      const check = value => { if (!value) throw Error('Qualification assertion failed'); };
      const [{ runBuildInWorker }, { createIdentity, restoreIdentity }] = await Promise.all([
        import('/core/worker-client.mjs'), import('/core/identity.mjs'),
      ]);
      check(isSecureContext && crypto.subtle);
      const templateBytes = new Uint8Array(await (await fetch('/qualification/template.aab')).arrayBuffer());
      const hex = bytes => Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
      const digest = async bytes => hex(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)));
      check(await digest(templateBytes) === descriptor.templateAsset.sha256);
      let identity = await createIdentity('com.example.oalworkerqualification');
      const certificate = identity.certificatePem;
      const password = hex(crypto.getRandomValues(new Uint8Array(32)));
      const save = async (name, bytes) => {
        let binary = '';
        for (let i = 0; i < bytes.length; i += 32768) binary += String.fromCharCode(...bytes.subarray(i, i + 32768));
        await window.savePublicArtifact(name, btoa(binary));
      };
      await save('upload.pem', new TextEncoder().encode(certificate));
      const builds = [];
      for (const [label, versionCode] of [['first', 12345], ['update', 12346]]) {
        await window.qualificationPhase(label + '-build');
        const snapshot = JSON.stringify(identity), phases = [];
        let ticks = 0;
        const interval = setInterval(() => ticks++, 10), start = performance.now();
        let built;
        try {
          built = await runBuildInWorker({ templateBytes, descriptor, identity, versionCode, backupPassword: password },
            { timeoutMs: 120000, onProgress: progress => phases.push(progress.phase) });
        } finally { clearInterval(interval); }
        check(ticks > 0 && phases.join(',') === 'building,complete');
        check(JSON.stringify(identity) === snapshot && built.identity.certificatePem === certificate);
        check(built.identity.versionCodeHighWaterMark === versionCode);
        const restored = await restoreIdentity(built.backupBytes, password);
        await window.qualificationPhase(label + '-backup');
        check(JSON.stringify(restored) === JSON.stringify(built.identity));
        let wrongRejected = false;
        try { await restoreIdentity(built.backupBytes, password + 'wrong'); } catch { wrongRejected = true; }
        check(wrongRejected);
        identity = restored; // The actual update uses the first encrypted backup's restored key.
        await save(label + '.aab', built.aabBytes);
        await save(label + '.backup.json', built.backupBytes);
        builds.push({ file: label + '.aab', versionCode, sha256: await digest(built.aabBytes), size: built.aabBytes.length,
          progress: phases, mainThreadTicks: ticks, elapsedMs: Math.round(performance.now() - start) });
      }
      // Cancel on the worker's first progress event, not before worker creation.
      const controller = new AbortController(); let reachedWorker = false, cancelStart = 0, aborted = false;
      await window.qualificationPhase('cancel');
      try {
        await runBuildInWorker({ templateBytes, descriptor, identity, versionCode: 12347, backupPassword: password }, {
          signal: controller.signal, onProgress: () => { reachedWorker = true; cancelStart = performance.now(); controller.abort(); },
        });
      } catch (error) { aborted = error.name === 'AbortError'; }
      const cancelElapsedMs = Math.round(performance.now() - cancelStart);
      check(reachedWorker && aborted && cancelElapsedMs < 1000);
      let timeoutRejected = false;
      await window.qualificationPhase('timeout');
      try {
        await runBuildInWorker({ templateBytes, descriptor, identity, versionCode: 12347, backupPassword: password }, { timeoutMs: 1 });
      } catch (error) { timeoutRejected = error.name === 'TimeoutError'; }
      check(timeoutRejected && await digest(templateBytes) === descriptor.templateAsset.sha256);
      return { browser: navigator.userAgent, secureContext: isSecureContext, applicationId: identity.applicationId,
        backupRestore: true, wrongPasswordRejected: true, certificateUnchanged: true, templatePreserved: true,
        cancellation: { reachedWorker, aborted, cancelElapsedMs }, timeoutRejected, builds };
    }, descriptor);
    phase = 'worker-count';
    // Chromium may terminate the 1ms-timeout worker before Playwright attaches
    // and emits its worker event. The first/update/cancel workers MUST appear.
    assert.ok(workerCount === 3 || workerCount === 4);
    assert.deepEqual(unexpected, []);
    result.workerCount = workerCount;
    phase = 'worker-shutdown';
    let shutdownTimer;
    try {
      await Promise.race([Promise.all(workerClosures), new Promise((_, reject) => {
        shutdownTimer = setTimeout(() => reject(new Error('Worker shutdown deadline exceeded')), 15000);
      })]);
    } finally { clearTimeout(shutdownTimer); }
    assert.equal(closedWorkerCount, workerCount);
    const cancelledWorker = workerTimings[2];
    // Includes startup, so this is an upper bound, NOT an exact cancel latency.
    // The caller's AbortError settles sooner than Chromium's target teardown.
    result.cancellation.workerLifetimeUpperBoundMs = Math.ceil(cancelledWorker.closed - cancelledWorker.opened);
    result.cancellation.nativeCloseDeadlineMs = 15000;
    result.closedWorkerCount = closedWorkerCount;
    const cdp = await browser.newBrowserCDPSession();
    const targets = (await cdp.send('Target.getTargets')).targetInfos;
    assert.equal(targets.filter(target => target.type === 'worker').length, 0);
    await cdp.detach();
    await context.close();
  } finally { await browser.close(); }
  assert.equal(saved.size, 5);
  phase = 'independent-verification';
  const trustPassword = randomBytes(32).toString('hex'), trustFile = join(outputDir, 'public-trust.p12');
  const run = (name, args) => {
    const output = spawnSync(join(tools, 'jdk-17/bin', name), args, { encoding: 'utf8', timeout: 120000,
      maxBuffer: 16 * 1024 ** 2, env: { ...process.env, OAL_WORKER_TRUST_PASSWORD: trustPassword } });
    assert.ok(!output.error && output.status === 0, 'Independent tool verification failed');
    return output.stdout + output.stderr;
  };
  const bundle = args => run('java', ['-Xmx512m', '-jar', join(tools, 'bundletool-1.18.3.jar'), ...args]);
  const trust = ['-keystore', trustFile, '-storepass:env', 'OAL_WORKER_TRUST_PASSWORD'];
  const certificate = new X509Certificate(await readFile(join(outputDir, 'upload.pem')));
  const certificateSha256 = hash(certificate.raw);
  const source = readArchive(new Uint8Array(template));
  const sourceHashes = new Map(source.map(entry => [entry.name, hash(entry.data)]));
  try {
    run('keytool', ['-importcert', '-noprompt', '-alias', 'public-upload', '-file', join(outputDir, 'upload.pem'), '-storetype', 'PKCS12', ...trust]);
    for (const build of result.builds) {
      const file = join(outputDir, build.file), bytes = await readFile(file);
      assert.equal(hash(bytes), build.sha256);
      bundle(['validate', '--bundle=' + file]);
      assert.match(run('jarsigner', ['-verify', '-strict', ...trust, file]), /jar verified\./);
      const actualPem = run('keytool', ['-printcert', '-rfc', '-jarfile', file]).match(/-----BEGIN CERTIFICATE-----[\s\S]+?-----END CERTIFICATE-----/)?.[0];
      assert.equal(hash(new X509Certificate(actualPem).raw), certificateSha256);
      const manifest = bundle(['dump', 'manifest', '--bundle=' + file]);
      assert.ok(manifest.includes('package="' + result.applicationId + '"'));
      assert.ok(manifest.includes('android:versionCode="' + build.versionCode + '"'));
      const entries = readArchive(new Uint8Array(bytes));
      const unsigned = entries.filter(entry => !['META-INF/MANIFEST.MF', 'META-INF/OAL.SF', 'META-INF/OAL.RSA'].includes(entry.name));
      assert.deepEqual(unsigned.map(entry => entry.name), source.map(entry => entry.name));
      const changed = unsigned.filter(entry => hash(entry.data) !== sourceHashes.get(entry.name)).map(entry => entry.name).sort();
      assert.deepEqual(changed, ['base/manifest/AndroidManifest.xml', 'base/resources.pb']);
      Object.assign(build, { bundletoolValidated: true, jarsignerStrictVerified: true, certificateSha256, unchangedPayloads: true });
    }
  } finally { await rm(trustFile, { force: true }); }
  assert.equal(hash(await readFile(templatePath)), descriptor.templateAsset.sha256);
  Object.assign(result, { status: 'passed', templateSha256: descriptor.templateAsset.sha256,
    descriptorSha256: hash(descriptorBytes), sourceCommit: descriptor.sourceCommit, releaseTag: descriptor.releaseTag,
    requests, unexpectedNetworkRequests: unexpected,
    limitations: ['Local publication fixture, not live GitHub release download', 'Chromium/Linux only; not Firefox/Safari',
      'Not Play upload, APK generation, installed update, native desktop or release qualification',
      'Fresh disposable identity; browser backup format only; no durable reservation UI exercised'] });
  await writeFile(join(outputDir, 'result.json'), JSON.stringify(result, null, 2) + '\n', { flag: 'wx' });
  return { outputDir, ...result };
}
try { console.log(JSON.stringify(await qualify(), null, 2)); }
catch { console.error(JSON.stringify({ status: 'failed', phase })); process.exitCode = 1; }
