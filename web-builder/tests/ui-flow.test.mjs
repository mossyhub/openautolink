import test from 'node:test';
import assert from 'node:assert/strict';
import { chromium } from '@playwright/test';
import { readFile, mkdir } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { createHash } from 'node:crypto';
import { createIdentity, backupIdentity, restoreIdentity } from '../core/identity.mjs';
import { readArchive } from '../core/archive.mjs';
const enabled = process.env.OAL_UI_TEST === '1';
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const password = 'disposable UI integration password';
const output = process.env.OAL_UI_OUTPUT || join(process.env.TMPDIR || process.cwd(), 'oal-ui-flow-evidence');
async function fixtureRoutes(page) {
  // Optional built-asset interception exercises the production bundle and CSP
  // on the existing local test origin; it never starts or changes a server.
  if (process.env.OAL_UI_DIST) {
    const dist = process.env.OAL_UI_DIST;
    const config = JSON.parse(await readFile(join(dist, 'staticwebapp.config.json'), 'utf8'));
    await page.route(`${origin}/**`, async route => {
      const path = new URL(route.request().url()).pathname;
      if (path !== '/' && !/^\/assets\/[A-Za-z0-9._-]+$/.test(path)) return route.fallback();
      const name = path === '/' ? 'index.html' : path.slice(1);
      return route.fulfill({ body: await readFile(join(dist, name)), headers: config.globalHeaders,
        contentType: name.endsWith('.css') ? 'text/css' : name.endsWith('.js') ? 'text/javascript' : 'text/html' });
    });
  }
  const template = await readFile(process.env.OAL_WEB_TEMPLATE);
  const raw = await readFile(process.env.OAL_WEB_DESCRIPTOR || join(dirname(process.env.OAL_WEB_TEMPLATE), 'openautolink-personalization.json'));
  const descriptor = JSON.parse(raw);
  const fixtureTag = descriptor.releaseTag;
  const asset = (name, bytes) => ({ name, size: bytes.length, digest: `sha256:${hash(bytes)}`,
    browser_download_url: `${web}/releases/download/${fixtureTag}/${name}` });
  await page.route(`${api}/**`, route => route.fulfill({ json:
    route.request().url().endsWith('/releases/latest')
      ? { tag_name: fixtureTag, draft: false, prerelease: false, html_url: `${web}/releases/tag/${fixtureTag}`,
        assets: [asset('openautolink-personalization.json', raw), asset(descriptor.templateAsset.name, template)] }
      : { ref: `refs/tags/${fixtureTag}`, object: { type: 'commit', sha: descriptor.sourceCommit } }
  }));
  await page.route(`${origin}/templates/**`, route => route.fulfill({ contentType: 'application/octet-stream',
    body: route.request().url().endsWith('.json') ? raw : template }));
  return descriptor;
}
async function download(page, name, file) {
  const promise = page.waitForEvent('download');
  await page.getByRole('button', { name, exact: true }).click();
  const result = await promise;
  await result.saveAs(join(output, file));
  return readFile(join(output, file));
}
const origin = process.env.OAL_UI_ORIGIN || 'http://127.0.0.1:5187';
const api = 'https://api.github.com/repos/mossyhub/openautolink';
const web = 'https://github.com/mossyhub/openautolink';
const tag = 'v0.1.528';
// Explicit test-only release metadata. Never shipped by the application.
async function launch() {
  await mkdir(output, { recursive: true });
  return chromium.launch({ executablePath: process.env.OAL_BROWSER || undefined, headless: true,
    args: process.platform === 'linux' ? ['--no-sandbox', '--disable-dev-shm-usage'] : [] });
}
test('UI: missing latest public template fails clearly without older fallback', { skip: !enabled }, async () => {
  const browser = await launch();
  try {
    const page = await browser.newPage();
    const requests = [];
    await page.route(`${api}/**`, route => {
      requests.push(route.request().url());
      const body = route.request().url().endsWith('/releases/latest')
        ? { tag_name: tag, draft: false, prerelease: false, html_url: `${web}/releases/tag/${tag}`, assets: [] }
        : { ref: `refs/tags/${tag}`, object: { type: 'commit', sha: 'a'.repeat(40) } };
      return route.fulfill({ json: body });
    });
    await page.goto(origin);
    await page.getByRole('heading', { name: 'Personal OAL builder' }).waitFor({ timeout: 10000 });
    await page.getByRole('alert').filter({ hasText: 'Latest template unavailable' }).waitFor();
    assert.match(await page.locator('main').innerText(), /older releases are not used/i);
    assert.equal(await page.getByRole('button', { name: 'Continue to identity' }).isEnabled(), false);
    assert.deepEqual(requests, [`${api}/releases/latest`, `${api}/git/ref/tags/${tag}`]);
    await page.getByRole('button', { name: 'Check latest again' }).click();
    await page.getByRole('alert').filter({ hasText: 'Latest template unavailable' }).waitFor();
    assert.equal(requests.length, 4);
  } finally { await browser.close(); }
});

