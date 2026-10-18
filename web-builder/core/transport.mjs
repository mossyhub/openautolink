/**
 * Public browser transport only: no uploads, private inputs, cache or fallbacks.
 * GitHub API metadata is CORS-readable; GitHub release download URLs are not.
 * Therefore the publisher must separately build/populate a static, same-origin
 * /templates/<tag>/<name> mirror with the EXACT published descriptor/AAB bytes.
 * Mirror construction/deployment is a separate task, not performed here. Missing
 * latest assets intentionally produce an actionable unavailable error rather
 * than using an older release, direct GitHub download, APK or local file.
 *
 * GitHub repository ownership is the trust root, not the mirror. Require the
 * descriptor's published SHA-256 before parsing it; its authenticated AAB hash
 * is mandatory, and any GitHub AAB digest must also agree. These checks establish
 * byte provenance, not AAB structure, credential safety or live qualification.
 */
import { resolveLatestRelease, validateDescriptor, verifyTemplateBytes,
  MAX_DESCRIPTOR_SIZE } from './releases.mjs';
import { parseStrictJson } from './strict-json.mjs';

const DESCRIPTOR_NAME = 'openautolink-personalization.json';
export const MAX_METADATA_SIZE = 1024 * 1024;
export const LOAD_TIMEOUT_MS = 120000;
class TransportError extends Error {}

function validateOrigin(origin) {
  const fail = () => { throw new Error('A same-page HTTPS origin is required (loopback HTTP is for local tests only)'); };
  let url;
  try { url = new URL(origin); } catch { fail(); }
  if (typeof origin !== 'string' || url.origin !== origin || url.username || url.password
      || (url.protocol !== 'https:' && !(url.protocol === 'http:'
        && ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)))
      || (globalThis.location && globalThis.location.origin !== origin)) fail();
  return origin;
}

function cancelBody(body) {
  try { body?.cancel()?.catch(() => {}); } catch {}
}

// A separate listener per wait avoids accumulating Promise.race handlers on one
// never-settled abort promise for every streamed chunk. Dispose late fetch bodies
// at the actual handoff boundary, including cancellation between microtasks.
function waitFor(promise, signal, discard) {
  return new Promise((resolve, reject) => {
    const abort = () => reject(signal.reason);
    const cleanup = () => signal.removeEventListener('abort', abort);
    signal.addEventListener('abort', abort, { once: true });
    if (signal.aborted) { cleanup(); abort(); }
    Promise.resolve(promise).then((value) => {
      cleanup();
      if (signal.aborted) {
        discard?.(value);
        reject(signal.reason);
      } else resolve(value);
    }, (error) => { cleanup(); reject(error); });
  });
}

/**
 * Resolve latest on every call, then return { release, descriptor, bytes }.
 * origin defaults to the page origin and cannot override it in a browser;
 * explicit loopback HTTP origins support local tests only. HTTPS elsewhere.
 * fetchImpl is a test seam; production uses native fetch, credentials omitted.
 *
 * One 120s deadline covers all requests, stream reads and checksum waits.
 * Metadata is capped at 1 MiB; descriptor at 64 KiB; AAB at the validated exact
 * size (at most 512 MiB). No arrayBuffer/text/json body convenience methods.
 * onProgress receives frozen { stage, loaded, total } byte counters per request;
 * stage is metadata/descriptor/template, total may be null for metadata.
 * Download progress is NOT verification success: only resolution of this call
 * means all provenance checks passed. Observer errors are ignored, not exposed.
 * AbortError/TimeoutError have fixed safe messages; no remote text is returned
 * in errors. Cancellation cannot interrupt synchronous JS or WebCrypto work,
 * but it abandons asynchronous results and prevents later downloads/return.
 */
export async function loadLatestTemplate({ fetchImpl = globalThis.fetch, signal, onProgress,
  origin = globalThis.location?.origin } = {}) {
  origin = validateOrigin(origin);
  const controller = new AbortController();
  const abort = () => controller.abort(new DOMException('Public template load cancelled', 'AbortError'));
  if (signal?.aborted) abort();
  signal?.addEventListener('abort', abort, { once: true });
  const timer = setTimeout(() => controller.abort(new DOMException(
    'Public template load timed out; check connectivity and retry', 'TimeoutError')), LOAD_TIMEOUT_MS);
  try {
    controller.signal.throwIfAborted();
    return await loadResolvedTemplate(fetchImpl, origin, controller.signal, onProgress);
  } catch (error) {
    controller.signal.throwIfAborted();
    throw error;
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', abort);
  }
}

