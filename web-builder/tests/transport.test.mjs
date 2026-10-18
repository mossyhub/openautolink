import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';

// Synthetic transport fixtures only; these bytes are NOT a live or valid AAB.
const API = 'https://api.github.com/repos/mossyhub/openautolink';
const WEB = 'https://github.com/mossyhub/openautolink';
const ORIGIN = 'https://builder.example';
const TAG = 'v0.1.528';
const COMMIT = 'a'.repeat(40);
const NAME = `openautolink-template-${TAG}.aab`;
const DESC = 'openautolink-personalization.json';
const encode = (text) => new TextEncoder().encode(text);
const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
const mirror = (name) => `${ORIGIN}/templates/${TAG}/${name}`;

async function transport() {
  const module = await import('../core/transport.mjs').catch((error) => {
    if (error.code === 'ERR_MODULE_NOT_FOUND') return {};
    throw error;
  });
  assert.equal(typeof module.loadLatestTemplate, 'function');
  return module;
}

function fixture() {
  const bytes = encode('synthetic aab bytes');
  const descriptor = {
    schemaVersion: 1, formatVersion: 1, releaseTag: TAG, sourceCommit: COMMIT,
    templateApplicationId: 'com.openautolink.template', transformContract: 'oal-identity-v1',
    templateAsset: { name: NAME, size: bytes.length, sha256: hash(bytes) },
  };
  const raw = encode(JSON.stringify(descriptor));
  const asset = (name, data) => ({ name, size: data.length, digest: `sha256:${hash(data)}`,
    browser_download_url: `${WEB}/releases/download/${TAG}/${name}` });
  const metadata = { tag_name: TAG, draft: false, prerelease: false,
    html_url: `${WEB}/releases/tag/${TAG}`, assets: [asset(DESC, raw), asset(NAME, bytes)] };
  const ref = { ref: `refs/tags/${TAG}`, object: { type: 'commit', sha: COMMIT } };
  const routes = new Map([
    [`${API}/releases/latest`, () => new Response(JSON.stringify(metadata))],
    [`${API}/git/ref/tags/${TAG}`, () => new Response(JSON.stringify(ref))],
    [mirror(DESC), () => new Response(raw)],
    [mirror(NAME), () => new Response(bytes)],
  ]);
  const calls = [];
  const fetchImpl = async (url, options) => {
    calls.push({ url, options });
    assert.ok(routes.has(url), `Unexpected fixture request: ${url}`);
    return routes.get(url)(options);
  };
  return { bytes, descriptor, raw, metadata, ref, routes, calls, fetchImpl };
}

const load = async (f, options = {}) => (await transport()).loadLatestTemplate({
  fetchImpl: f.fetchImpl, origin: ORIGIN, ...options,
});

test('requests use credential-free GET, no cache, no referrer and no redirects', async () => {
  const f = fixture();
  await load(f);
  for (const { url, options } of f.calls) {
    assert.equal(options?.method, 'GET');
    assert.equal(options.credentials, 'omit');
    assert.equal(options.cache, 'no-store');
    assert.equal(options.redirect, 'error');
    assert.equal(options.referrerPolicy, 'no-referrer');
    assert.equal(options.mode, url.startsWith(API) ? 'cors' : 'same-origin');
    assert.deepEqual(Object.keys(options.headers), ['Accept']);
  }
});

test('rejects insecure, credentialed, non-origin and foreign-page origins before fetching', async () => {
  for (const origin of ['http://builder.example', 'https://user:secret@builder.example',
    'file:///tmp', 'https://builder.example/path', `${ORIGIN}?secret`, `${ORIGIN}#x`,
    'http://localhost.evil', 'http://127.1', 'http://192.168.1.2', '', null]) {
    const f = fixture();
    await assert.rejects(load(f, { origin }), /origin|HTTPS/i);
    assert.equal(f.calls.length, 0);
  }
  const previous = Object.getOwnPropertyDescriptor(globalThis, 'location');
  Object.defineProperty(globalThis, 'location', { configurable: true, value: { origin: ORIGIN } });
  try {
    const f = fixture();
    await assert.rejects(load(f, { origin: 'https://other.example' }), /origin/i);
    assert.equal(f.calls.length, 0);
    const good = fixture();
    await (await transport()).loadLatestTemplate({ fetchImpl: good.fetchImpl });
  } finally {
    if (previous) Object.defineProperty(globalThis, 'location', previous);
    else delete globalThis.location;
  }
});