test('UI: local-template test creates a genuinely signed AAB only after both backup gates', { skip: !enabled, timeout: 240000 }, async () => {
  assert.ok(process.env.OAL_WEB_TEMPLATE, 'Set OAL_WEB_TEMPLATE to an actual local-template test fixture');
  await mkdir(output, { recursive: true });
  const browser = await launch();
  try {
    const page = await browser.newPage({ reducedMotion: 'reduce', viewport: { width: 1280, height: 900 }, acceptDownloads: true });
    page.setDefaultTimeout(15000);
    await fixtureRoutes(page);
    let workers = 0;
    const unexpected = [];
    page.on('worker', () => workers++);
    page.on('request', r => { if (r.method() !== 'GET' || (!r.url().startsWith(origin) && !r.url().startsWith(api))) unexpected.push(r.method()); });
    await page.goto(origin);
    await page.getByRole('button', { name: 'Continue to identity' }).click();
    await page.getByLabel('Backup password', { exact: true }).fill(password);
    await page.getByLabel('Confirm backup password').fill('different test password');
    await page.getByRole('button', { name: 'Create identity & backup' }).click();
    await page.getByRole('alert').filter({ hasText: 'Passwords do not match' }).waitFor();
    assert.equal(workers, 0);
    await page.getByLabel('Confirm backup password').fill(password);
    await page.getByRole('button', { name: 'Create identity & backup' }).click();
    const build = page.getByRole('button', { name: 'Build AAB', exact: true });
    await build.waitFor();
    assert.equal(await build.isEnabled(), false);
    assert.equal(await page.getByLabel('I saved the initial web backup and can find it.').isEnabled(), false);
    const initial = await download(page, 'Download initial web backup', 'first-initial.backup.json');
    const initialIdentity = await restoreIdentity(new Uint8Array(initial), password);
    assert.match(initialIdentity.applicationId, /^com\.personal\.oal\.user[a-z]{32}$/);
    assert.equal(initialIdentity.versionCodeHighWaterMark, 0);
    assert.equal(await build.isEnabled(), false);
    await page.getByLabel('I saved the initial web backup and can find it.').check();
    await page.screenshot({ path: join(output, 'desktop-save.png'), fullPage: true });
    await build.click();
    await page.getByRole('heading', { name: 'Save your build files' }).waitFor({ timeout: 180000 });
    assert.equal(workers, 1, 'real dedicated build worker, no mock signing');
    const aabButton = page.getByRole('button', { name: 'Download AAB', exact: true });
    assert.equal(await aabButton.isEnabled(), false);
    const backup = await download(page, 'Download updated web backup', 'first.backup.json');
    assert.equal(await aabButton.isEnabled(), false);
    await page.getByLabel('I saved the updated web backup and know its password.').check();
    const aab = await download(page, 'Download AAB', 'first.aab');
    const restored = await restoreIdentity(new Uint8Array(backup), password);
    assert.equal(restored.applicationId, initialIdentity.applicationId);
    assert.equal(restored.certificatePem, initialIdentity.certificatePem);
    assert.equal(restored.versionCodeHighWaterMark, 1);
    const entries = readArchive(new Uint8Array(aab));
    assert.ok(entries.some(e => e.name === 'META-INF/OAL.RSA' && e.data.length > 100));
    assert.equal(entries.some(e => e.name.includes('offline-mfi')), false);
    assert.equal(await page.locator('input[type=password]').count(), 0);
    const storage = await page.evaluate(() => ({ local: { ...localStorage }, session: { ...sessionStorage } }));
    assert.equal(Object.keys(storage.local).length, 1);
    assert.equal(Object.keys(storage.session).length, 0);
    assert.doesNotMatch(JSON.stringify(storage), /PRIVATE KEY|privateKeyPem|ciphertext|password|certificatePem/);
    assert.equal(JSON.parse(Object.values(storage.local)[0]).highWaterMark, 1);
    assert.deepEqual(unexpected, []);
    await page.screenshot({ path: join(output, 'desktop-downloads.png'), fullPage: true });
    // A real reload discards private state; recovery is only by encrypted WEB backup.
    await page.reload();
    await page.getByRole('button', { name: 'Continue to identity' }).click();
    await page.getByLabel('Update my existing app').check();
    await page.getByLabel('Encrypted web backup').setInputFiles({ name: 'first.web-backup.json', mimeType: 'application/json', buffer: backup });
    await page.getByLabel('Backup password', { exact: true }).fill(password);
    await page.getByLabel('Highest version code in Google Play').fill('1');
    await page.getByRole('button', { name: 'Restore web identity' }).click();
    const recovered = await download(page, 'Download initial web backup', 'reload-restored.backup.json');
    const recoveredIdentity = await restoreIdentity(new Uint8Array(recovered), password);
    assert.equal(recoveredIdentity.applicationId, restored.applicationId);
    assert.equal(recoveredIdentity.certificatePem, restored.certificatePem);
    assert.equal(recoveredIdentity.versionCodeHighWaterMark, 1);
  } finally { await browser.close(); }
});