async function loadResolvedTemplate(fetchImpl, origin, signal, onProgress) {
  const progress = (stage, loaded, total) => {
    signal.throwIfAborted();
    try { Promise.resolve(onProgress?.(Object.freeze({ stage, loaded, total }))).catch(() => {}); } catch {}
    signal.throwIfAborted();
  };
  const read = async (url, limit = MAX_METADATA_SIZE, stage = 'metadata', exactSize) => {
    signal.throwIfAborted();
    const api = url.startsWith('https://api.github.com/');
    let response;
    let reader;
    let finished = false;
    try {
      response = await waitFor(fetchImpl(url, {
        method: 'GET', credentials: 'omit', cache: 'no-store', redirect: 'error',
        referrerPolicy: 'no-referrer', mode: api ? 'cors' : 'same-origin',
        signal,
        headers: { Accept: api ? 'application/vnd.github+json' : 'application/octet-stream' },
      }), signal, (late) => cancelBody(late?.body));
      signal.throwIfAborted();
      if (response.status !== 200 || response.redirected
          || ['opaque', 'opaqueredirect', 'error'].includes(response.type)
          || (response.url && response.url !== url)) {
        throw new TransportError('Public download or mirror unavailable; redirects are not allowed. Retry after the publisher updates the mirror');
      }
      const length = response.headers.get('Content-Length');
      if (length !== null && (!/^[0-9]{1,16}$/.test(length)
          || !Number.isSafeInteger(Number(length)) || Number(length) > limit
          || (exactSize !== undefined && Number(length) !== exactSize))) {
        throw new TransportError(`Public ${stage} size limit or Content-Length mismatch; retry`);
      }
      reader = response.body.getReader();
      // Fixed bounded destination: tiny chunks cannot inflate a chunk-list heap.
      const bytes = new Uint8Array(limit);
      let size = 0;
      const total = exactSize ?? (length === null ? null : Number(length));
      progress(stage, size, total);
      for (;;) {
        const { done, value } = await waitFor(reader.read(), signal);
        if (done) { finished = true; break; }
        if (!(value instanceof Uint8Array) || value.byteLength > limit - size) {
          throw new TransportError(`Public ${stage} size limit exceeded; retry`);
        }
        bytes.set(value, size);
        size += value.byteLength;
        progress(stage, size, total);
      }
      if (exactSize !== undefined && size !== exactSize) {
        throw new TransportError(`Public ${stage} size mismatch; retry`);
      }
      return bytes.subarray(0, size);
    } catch (error) {
      signal.throwIfAborted();
      if (error instanceof TransportError) throw error;
      throw new TransportError(`Public ${stage} download failed; check connectivity or mirror availability and retry`);
    } finally {
      if (!finished) {
        // Do not await cancel(): an adapter's cancellation may itself hang.
        try { (reader ? reader.cancel() : response?.body?.cancel())?.catch(() => {}); } catch {}
      }
      reader?.releaseLock();
    }
  };
  const release = await resolveLatestRelease(async (url) => parseStrictJson(await read(url), { maxBytes: MAX_METADATA_SIZE }));
  if (!release.templateAvailable) {
    throw new Error('The latest release template is unavailable. Retry after the publisher provides a supported template; older releases are not used');
  }
  const record = release.assets[DESCRIPTOR_NAME];
  if (record.size > MAX_DESCRIPTOR_SIZE || record.digest == null) {
    throw new Error('Personalization descriptor size or SHA-256 checksum is unavailable or unsupported; contact the publisher');
  }
  const base = `${origin}/templates/${release.sourceRef}/`;
  const raw = await read(base + DESCRIPTOR_NAME, record.size, 'descriptor', record.size);

  const digest = Array.from(new Uint8Array(await waitFor(globalThis.crypto.subtle.digest('SHA-256', raw), signal)),
    (byte) => byte.toString(16).padStart(2, '0')).join('');
  if (`sha256:${digest}` !== record.digest) throw new Error('Personalization descriptor checksum mismatch; retry');
  let parsed;
  try { parsed = parseStrictJson(raw, { maxBytes: MAX_DESCRIPTOR_SIZE }); }
  catch { throw new TransportError('Malformed personalization descriptor JSON; retry or contact the publisher'); }
  const descriptor = validateDescriptor(parsed, release);
  const bytes = await waitFor(verifyTemplateBytes(await read(base + descriptor.templateAsset.name,
    descriptor.templateAsset.size, 'template', descriptor.templateAsset.size), descriptor), signal);
  return { release, descriptor, bytes };
}
