const usedWorkers = new WeakSet();
let sequence = 0;
/** One fresh, single-use module Worker per invocation, including concurrent calls.
 * Input is structured-cloned by postMessage, not transferred/detached. Returns
 * the pipeline's {aabBytes, identity, backupBytes}; identity contains private key
 * material and MUST NOT be logged. Only output AAB/encrypted backup buffers are
 * transferred. Progress is {phase: 'building'|'complete'}, not percentage.
 * Default timeout 120s, accepted range 1–300000ms. Abort/timeout calls terminate
 * synchronously; Chromium target teardown can lag promise rejection. Browser
 * scheduling can delay timer delivery (this is not a hard realtime SLA).
 * Caller owns durable identity/version reservations and must retain reservations
 * after failure/cancel. This runner neither persists nor creates identities.
 */
const failure = () => new Error('AAB worker build failed.');
export function runBuildInWorker(input, { signal, onProgress, timeoutMs = 120000,
  workerFactory = () => new Worker(new URL('../build.worker.mjs', import.meta.url), { type: 'module' }),
} = {}) {
  return new Promise((resolve, reject) => {
    const aborted = () => new DOMException('Build cancelled.', 'AbortError');
    if (signal?.aborted) { reject(aborted()); return; }
    if (!Number.isInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 300000) {
      reject(new Error('Worker timeout must be 1–300000 milliseconds.')); return;
    }
    let worker, timer, settled = false;
    const jobId = String(++sequence);
    const finish = (error, result) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      signal?.removeEventListener('abort', abort);
      for (const [type, listener] of [['message', message], ['error', crash], ['messageerror', crash]]) {
        try { worker?.removeEventListener(type, listener); } catch { /* Never forward worker errors. */ }
      }
      try { worker?.terminate(); } catch { /* Settlement must still complete. */ }
      if (error) reject(error); else resolve(result);
    };
    const abort = () => finish(aborted());
    const crash = event => { event.preventDefault?.(); finish(failure()); };
    const message = event => {
      if (settled) return;
      const data = event.data;
      if (!data || typeof data !== 'object' || typeof data.jobId !== 'string') { finish(failure()); return; }
      if (data.jobId !== jobId) return;
      if (data.type === 'progress' && ['building', 'complete'].includes(data.phase)) {
        // A consumer's UI failure must not leak keys or strand this worker.
        try { Promise.resolve(onProgress?.({ phase: data.phase })).catch(() => {}); } catch { /* UI only. */ }
        return;
      }
      const result = data.result;
      if (data.type === 'result' && result?.aabBytes instanceof Uint8Array && result.aabBytes.length > 0 &&
          result.backupBytes instanceof Uint8Array && result.backupBytes.length > 0 &&
          result.identity?.format === 'oal-web-upload-identity' && result.identity.formatVersion === 1 &&
          typeof result.identity.applicationId === 'string' && typeof result.identity.privateKeyPem === 'string' &&
          typeof result.identity.certificatePem === 'string' && Number.isInteger(result.identity.versionCodeHighWaterMark)) {
        finish(null, { aabBytes: result.aabBytes, identity: result.identity, backupBytes: result.backupBytes });
        return;
      }
      finish(failure());
    };
    try {
      const candidate = workerFactory();
      if (usedWorkers.has(candidate)) { reject(new Error('Worker factory must return a fresh worker.')); return; }
      worker = candidate;
      usedWorkers.add(worker);
      worker.addEventListener('message', message);
      worker.addEventListener('error', crash);
      worker.addEventListener('messageerror', crash);
      signal?.addEventListener('abort', abort, { once: true });
      if (signal?.aborted) { abort(); return; }
      timer = setTimeout(() => finish(new DOMException('Build timed out.', 'TimeoutError')), timeoutMs);
      worker.postMessage({ type: 'build', jobId, input });
    } catch { finish(failure()); }
  });
}