test('allows explicit loopback origins for local fixture tests', async () => {
  for (const origin of ['http://localhost:8123', 'http://127.0.0.1:8123', 'http://[::1]:8123']) {
    const f = fixture();
    for (const name of [DESC, NAME]) {
      f.routes.set(`${origin}/templates/${TAG}/${name}`, f.routes.get(mirror(name)));
    }
    assert.deepEqual((await load(f, { origin })).bytes, f.bytes);
  }
});

test('missing latest template stops with useful unavailable error and no fallback', async () => {
  const f = fixture();
  f.metadata.assets = [];
  await assert.rejects(load(f), /latest release.*unavailable.*publisher/i);
  assert.equal(f.calls.length, 2);
});

test('descriptor provenance requires a bounded size and GitHub SHA-256 before download', async () => {
  for (const change of [{ size: 65537 }, { digest: null }, { digest: undefined }]) {
    const f = fixture();
    Object.assign(f.metadata.assets[0], change);
    await assert.rejects(load(f), /descriptor.*(size|checksum|SHA-256)/i);
    assert.equal(f.calls.length, 2);
  }
});

test('checks raw descriptor length and digest before parsing remote JSON', async () => {
  for (const kind of ['size', 'checksum']) {
    const f = fixture();
    // Invalid JSON would throw a parse error if raw-byte verification were skipped.
    const raw = encode('!'.repeat(f.raw.length + (kind === 'size' ? -1 : 0)));
    f.routes.set(mirror(DESC), () => new Response(raw));
    await assert.rejects(load(f), new RegExp(`descriptor.*${kind}`, 'i'));
    assert.equal(f.calls.length, 3);
  }
});

test('rejects redirect, opaque, partial and failed responses before reading their bodies', async () => {
  for (const url of [`${API}/releases/latest`, mirror(DESC), mirror(NAME)]) {
    for (const mutation of [{ status: 302 }, { status: 404 }, { status: 206 },
      { redirected: true }, { type: 'opaque' }, { type: 'opaqueredirect' },
      { url: 'https://evil.example/secret' }, { url: `${url}?secret` }]) {
      const f = fixture();
      let cancelled = false;
      let reads = 0;
      f.routes.set(url, () => ({ status: 200, type: 'basic', url, redirected: false,
        headers: new Headers(), body: new ReadableStream({
          pull(controller) { reads++; controller.enqueue(encode('secret')); controller.close(); },
          cancel() { cancelled = true; },
        }, { highWaterMark: 0 }), ...mutation }));
      await assert.rejects(load(f), /metadata|download|mirror/i);
      assert.equal(reads, 0);
      assert.equal(cancelled, true);
      assert.equal(f.calls.at(-1).url, url);
    }
  }
});

test('caps stream bytes without Content-Length and cancels before accepting oversized data', async () => {
  const { MAX_METADATA_SIZE } = await transport();
  assert.equal(MAX_METADATA_SIZE, 1024 * 1024);
  for (const [url, cap] of [[`${API}/releases/latest`, MAX_METADATA_SIZE],
    [mirror(DESC), fixture().raw.length], [mirror(NAME), fixture().bytes.length]]) {
    const f = fixture();
    let cancelled = false;
    f.routes.set(url, () => new Response(new ReadableStream({
      start(controller) { controller.enqueue(new Uint8Array(cap + 1)); },
      cancel() { cancelled = true; },
    })));
    await assert.rejects(load(f), /metadata|size|limit/i);
    assert.equal(cancelled, true);
    assert.equal(f.calls.at(-1).url, url);
  }
});

test('rejects invalid Content-Length before consuming the descriptor stream', async () => {
  for (const size of ['99999999999999999999', '-1', '1.5', 'NaN', '0', '1', '65537']) {
    const f = fixture();
    let reads = 0;
    f.routes.set(mirror(DESC), () => new Response(new ReadableStream({
      pull(controller) { reads++; controller.enqueue(f.raw); controller.close(); },
    }, { highWaterMark: 0 }), { headers: { 'Content-Length': size } }));
    await assert.rejects(load(f), /size|length|download/i);
    assert.equal(reads, 0);
  }
});