test('UI: restore requires a web backup, password and Play high-water; cancellation never reuses its reservation', { skip: !enabled, timeout: 240000 }, async () => {
  await mkdir(output, { recursive: true });
  const original = await createIdentity('com.personal.oal.useruitestupdate');
  original.versionCodeHighWaterMark = 11;
  const backup = Buffer.from(await backupIdentity(original, password));
  const browser = await launch();
  try {
    const page = await browser.newPage({ reducedMotion: 'reduce', viewport: { width: 1280, height: 900 }, acceptDownloads: true });
    page.setDefaultTimeout(15000);
    await fixtureRoutes(page);
    await page.goto(origin);
    await page.getByRole('button', { name: 'Continue to identity' }).click();
    await page.getByLabel('Update my existing app').check();
    assert.equal(await page.getByLabel('Confirm backup password').count(), 0);
    await page.getByRole('button', { name: 'Restore web identity' }).click();
    assert.equal(await page.getByLabel('Encrypted web backup').evaluate(e => e.validity.valueMissing), true);
    await page.getByLabel('Encrypted web backup').setInputFiles({ name: 'test.web-backup.json', mimeType: 'application/json', buffer: backup });
    await page.getByLabel('Backup password', { exact: true }).fill('wrong test password');
    await page.getByRole('button', { name: 'Restore web identity' }).click();
    assert.equal(await page.getByLabel('Highest version code in Google Play').evaluate(e => e.validity.valueMissing), true);
    await page.getByLabel('Highest version code in Google Play').fill('29');
    await page.getByRole('button', { name: 'Restore web identity' }).click();
    await page.getByRole('alert').filter({ hasText: 'Wrong password or damaged web backup' }).waitFor();
    assert.match(await page.getByRole('status').innerText(), /Identity not prepared/);
    assert.equal(await page.getByRole('button', { name: 'Build AAB', exact: true }).count(), 0);
    await page.getByLabel('Backup password', { exact: true }).fill(password);
    await page.screenshot({ path: join(output, 'desktop-update.png'), fullPage: true });
    await page.getByRole('button', { name: 'Restore web identity' }).click();
    const initial = await download(page, 'Download initial web backup', 'update-initial.backup.json');
    assert.equal((await restoreIdentity(new Uint8Array(initial), password)).certificatePem, original.certificatePem);
    await page.getByLabel('I saved the initial web backup and can find it.').check();
    const workerStarted = page.waitForEvent('worker');
    await page.getByRole('button', { name: 'Build AAB', exact: true }).click();
    await workerStarted;
    await page.getByRole('button', { name: 'Cancel build', exact: true }).click();
    await page.getByRole('status').filter({ hasText: 'Build cancelled' }).waitFor();
    assert.equal(await page.getByRole('button', { name: 'Download AAB', exact: true }).count(), 0);
    const highWater = () => page.evaluate(id => JSON.parse(localStorage.getItem('oal-version-v1:' + id)).highWaterMark, original.applicationId);
    assert.equal(await highWater(), 30);
    await page.getByRole('button', { name: 'Build AAB', exact: true }).click();
    await page.getByRole('heading', { name: 'Save your build files' }).waitFor({ timeout: 180000 });
    const updated = await download(page, 'Download updated web backup', 'update.backup.json');
    const restored = await restoreIdentity(new Uint8Array(updated), password);
    assert.equal(restored.applicationId, original.applicationId);
    assert.equal(restored.certificatePem, original.certificatePem);
    assert.equal(restored.versionCodeHighWaterMark, 31);
    assert.equal(await highWater(), 31);
    await page.getByLabel('I saved the updated web backup and know its password.').check();
    await download(page, 'Download AAB', 'update.aab');
  } finally { await browser.close(); }
});

