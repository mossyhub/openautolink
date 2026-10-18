import { buildPersonalizedAab } from './core/pipeline.mjs';

/** Single-use dedicated worker. Never log requests, identities, or exceptions.
 * Exported installer permits deterministic transport tests without substituting
 * for the real browser qualification. Cancellation is parent-side terminate().
 */
export function installBuildWorker(scope, build = buildPersonalizedAab) {
  let used = false;
  scope.addEventListener('message', async event => {
    const request = event.data;
    const jobId = typeof request?.jobId === 'string' && /^[A-Za-z0-9-]{1,80}$/.test(request.jobId) ? request.jobId : '';
    const fail = () => {
      try { scope.postMessage({ type: 'error', jobId, code: 'BUILD_FAILED' }); } catch { /* Parent timeout/crash handles transport loss. */ }
    };
    if (used || !jobId || request?.type !== 'build' || !request.input || typeof request.input !== 'object') { fail(); return; }
    used = true;
    try {
      scope.postMessage({ type: 'progress', jobId, phase: 'building' });
      const { aabBytes, identity, backupBytes } = await build(request.input);
      scope.postMessage({ type: 'progress', jobId, phase: 'complete' });
      scope.postMessage({ type: 'result', jobId, result: { aabBytes, identity, backupBytes } },
        [aabBytes.buffer, backupBytes.buffer]);
    } catch { fail(); }
  });
}

if (typeof WorkerGlobalScope !== 'undefined' && globalThis instanceof WorkerGlobalScope) {
  installBuildWorker(globalThis);
}