test('pre-aborted calls make no requests and do not expose abort reasons', async () => {
  const f = fixture();
  const controller = new AbortController();
  controller.abort(new Error('private-abort-reason'));
  await assert.rejects(load(f, { signal: controller.signal }), (error) => {
    assert.equal(error.name, 'AbortError');
    assert.doesNotMatch(error.message, /private-abort-reason/);
    return true;
  });
  assert.equal(f.calls.length, 0);
});

test('cancels pending fetch even if the adapter ignores AbortSignal, then allows retry', async () => {
  const f = fixture();
  const controller = new AbortController();
  const started = Promise.withResolvers();
  const response = Promise.withResolvers();
  let requestSignal;
  f.routes.set(mirror(DESC), (options) => {
    requestSignal = options.signal;
    started.resolve();
    return response.promise;
  });
  const pending = load(f, { signal: controller.signal });
  await started.promise;
  assert.ok(requestSignal instanceof AbortSignal);
  const rejected = assert.rejects(pending, { name: 'AbortError' });
  controller.abort('private-abort-reason');
  await rejected;
  assert.equal(requestSignal.aborted, true);
  let cancelled = false;
  response.resolve(new Response(new ReadableStream({ cancel() { cancelled = true; } })));
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(cancelled, true);
  assert.equal(f.calls.length, 3);
  assert.deepEqual((await load(fixture())).bytes, f.bytes);
});

test('whole-load deadline aborts stalled headers or streams at every stage', async (t) => {
  const { LOAD_TIMEOUT_MS } = await transport();
  assert.equal(LOAD_TIMEOUT_MS, 120000);
  const timers = [];
  t.mock.method(globalThis, 'setTimeout', (callback, ms) => {
    const timer = { callback, ms, cleared: false }; timers.push(timer); return timer;
  });
  t.mock.method(globalThis, 'clearTimeout', (timer) => { timer.cleared = true; });
  for (const url of [`${API}/releases/latest`, mirror(DESC), mirror(NAME)]) {
    for (const at of ['headers', 'body']) {
      const f = fixture();
      const started = Promise.withResolvers();
      let cancelled = false;
      let requestSignal;
      f.routes.set(url, (options) => {
        requestSignal = options.signal;
        if (at === 'headers') { started.resolve(); return new Promise(() => {}); }
        return new Response(new ReadableStream({
          pull() { started.resolve(); return new Promise(() => {}); },
          cancel() { cancelled = true; return new Promise(() => {}); },
        }, { highWaterMark: 0 }));
      });
      const pending = load(f);
      await started.promise;
      const timer = timers.at(-1);
      assert.equal(timer.ms, LOAD_TIMEOUT_MS);
      const rejected = assert.rejects(pending, { name: 'TimeoutError' });
      timer.callback();
      await rejected;
      assert.equal(requestSignal.aborted, true);
      assert.equal(timer.cleared, true);
      if (at === 'body') assert.equal(cancelled, true);
      assert.equal(f.calls.at(-1).url, url);
    }
  }
  const good = fixture();
  await load(good);
  assert.equal(timers.at(-1).cleared, true);
  assert.equal(good.calls[0].options.signal.aborted, false);
  assert.equal(timers.length, 7); // one timer per load, never reset per chunk/request
});

test('never exposes remote fetch, stream, HTTP or JSON error text', async () => {
  for (const url of [`${API}/releases/latest`, mirror(DESC), mirror(NAME)]) {
    for (const at of ['fetch', 'stream', 'http']) {
      const f = fixture();
      f.routes.set(url, () => {
        if (at === 'fetch') throw new Error('secret-remote-token');
        if (at === 'http') return new Response('secret-remote-token', { status: 503 });
        return new Response(new ReadableStream({
          start(controller) { controller.error(new Error('secret-remote-token')); },
        }));
      });
      await assert.rejects(load(f), (error) => {
        assert.match(error.message, /metadata|download|mirror/i);
        assert.doesNotMatch(error.message, /secret-remote-token/);
        assert.equal(error.cause, undefined);
        return true;
      });
    }
  }
  const f = fixture();
  const raw = encode('{"secret-remote-token":UNTRUSTED}');
  f.metadata.assets[0].size = raw.length;
  f.metadata.assets[0].digest = `sha256:${hash(raw)}`;
  f.routes.set(mirror(DESC), () => new Response(raw));
  await assert.rejects(load(f), (error) => {
    assert.match(error.message, /descriptor.*JSON/i);
    assert.doesNotMatch(error.message, /secret-remote-token|UNTRUSTED/);
    assert.equal(error.cause, undefined);
    return true;
  });
});