test('UI: optional CarPlay demands an authorized matching pair and keeps it out of web backups', { skip: !enabled, timeout: 240000 }, async () => {
  const { mkdtemp, rm } = await import('node:fs/promises');
  const { tmpdir } = await import('node:os');
  const { spawnSync } = await import('node:child_process');
  const { fileURLToPath } = await import('node:url');
  const fixtures = await mkdtemp(join(process.env.TMPDIR || tmpdir(), 'ui-synthetic-carplay-'));
  const browser = await launch();
  try {
    const generated = spawnSync(process.env.CARPLAY_FIXTURE_PYTHON || (process.platform === 'win32' ? 'python' : 'python3'),
      [fileURLToPath(new URL('./carplay-fixtures/generate.py', import.meta.url)), fixtures], { timeout: 30000, encoding: 'utf8' });
    assert.equal(generated.status, 0, 'Synthetic fixtures require Python cryptography; configure CARPLAY_FIXTURE_PYTHON');
    const key = await readFile(join(fixtures, 'p256.pk8'));
    const cert = await readFile(join(fixtures, 'single.p7b'));
    const page = await browser.newPage({ reducedMotion: 'reduce', viewport: { width: 1280, height: 1100 }, acceptDownloads: true });
    page.setDefaultTimeout(15000);
    await fixtureRoutes(page);
    await page.goto(origin);
    await page.getByRole('button', { name: 'Continue to identity' }).click();
    await page.getByLabel('Backup password', { exact: true }).fill(password);
    await page.getByLabel('Confirm backup password').fill(password);
    await page.getByText('Optional CarPlay files', { exact: true }).click();
    await page.getByLabel('CarPlay private key').setInputFiles({ name: 'SYNTHETIC-TEST.pk8', mimeType: 'application/octet-stream', buffer: key });
    await page.getByRole('button', { name: 'Create identity & backup' }).click();
    await page.getByRole('alert').filter({ hasText: 'Choose both CarPlay files or neither' }).waitFor();
    await page.getByLabel('CarPlay certificate').setInputFiles({ name: 'SYNTHETIC-TEST.p7b', mimeType: 'application/octet-stream', buffer: cert });
    await page.getByRole('button', { name: 'Create identity & backup' }).click();
    await page.getByRole('alert').filter({ hasText: 'Confirm you are authorized' }).waitFor();
    await page.getByLabel('I am authorized to use this pair and understand the files are extractable from the AAB.').check();
    await page.getByLabel('CarPlay certificate').setInputFiles({ name: 'MISMATCH-SYNTHETIC-TEST.der', mimeType: 'application/octet-stream', buffer: await readFile(join(fixtures, 'other.der')) });
    await page.getByRole('button', { name: 'Create identity & backup' }).click();
    await page.getByRole('alert').filter({ hasText: 'CarPlay files could not be validated' }).waitFor();
    await page.getByLabel('CarPlay certificate').setInputFiles({ name: 'SYNTHETIC-TEST.p7b', mimeType: 'application/octet-stream', buffer: cert });
    await page.screenshot({ path: join(output, 'desktop-carplay.png'), fullPage: true });
    await page.getByRole('button', { name: 'Create identity & backup' }).click();
    const initial = await download(page, 'Download initial web backup', 'carplay-initial.backup.json');
    const initialIdentity = await restoreIdentity(new Uint8Array(initial), password);
    await page.getByLabel('I saved the initial web backup and can find it.').check();
    await page.getByRole('button', { name: 'Build AAB', exact: true }).click();
    await page.getByRole('heading', { name: 'Save your build files' }).waitFor({ timeout: 180000 });
    const backup = await download(page, 'Download updated web backup', 'carplay.backup.json');
    const restored = await restoreIdentity(new Uint8Array(backup), password);
    assert.deepEqual(Object.keys(restored).sort(), Object.keys(initialIdentity).sort());
    assert.equal(restored.privateKeyPem, initialIdentity.privateKeyPem);
    await page.getByLabel('I saved the updated web backup and know its password.').check();
    const aab = await download(page, 'Download AAB', 'carplay.aab');
    const entries = readArchive(new Uint8Array(aab));
    assert.deepEqual(Buffer.from(entries.find(e => e.name === 'base/assets/offline-mfi/identity.pk8').data), key);
    assert.deepEqual(Buffer.from(entries.find(e => e.name === 'base/assets/offline-mfi/certificate.p7b').data), cert);
    assert.equal(await page.locator('input[type=file]').count(), 0);
  } finally { await browser.close(); await rm(fixtures, { recursive: true, force: true }); }
});

