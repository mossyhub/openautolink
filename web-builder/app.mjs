import { loadLatestTemplate } from './core/transport.mjs';
import { createIdentity, backupIdentity, restoreIdentity } from './core/identity.mjs';
import { reserveVersion } from './core/version-ledger.mjs';
import { validatePersonalApplicationId } from './core/transform.mjs';
import { runBuildInWorker } from './core/worker-client.mjs';
import { newApplicationId } from './core/ui-state.mjs';
import { validateCarplayPair } from './core/carplay.mjs';
const $ = selector => document.querySelector(selector);
let template = null;
let operation = null;
let identity = null;
let backupPassword = '';
let initialBackup = null;
let result = null;
let initialDownloaded = false;
let finalDownloaded = false;
let lastPlayVersion = 0;
let carplay = null;
let includedCarplay = false;
function clearCarplay() {
  carplay?.keyBytes.fill(0);
  carplay?.certificateBytes.fill(0);
  carplay = null;
}
const urls = new Map();
function revokeDownloads() {
  for (const url of urls.values()) URL.revokeObjectURL(url);
  urls.clear();
}
function saveFile(key, bytes, filename) {
  if (!urls.has(key)) urls.set(key, URL.createObjectURL(new Blob([bytes], { type: key === 'aab' ? 'application/octet-stream' : 'application/json' })));
  const link = document.createElement('a');
  link.href = urls.get(key);
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
}
function showStep(step, markup) {
  $('#release-panel').hidden = step !== 1;
  $('#wizard').innerHTML = markup;
  $('#error').hidden = true;
  document.querySelectorAll('#steps li').forEach((item, i) => {
    if (i + 1 === step) item.setAttribute('aria-current', 'step');
    else item.removeAttribute('aria-current');
  });
  $('#wizard h2')?.focus();
}
function showIdentity(mode = 'create') {
  if (!template) return;
  const updating = mode === 'update';
  status('Latest release selected. Next, protect your app’s signing identity.');
  showStep(2, `<section aria-labelledby="identity-title">
    <h2 id="identity-title" tabindex="-1">Your app’s identity</h2>
    <fieldset><legend>What would you like to do?</legend><div class="mode-options">
      <label class="choice"><input type="radio" name="mode" value="create" ${updating ? '' : 'checked'}><span>Create a new personal app</span></label>
      <label class="choice"><input type="radio" name="mode" value="update" ${updating ? 'checked' : ''}><span>Update my existing app</span></label>
    </div></fieldset>
    <p>${updating ? 'Restore the encrypted backup from this web builder to keep the same app ID and upload signing key. Nothing will create or replace your identity if restoration fails.' : 'A unique app ID and upload signing key will be created on this computer. Keep the encrypted web backup: it is required to update the same app.'}</p>
    <form id="identity-form">
      ${updating ? `<label for="restore-file">Encrypted web backup</label><input id="restore-file" type="file" accept=".json" required aria-describedby="backup-help"><p id="backup-help" class="help">Choose an oal-web-upload-backup JSON file, up to 256 KiB. Desktop/JKS backups are not compatible.</p>` : ''}
      <div class="form-row">
        <div><label for="password">Backup password</label><input id="password" type="password" autocomplete="${updating ? 'current-password' : 'new-password'}" required aria-describedby="password-help"></div>
        ${updating ? `<div><label for="play-version">Highest version code in Google Play</label><input id="play-version" type="text" inputmode="numeric" pattern="[0-9]+" required aria-describedby="play-help"></div>` : `<div><label for="confirm-password">Confirm backup password</label><input id="confirm-password" type="password" autocomplete="new-password" required></div>`}
      </div>
      <p id="password-help" class="help">${updating ? 'Enter the password used to encrypt this web backup. The updated backup will use the same password.' : 'Use a unique password (at least 8 characters; up to 1,024 UTF-8 bytes). Keep it separately from the backup. There is no password recovery.'}</p>
      ${updating ? '<p id="play-help" class="help">Check all tracks and uploaded bundles in Play Console. Enter the highest version code ever uploaded, or 0 if none. This is a whole number, not the OAL release name. The next version will exceed Play, your backup and this browser’s reservations.</p>' : ''}
      <p class="notice">This is a web-builder backup, not a desktop/JKS backup. Never create a new identity to update an existing Play app.</p>
      <details><summary>Optional CarPlay files</summary>
        <p>Leave both empty unless you already have an authorized matching pair. This builder does not supply credentials or establish Apple authorization or iPhone compatibility.</p>
        <label for="carplay-key">CarPlay private key</label><input id="carplay-key" type="file" accept=".pk8,.der" aria-describedby="carplay-help">
        <label for="carplay-certificate">CarPlay certificate</label><input id="carplay-certificate" type="file" accept=".p7b,.der" aria-describedby="carplay-help">
        <p id="carplay-help" class="help">Unencrypted DER PKCS8 P-256 key and a single matching certificate in DER X.509 or DER PKCS7 format. Each file must be 1 byte–16 KiB. PEM and certificate chains are not supported.</p>
        <p class="notice">These files will be embedded in the AAB and are extractable by anyone who gets the bundle. They are not included in your web backup. Supply the pair again for each update that needs it.</p>
        <label class="choice"><input id="carplay-authorized" type="checkbox"><span>I am authorized to use this pair and understand the files are extractable from the AAB.</span></label>
      </details>
      <div class="actions"><button type="submit" id="prepare" class="primary">${updating ? 'Restore web identity' : 'Create identity &amp; backup'}</button></div>
    </form>
  </section>`);
  document.querySelectorAll('input[name=mode]').forEach(input => input.addEventListener('change', () => {
    const value = input.value;
    showIdentity(value);
    $(`input[name=mode][value=${value}]`).focus();
  }));
  $('#identity-form').addEventListener('submit', prepareIdentity);
}
async function prepareIdentity(event) {
  event.preventDefault();
  if (operation || identity) return;
  $('#error').hidden = true;
  const password = $('#password').value;
  const updating = $('input[name=mode]:checked').value === 'update';
  if (!updating && password !== $('#confirm-password').value) { error('Passwords do not match. Enter the same backup password in both fields.'); return; }
  const size = new TextEncoder().encode(password).length;
  if (size < 8 || (!updating && password.length < 8) || size > 1024) { error('Use a backup password of at least 8 characters and at most 1,024 UTF-8 bytes.'); return; }
  const playValue = updating ? $('#play-version').value : '0';
  if (!/^[0-9]+$/.test(playValue) || !Number.isSafeInteger(Number(playValue)) || Number(playValue) >= 2100000000) {
    error('Enter a whole Play version code from 0 to 2,099,999,999. At the Android version limit no further update can be built.'); return;
  }
  const file = updating ? $('#restore-file').files[0] : null;
  if (updating && (!file || file.size < 100 || file.size > 262144)) {
    error('Choose an encrypted web backup between 100 bytes and 256 KiB. Desktop/JKS backups are not compatible.'); return;
  }
  const keyFile = $('#carplay-key').files[0];
  const certFile = $('#carplay-certificate').files[0];
  if (Boolean(keyFile) !== Boolean(certFile)) { error('Choose both CarPlay files or neither. No identity has been created or changed.'); return; }
  if (keyFile && !$('#carplay-authorized').checked) { error('Confirm you are authorized to use this pair and understand the extractability warning.'); return; }
  if ([keyFile, certFile].some(file => file && (file.size < 1 || file.size > 16384))) { error('Each CarPlay file must be between 1 byte and 16 KiB.'); return; }
  const controller = new AbortController();
  operation = controller;
  document.querySelectorAll('#wizard input, #prepare').forEach(input => { input.disabled = true; });
  status(updating ? 'Restoring and checking your web identity locally…' : 'Creating and encrypting your upload identity locally…');
  let phase = keyFile ? 'carplay' : 'identity';
  try {
    clearCarplay();
    if (keyFile) {
      const rawPair = { keyBytes: new Uint8Array(await keyFile.arrayBuffer()), certificateBytes: new Uint8Array(await certFile.arrayBuffer()) };
      controller.signal.throwIfAborted();
      await validateCarplayPair(rawPair.keyBytes, rawPair.certificateBytes);
      controller.signal.throwIfAborted();
      carplay = rawPair;
    }
    phase = 'identity';
    let created;
    if (updating) {
      created = await restoreIdentity(new Uint8Array(await file.arrayBuffer()), password);
      phase = 'compatibility';
      validatePersonalApplicationId(created.applicationId);
      phase = 'identity';
    } else {
      phase = 'compatibility';
      const applicationId = newApplicationId();
      validatePersonalApplicationId(applicationId);
      phase = 'identity';
      created = await createIdentity(applicationId);
    }
    controller.signal.throwIfAborted();
    const backup = await backupIdentity(created, password);
    controller.signal.throwIfAborted();
    identity = created;
    lastPlayVersion = Number(playValue);
    backupPassword = password;
    initialBackup = backup;
    initialDownloaded = false;
    includedCarplay = Boolean(carplay);
    showSave();
  } catch {
    if (operation !== controller) return;
    clearCarplay();
    if (!controller.signal.aborted) {
      status('Identity not prepared. Correct the inputs below and try again.');
      error(phase === 'compatibility' ? 'This application ID is not compatible with the personal template. Your identity has not been changed or replaced. Keep your original backup; no version has been reserved.' : phase === 'carplay' ? 'CarPlay files could not be validated. Use a matching unencrypted DER PKCS8 P-256 key and a single DER X.509 or PKCS7 certificate, or remove both files.' : updating ? 'Wrong password or damaged web backup. Check your password and choose a valid encrypted WEB backup from this builder. Desktop/JKS backups are not compatible. Your identity has not been replaced.' : 'Could not prepare your web identity backup. Check browser support and try again.');
      document.querySelectorAll('#wizard input, #prepare').forEach(input => { input.disabled = false; });
    }
  } finally { if (operation === controller) operation = null; }
}
function showSave() {
  showStep(3, `<section aria-labelledby="save-title">
    <h2 id="save-title" tabindex="-1">Save your identity before building</h2>
    <p>This initial encrypted backup protects your app ID and signing key if this tab closes. Save it somewhere you can find again, not only in your Downloads folder.</p>
    <dl><dt>Application ID</dt><dd id="app-id" class="identifier"></dd><dt>OAL release</dt><dd id="selected-release"></dd></dl>
    <button id="initial-backup">Download initial web backup</button>
    <label class="choice"><input id="initial-saved" type="checkbox" disabled> <span>I saved the initial web backup and can find it.</span></label>
    <p class="help">After building, save the updated backup too: it includes the new version number. Returning later? Choose Update and restore your web backup.</p>
    <p class="notice">The build runs locally in a worker. A version number is reserved before it starts; cancelled or failed attempts keep that reservation. Gaps are safe.</p>
    <div class="actions"><button id="build" class="primary" disabled>Build AAB</button><button id="cancel-build" hidden>Cancel build</button></div>
  </section>`);
  $('#app-id').textContent = identity.applicationId;
  $('#selected-release').textContent = template.release.sourceRef;
  status('Identity prepared. Download and confirm your initial backup to enable building.');
  $('#initial-backup').addEventListener('click', () => {
    saveFile('initial', initialBackup, `${identity.applicationId}-initial.web-backup.json`);
    initialDownloaded = true;
    $('#initial-saved').disabled = false;
  });
  $('#initial-saved').addEventListener('change', () => { $('#build').disabled = !(initialDownloaded && $('#initial-saved').checked); });
  $('#build').addEventListener('click', build);
  $('#cancel-build').addEventListener('click', () => operation?.abort());
}
async function build() {
  if (operation || !identity || !initialDownloaded || !$('#initial-saved').checked) return;
  const controller = new AbortController();
  operation = controller;
  $('#error').hidden = true;
  $('#build').disabled = true;
  $('#initial-backup').disabled = true;
  $('#initial-saved').disabled = true;
  $('#cancel-build').hidden = false;
  let phase = 'reserve';
  try {
    status('Reserving the next version number in this browser…');
    const reservation = await reserveVersion(identity, lastPlayVersion);
    controller.signal.throwIfAborted();
    phase = 'build';
    status(`Building version ${reservation.versionCode} locally. Keep this tab open…`);
    const output = await runBuildInWorker({ templateBytes: template.bytes, descriptor: template.descriptor,
      identity, versionCode: reservation.versionCode, backupPassword, carplay }, { signal: controller.signal });
    controller.signal.throwIfAborted();
    result = output;
    identity = output.identity;
    backupPassword = '';
    clearCarplay();
    initialBackup = null;
    template.bytes = null;
    revokeDownloads();
    showDownloads();
  } catch (cause) {
    if (operation !== controller) return;
    if (cause.name === 'AbortError') status('Build cancelled. Any version reservation is kept. Your saved initial backup still protects this identity. You can retry.');
    else {
      status('No AAB available. Any version reservation is kept.');
      error(phase === 'reserve'
        ? 'Could not reserve a version safely. Allow browser storage and locking in a current desktop browser. If the version record is damaged or conflicts with your backup, stop and reconcile it; do not clear it to force a build.'
        : 'The local build failed or timed out. No AAB was released. Keep your backup and retry. If it fails again, the template may not be supported by this builder.');
    }
    if (!controller.signal.aborted || operation === controller) {
      $('#build').disabled = false;
      $('#initial-backup').disabled = false;
      $('#initial-saved').disabled = false;
      $('#cancel-build').hidden = true;
    }
  } finally { if (operation === controller) operation = null; }
}
function showDownloads() {
  finalDownloaded = false;
  showStep(4, `<section aria-labelledby="downloads-title">
    <h2 id="downloads-title" tabindex="-1">Save your build files</h2>
    <p>Your AAB has been built and signed locally. Save the updated web backup before downloading it.</p>
    ${includedCarplay ? '<p class="notice">This AAB contains your selected CarPlay files. They are extractable and are not in the web backup. Supply them again for future updates that need them.</p>' : ''}
    <dl><dt>Application ID</dt><dd id="built-id" class="identifier"></dd><dt>Version code</dt><dd id="built-version"></dd></dl>
    <h3>1. Keep the updated web backup</h3>
    <p>Use this backup for your next update. It keeps the same signing identity and records this version. Keep your password separately.</p>
    <button id="final-backup">Download updated web backup</button>
    <label class="choice"><input id="final-saved" type="checkbox" disabled> <span>I saved the updated web backup and know its password.</span></label>
    <h3>2. Download your Android App Bundle</h3>
    <p>An AAB is for upload to Google Play; it is not directly installable on your car. A successful local build does not establish Play acceptance or vehicle compatibility.</p>
    <button id="aab" class="primary" disabled>Download AAB</button>
  </section>`);
  $('#built-id').textContent = identity.applicationId;
  $('#built-version').textContent = String(identity.versionCodeHighWaterMark);
  status('Build finished. The AAB download is locked until you save and confirm the updated backup.');
  $('#final-backup').addEventListener('click', () => {
    saveFile('backup', result.backupBytes, `${identity.applicationId}-v${identity.versionCodeHighWaterMark}.web-backup.json`);
    finalDownloaded = true;
    $('#final-saved').disabled = false;
  });
  $('#final-saved').addEventListener('change', () => { $('#aab').disabled = !(finalDownloaded && $('#final-saved').checked); });
  $('#aab').addEventListener('click', () => {
    if (!finalDownloaded || !$('#final-saved').checked) return;
    saveFile('aab', result.aabBytes, `${identity.applicationId}-v${identity.versionCodeHighWaterMark}.aab`);
    status('AAB download requested. Keep the updated backup and password for your next update.');
  });
}
$('#continue').addEventListener('click', () => showIdentity());
// Best-effort cleanup: JS/GC cannot promise secure erasure of immutable strings.
function discardSession() {
  operation?.abort();
  operation = null;
  revokeDownloads();
  clearCarplay();
  initialBackup?.fill(0);
  result?.aabBytes.fill(0);
  result?.backupBytes.fill(0);
  template?.bytes?.fill(0);
  identity = null;
  backupPassword = '';
  initialBackup = null;
  result = null;
  template = null;
  initialDownloaded = false;
  finalDownloaded = false;
  includedCarplay = false;
  lastPlayVersion = 0;
  document.querySelectorAll('#wizard input').forEach(input => { input.value = ''; });
  showStep(1, '');
  $('#continue').disabled = true;
  $('#release-details').hidden = true;
  $('#release-tag').textContent = '';
  $('#release-commit').textContent = '';
  $('#check').disabled = false;
  $('#cancel').hidden = true;
}
$('#restart').addEventListener('click', () => {
  if (!confirm('Clear this tab’s private data and downloads? Save your web backup first. This does not remove version reservations or change your app identity. To update later, restore the web backup.')) return;
  discardSession();
  checkLatest();
  $('#release-title').focus();
});
window.addEventListener('pagehide', discardSession);
window.addEventListener('pageshow', event => {
  if (event.persisted) { discardSession(); status('Private data cleared when this page was left. Check latest again, then restore your web backup to continue an existing app.'); }
});
function status(message) { $('#status').textContent = message; }
function error(message) { $('#error').textContent = message; $('#error').hidden = false; $('#error').focus(); }
async function checkLatest() {
  if (!globalThis.isSecureContext || !globalThis.crypto?.subtle || typeof Worker !== 'function' || !navigator.locks?.request) {
    status('This browser cannot safely run the builder.');
    error('Browser features unavailable. Use a current desktop Chrome or Edge with Web Crypto, workers and browser locking, over HTTPS. Private files have not been requested.');
    $('#continue').disabled = true;
    return;
  }
  operation?.abort();
  const controller = new AbortController();
  operation = controller;
  template = null;
  $('#continue').disabled = true;
  $('#release-details').hidden = true;
  $('#error').hidden = true;
  $('#check').disabled = true;
  $('#cancel').hidden = false;
  status('Checking latest public release…');
  try {
    const result = await loadLatestTemplate({ signal: controller.signal, onProgress: ({ stage }) => {
      if (operation !== controller) return;
      status(stage === 'template' ? 'Downloading and checking the latest template…' : 'Checking public release metadata…');
    } });
    if (controller.signal.aborted) return;
    template = result;
    $('#release-tag').textContent = result.release.sourceRef;
    $('#release-commit').textContent = result.release.sourceCommit;
    $('#release-details').hidden = false;
    $('#continue').disabled = false;
    status('Latest template downloaded. File size and checksums match the release metadata.');
  } catch (cause) {
    if (operation !== controller) return;
    if (cause.name === 'AbortError') status('Release check cancelled. Check latest again to continue.');
    else {
      status('No template loaded.');
      error('Latest template unavailable or could not be verified. Check your connection and try again. If this continues, the publisher must provide a supported latest template and matching mirror; older releases are not used.');
    }
  } finally {
    if (operation === controller) { operation = null; $('#check').disabled = false; $('#cancel').hidden = true; }
  }
}
$('#check').addEventListener('click', checkLatest);
$('#cancel').addEventListener('click', () => operation?.abort());
checkLatest();