test('strict descriptor parsing rejects duplicate fields, malformed UTF-8 and excessive depth', async () => {
  for (const raw of [encode('{"schemaVersion":1,"schemaVersion":1}'),
    encode('{"templateAsset":{"name":"x","name":"x"}}'),
    new Uint8Array([0xc3, 0x28]), encode('['.repeat(34) + '0' + ']'.repeat(34))]) {
    const f = fixture();
    f.metadata.assets[0].size = raw.length;
    f.metadata.assets[0].digest = `sha256:${hash(raw)}`;
    f.routes.set(mirror(DESC), () => new Response(raw));
    await assert.rejects(load(f), /descriptor.*JSON/i);
    assert.equal(f.calls.length, 3);
  }
});

test('reports byte progress without exposing remote payloads or trusting observer mutations', async () => {
  const f = fixture();
  const events = [];
  f.routes.set(mirror(NAME), () => new Response(new ReadableStream({
    start(controller) {
      controller.enqueue(f.bytes.subarray(0, 3));
      controller.enqueue(f.bytes.subarray(3));
      controller.close();
    },
  }), { headers: { 'Content-Length': String(f.bytes.length) } }));
  const result = await load(f, { onProgress(event) { events.push(event); } });
  assert.deepEqual(events.filter((e) => e.stage === 'template'), [
    { stage: 'template', loaded: 0, total: f.bytes.length },
    { stage: 'template', loaded: 3, total: f.bytes.length },
    { stage: 'template', loaded: f.bytes.length, total: f.bytes.length },
  ]);
  assert.ok(events.some((e) => e.stage === 'metadata'));
  assert.ok(events.some((e) => e.stage === 'descriptor' && e.loaded === f.raw.length));
  assert.deepEqual(result.bytes, f.bytes);
  for (const onProgress of [() => { throw Error('private-observer'); },
    () => Promise.reject(Error('private-observer')), (event) => { event.loaded = -1; }]) {
    assert.deepEqual((await load(fixture(), { onProgress })).bytes, f.bytes);
  }
});

test('abort from progress cancels stream and prevents subsequent requests', async () => {
  const f = fixture();
  const controller = new AbortController();
  let cancelled = false;
  f.routes.set(mirror(DESC), () => new Response(new ReadableStream({
    start(stream) { stream.enqueue(f.raw.subarray(0, 1)); },
    cancel() { cancelled = true; },
  })));
  let callbacks = 0;
  await assert.rejects(load(f, { signal: controller.signal, onProgress(event) {
    callbacks++;
    if (event.stage === 'descriptor' && event.loaded > 0) controller.abort('private-reason');
  } }), { name: 'AbortError' });
  assert.ok(callbacks > 0);
  assert.equal(cancelled, true);
  assert.equal(f.calls.length, 3);
});

test('cancellation around response delivery always disposes the response body', async () => {
  for (let depth = 0; depth < 12; depth++) {
    const f = fixture();
    const controller = new AbortController();
    let cancelled = false;
    f.routes.set(mirror(DESC), () => {
      const schedule = (left) => queueMicrotask(() => {
        if (left) schedule(left - 1);
        else controller.abort();
      });
      schedule(depth);
      return new Response(new ReadableStream({ cancel() { cancelled = true; } }));
    });
    await assert.rejects(load(f, { signal: controller.signal }), { name: 'AbortError' });
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(cancelled, true, `response handoff microtask ${depth}`);
    assert.equal(f.calls.length, 3);
  }
});

test('rejects stale descriptor provenance and metadata disagreement before the AAB request', async () => {
  for (const mutate of [
    (d) => { d.releaseTag = 'v0.1.527'; },
    (d) => { d.sourceCommit = 'b'.repeat(40); },
    (d) => { d.templateAsset.sha256 = 'f'.repeat(64); },
    (d) => { d.templateAsset.name = '../private.aab'; },
    (d, f) => { d.templateAsset.size = f.metadata.assets[1].size = 512 * 1024 ** 2 + 1; },
  ]) {
    const f = fixture();
    mutate(f.descriptor, f);
    const raw = encode(JSON.stringify(f.descriptor));
    f.metadata.assets[0].size = raw.length;
    f.metadata.assets[0].digest = `sha256:${hash(raw)}`;
    f.routes.set(mirror(DESC), () => new Response(raw));
    await assert.rejects(load(f), /descriptor|checksum/i);
    assert.equal(f.calls.length, 3);
  }
});