test('UI: clearing and page lifecycle revoke download URLs, erase form data and recheck latest', { skip: !enabled, timeout: 60000 }, async () => {
  const browser = await launch();
  try {
    const page = await browser.newPage({ reducedMotion: 'reduce', viewport: { width: 390, height: 844 }, acceptDownloads: true });
    page.setDefaultTimeout(10000);
    await fixtureRoutes(page);
    await page.addInitScript(() => {
      const create = URL.createObjectURL.bind(URL), revoke = URL.revokeObjectURL.bind(URL);
      window.testObjectUrls = [];
      window.testRevokedUrls = [];
      URL.createObjectURL = blob => { const url = create(blob); window.testObjectUrls.push(url); return url; };
      URL.revokeObjectURL = url => { window.testRevokedUrls.push(url); revoke(url); };
    });
    await page.goto(origin);
    await page.getByRole('button', { name: 'Continue to identity' }).click();
    await page.getByLabel('Backup password', { exact: true }).fill(password);
    await page.getByLabel('Confirm backup password').fill(password);
    await page.getByRole('button', { name: 'Create identity & backup' }).click();
    await download(page, 'Download initial web backup', 'lifecycle-initial.backup.json');
    assert.equal(await page.evaluate(() => window.testObjectUrls.length), 1);
    page.once('dialog', dialog => dialog.accept());
    await page.getByRole('button', { name: 'Clear & start over', exact: true }).click();
    assert.deepEqual(await page.evaluate(() => window.testObjectUrls), await page.evaluate(() => window.testRevokedUrls));
    await page.getByRole('button', { name: 'Continue to identity' }).click();
    assert.equal(await page.getByLabel('Backup password', { exact: true }).inputValue(), '');
    assert.equal(await page.locator('a[download]').count(), 0);
    assert.equal(await page.getByRole('button', { name: 'Build AAB', exact: true }).count(), 0);
    await page.getByLabel('Backup password', { exact: true }).fill('test-lifecycle-private-value');
    await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted: true })));
    await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true })));
    assert.equal(await page.locator('input[type=password]').count(), 0);
    assert.equal(await page.getByRole('button', { name: 'Continue to identity' }).isEnabled(), false);
    await page.getByRole('button', { name: 'Check latest again' }).click();
    await page.getByRole('button', { name: 'Continue to identity' }).click();
    assert.equal(await page.getByLabel('Backup password', { exact: true }).inputValue(), '');
    assert.equal(await page.locator('.small-screen').isVisible(), true);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    await page.getByLabel('Backup password', { exact: true }).focus();
    await page.screenshot({ path: join(output, 'mobile-create.png'), fullPage: true });
  } finally { await browser.close(); }
});

test('UI: unsupported worker capability stops before accepting private files', { skip: !enabled }, async () => {
  const browser = await launch();
  try {
    const page = await browser.newPage();
    await page.addInitScript(() => { Object.defineProperty(window, 'Worker', { value: undefined }); });
    let apiRequests = 0;
    await page.route(`${api}/**`, route => { apiRequests++; return route.abort(); });
    await page.goto(origin);
    await page.getByRole('alert').filter({ hasText: 'Browser features unavailable' }).waitFor({ timeout: 10000 });
    assert.equal(apiRequests, 0);
    assert.equal(await page.getByRole('button', { name: 'Continue to identity' }).isEnabled(), false);
    assert.equal(await page.locator('input[type=password]').count(), 0);
  } finally { await browser.close(); }
});

test('shipping HTML and hosting policy restrict scripts, framing and private-form submission', async () => {
  const config = JSON.parse(await readFile(new URL('../public/staticwebapp.config.json', import.meta.url), 'utf8'));
  const headers = config.globalHeaders;
  assert.match(headers['Content-Security-Policy'], /script-src 'self';/);
  assert.match(headers['Content-Security-Policy'], /worker-src 'self';/);
  assert.match(headers['Content-Security-Policy'], /form-action 'none';/);
  assert.match(headers['Content-Security-Policy'], /frame-ancestors 'none'/);
  assert.doesNotMatch(headers['Content-Security-Policy'], /unsafe-inline|unsafe-eval|\*/);
  assert.equal(headers['Referrer-Policy'], 'no-referrer');
  assert.equal(headers['Cache-Control'], 'no-store');
  const html = await readFile(new URL('../index.html', import.meta.url), 'utf8');
  assert.doesNotMatch(html, /<script[^>]+src=["']https?:/);
  assert.match(html, /Installation guide \(preview, opens a new tab\)/);
});