test('verifies actual AAB length and SHA-256 even if the mirror claims a valid length', async () => {
  for (const kind of ['short', 'long', 'corrupt']) {
    const f = fixture();
    const data = kind === 'short' ? f.bytes.subarray(1)
      : kind === 'long' ? new Uint8Array(f.bytes.length + 1) : new Uint8Array(f.bytes.length);
    f.routes.set(mirror(NAME), () => new Response(data, {
      headers: { 'Content-Length': String(f.bytes.length) },
    }));
    await assert.rejects(load(f), /size|checksum/i);
    assert.equal(f.calls.length, 4);
  }
});

test('re-resolves latest on every load and never falls back to prior successful bytes', async () => {
  const f = fixture();
  await load(f);
  f.metadata.assets = [];
  await assert.rejects(load(f), /latest release.*unavailable/i);
  assert.equal(f.calls.length, 6);
  f.routes.set(`${API}/releases/latest`, () => { throw Error('offline'); });
  await assert.rejects(load(f), /connectivity|rate limit/i);
  assert.equal(f.calls.length, 7);
});

test('accepts the descriptor byte limit and larger bounded metadata without unbounded body helpers', async () => {
  const f = fixture();
  const raw = encode(JSON.stringify(f.descriptor).padEnd(65536));
  f.metadata.assets[0].size = raw.length;
  f.metadata.assets[0].digest = `sha256:${hash(raw)}`;
  f.routes.set(mirror(DESC), () => new Response(raw));
  f.routes.set(`${API}/releases/latest`, () => new Response(JSON.stringify(f.metadata).padEnd(1024 * 1024)));
  for (const [url, factory] of f.routes) f.routes.set(url, () => {
    const response = factory();
    for (const method of ['arrayBuffer', 'text', 'json', 'blob']) {
      response[method] = () => { assert.fail(`Unbounded body helper ${method}`); };
    }
    return response;
  });
  assert.deepEqual((await load(f)).bytes, f.bytes);
});

test('does not consume a response without a stream or non-byte stream chunks', async () => {
  for (const body of [null, new ReadableStream({
    start(controller) { controller.enqueue('private-stream-text'); controller.close(); },
  })]) {
    const f = fixture();
    f.routes.set(mirror(DESC), () => ({ status: 200, url: mirror(DESC), type: 'basic',
      redirected: false, headers: new Headers(), body,
      arrayBuffer() { assert.fail('Must not fall back to unbounded body'); },
    }));
    await assert.rejects(load(f), /download|size|limit/i);
    assert.equal(f.calls.length, 3);
  }
});

test('strictly parses live metadata and peels annotated tag commits through the fixed API', async () => {
  const f = fixture();
  const tagSha = 'c'.repeat(40);
  f.ref.object = { type: 'tag', sha: tagSha, url: 'https://evil.example/ignored' };
  f.routes.set(`${API}/git/tags/${tagSha}`, () => new Response(JSON.stringify({
    tag: TAG, sha: tagSha, object: { type: 'commit', sha: COMMIT },
  })));
  assert.equal((await load(f)).release.sourceCommit, COMMIT);
  assert.equal(f.calls.length, 5);
  const duplicate = fixture();
  duplicate.routes.set(`${API}/releases/latest`, () => new Response(
    JSON.stringify(duplicate.metadata).replace('{', '{"tag_name":"v0.1.1",')));
  await assert.rejects(load(duplicate), /metadata/i);
  assert.equal(duplicate.calls.length, 1);
});

test('loads live-resolved fixture provenance and verified same-origin bytes', async () => {
  const f = fixture();
  const result = await load(f);
  assert.equal(result.release.sourceRef, TAG);
  assert.equal(result.release.sourceCommit, COMMIT);
  assert.deepEqual(result.descriptor, { ...f.descriptor,
    url: `${WEB}/releases/download/${TAG}/${NAME}` });
  assert.deepEqual(result.bytes, f.bytes);
  assert.notEqual(result.bytes, f.bytes);
  assert.deepEqual(f.calls.map(({ url }) => url), [
    `${API}/releases/latest`, `${API}/git/ref/tags/${TAG}`, mirror(DESC), mirror(NAME),
  ]);
});
