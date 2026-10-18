import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';

const API = 'https://api.github.com/repos/mossyhub/openautolink';
const WEB = 'https://github.com/mossyhub/openautolink';
const TAG = 'v0.1.528';
const COMMIT = 'a'.repeat(40);
const NAME = `openautolink-template-${TAG}.aab`;
const DESCRIPTOR = 'openautolink-personalization.json';
const metadata = (assets = []) => ({
  tag_name: TAG, draft: false, prerelease: false,
  html_url: `${WEB}/releases/tag/${TAG}`, assets,
  target_commitish: 'main',
});
const tagRef = (object = { type: 'commit', sha: COMMIT }) => ({
  ref: `refs/tags/${TAG}`, object,
});
function fetcher(meta = metadata(), ref = tagRef(), extra = {}) {
  const calls = [];
  const responses = {
    [`${API}/releases/latest`]: meta,
    [`${API}/git/ref/tags/${TAG}`]: ref,
    ...extra,
  };
  return {
    calls,
    fetchJson: async (url) => {
      calls.push(url);
      assert.ok(Object.hasOwn(responses, url), `Unexpected fetch: ${url}`);
      return responses[url];
    },
  };
}

// Dynamic import makes the initial RED an assertion about the missing feature.
async function releases() {
  const module = await import('../core/releases.mjs').catch((error) => {
    if (error.code === 'ERR_MODULE_NOT_FOUND') return {};
    throw error;
  });
  assert.equal(typeof module.resolveLatestRelease, 'function');
  return module;
}

test('rejects nonstable or untrusted latest metadata without a fallback request', async () => {
  const { resolveLatestRelease } = await releases();
  const changes = [
    { draft: true }, { prerelease: true }, { draft: 0 }, { prerelease: null },
    { draft: undefined }, { tag_name: 'v0.1.528-rc1' }, { tag_name: 'v01.1.528' },
    { tag_name: '../main' }, { tag_name: 'v0.1.528\n' }, { tag_name: 528 },
    { tag_name: 'v1000000000.0.0' },
    { html_url: `${WEB}/releases/tag/v0.1.527` },
    { html_url: `${WEB}/releases/tag/${TAG}?token=secret` },
  ];
  for (const change of changes) {
    const { fetchJson, calls } = fetcher({ ...metadata(), ...change });
    await assert.rejects(resolveLatestRelease(fetchJson), /stable|Release URL/);
    assert.deepEqual(calls, [`${API}/releases/latest`]);
  }
});

test('metadata transport rejects malformed data and hides callback failure details', async () => {
  const { resolveLatestRelease } = await releases();
  for (const value of [null, [], 'secret', 1, true]) {
    await assert.rejects(resolveLatestRelease(async () => value), /Malformed release metadata/);
  }
  for (const failureAt of [1, 2]) {
    let calls = 0;
    await assert.rejects(resolveLatestRelease(async () => {
      if (++calls === failureAt) throw new Error('secret-token');
      return metadata();
    }), (error) => {
      assert.match(error.message, /connectivity|rate limit/);
      assert.doesNotMatch(error.message, /secret-token/);
      assert.equal(error.cause, undefined);
      return true;
    });
    assert.equal(calls, failureAt);
  }
});

test('source commit must come from the exact stable tag ref, never target_commitish', async () => {
  const { resolveLatestRelease } = await releases();
  for (const ref of [
    { ...tagRef(), ref: 'refs/tags/other' },
    tagRef({ type: 'commit', sha: 'main' }),
    tagRef({ type: 'commit', sha: 'A'.repeat(40) }),
    tagRef({ type: 'commit', sha: `${COMMIT}\n` }),
    tagRef({ type: 'tree', sha: COMMIT }),
    tagRef(null), tagRef([]), {},
  ]) {
    const { fetchJson, calls } = fetcher({ ...metadata(), target_commitish: COMMIT }, ref);
    await assert.rejects(resolveLatestRelease(fetchJson), /tag|commit/);
    assert.equal(calls.length, 2);
  }
  const latest = { ...metadata(), target_commitish: 'b'.repeat(40) };
  assert.equal((await resolveLatestRelease(fetcher(latest).fetchJson)).sourceCommit, COMMIT);
});

test('annotated tags peel at most eight immutable objects with cycle and identity checks', async () => {
  const { resolveLatestRelease } = await releases();
  function chain(count) {
    const shas = Array.from({ length: count }, (_, i) => (i + 1).toString(16).padStart(40, '0'));
    const extra = Object.fromEntries(shas.map((sha, i) => [`${API}/git/tags/${sha}`, {
      sha, tag: i === 0 ? TAG : 'nested-tag',
      object: i + 1 < count ? { type: 'tag', sha: shas[i + 1] } : { type: 'commit', sha: COMMIT },
    }]));
    return { shas, extra, ref: tagRef({ type: 'tag', sha: shas[0] }) };
  }
  for (const count of [1, 8]) {
    const { ref, extra } = chain(count);
    const { fetchJson, calls } = fetcher(metadata(), ref, extra);
    assert.equal((await resolveLatestRelease(fetchJson)).sourceCommit, COMMIT);
    assert.equal(calls.length, count + 2);
  }
  for (const mutation of ['sha', 'tag', 'cycle', 'too-deep', 'object', 'foreign-url']) {
    const { ref, extra, shas } = chain(mutation === 'too-deep' ? 9 : 1);
    const first = extra[`${API}/git/tags/${shas[0]}`];
    if (mutation === 'sha') first.sha = COMMIT;
    if (mutation === 'tag') first.tag = 'v0.1.527';
    if (mutation === 'cycle') first.object = ref.object;
    if (mutation === 'object') first.object = null;
    if (mutation === 'foreign-url') {
      first.object = { type: 'tag', sha: 'main', url: 'https://evil.example/secret' };
    }
    const { fetchJson, calls } = fetcher(metadata(), ref, extra);
    await assert.rejects(resolveLatestRelease(fetchJson), /tag|commit/);
    assert.ok(calls.length <= 10);
    assert.ok(calls.every((url) => url.startsWith(API)));
  }
});

function asset(name, size = 123, digest = null) {
  return { name, size, digest, browser_download_url: `${WEB}/releases/download/${TAG}/${name}` };
}

test('template availability requires both exact release assets, never an APK substitute', async () => {
  const { resolveLatestRelease } = await releases();
  for (const assets of [[], [asset('template.apk')], [asset(NAME)], [asset(DESCRIPTOR)], [asset(NAME), asset(DESCRIPTOR)]]) {
    const result = await resolveLatestRelease(fetcher(metadata(assets)).fetchJson);
    assert.equal(result.templateAvailable, assets.length === 2);
    assert.deepEqual(result.assets, Object.fromEntries(assets.map((item) => [item.name, item])));
  }
});

test('asset metadata is bounded and exact, including unused assets', async () => {
  const { resolveLatestRelease } = await releases();
  const invalid = [
    null, {}, [null], [[]], [asset(NAME), asset(NAME)],
    [asset('../template.aab')], [asset('x'.repeat(201))], [asset('a\n')],
    ...[true, 0, -1, 0.5, '3', NaN, Infinity, 4 * 1024 ** 3 + 1].map((size) => [asset(NAME, size)]),
    ...['sha256:bad', `sha256:${'a'.repeat(64)}\n`, `sha256:${'A'.repeat(64)}`, 123]
      .map((digest) => [asset(NAME, 3, digest)]),
    ...['https://evil.example/aab', `${asset(NAME).browser_download_url}?secret=x`,
      asset(NAME).browser_download_url.replace('https://', 'https://secret@'),
      asset(NAME).browser_download_url.replace(TAG, 'v0.1.527')]
      .map((browser_download_url) => [{ ...asset(NAME), browser_download_url }]),
    Array.from({ length: 101 }, (_, i) => asset(`${i}`)),
  ];
  for (const assets of invalid) {
    await assert.rejects(resolveLatestRelease(fetcher(metadata(assets)).fetchJson), /asset/i);
  }
  const records = Array.from({ length: 100 }, (_, i) => asset(`${i}`, 4 * 1024 ** 3));
  records[0] = { ...asset('constructor'), ignored: 'discard this', digest: undefined };
  const result = await resolveLatestRelease(fetcher(metadata(records)).fetchJson);
  assert.equal(Object.keys(result.assets).length, 100);
  assert.deepEqual(result.assets.constructor, asset('constructor'));
  assert.equal(result.assets['1'].size, 4 * 1024 ** 3);
});

const HASH = createHash('sha256').update('aab').digest('hex');
function descriptor() {
  return {
    schemaVersion: 1, formatVersion: 1, releaseTag: TAG, sourceCommit: COMMIT,
    templateApplicationId: 'com.openautolink.template', transformContract: 'oal-identity-v1',
    templateAsset: { name: NAME, size: 3, sha256: HASH },
  };
}
async function readyRelease() {
  const { resolveLatestRelease } = await releases();
  return resolveLatestRelease(fetcher(metadata([
    asset(NAME, 3, `sha256:${HASH}`), asset(DESCRIPTOR, JSON.stringify(descriptor()).length),
  ])).fetchJson);
}

test('descriptor returns an exact tag-pinned URL without mutating the input', async () => {
  const { validateDescriptor } = await releases();
  assert.equal(typeof validateDescriptor, 'function');
  const d = descriptor();
  const before = structuredClone(d);
  assert.deepEqual(validateDescriptor(d, await readyRelease()), {
    ...d, url: asset(NAME).browser_download_url,
  });
  assert.deepEqual(d, before);
});

test('descriptor requires the exact schema and identity contract for the resolved latest source', async () => {
  const { validateDescriptor } = await releases();
  const release = await readyRelease();
  const invalid = [null, [], {}, 'json',
    { ...descriptor(), extra: true },
    ...Object.keys(descriptor()).map((key) => {
      const d = descriptor(); delete d[key]; return d;
    }),
    ...[true, '1', 0, 2, null].flatMap((value) => [
      { ...descriptor(), schemaVersion: value }, { ...descriptor(), formatVersion: value },
    ]),
    { ...descriptor(), releaseTag: 'v0.1.527' },
    { ...descriptor(), sourceCommit: 'b'.repeat(40) },
    { ...descriptor(), templateApplicationId: 'com.openautolink.app' },
    { ...descriptor(), transformContract: 'unknown' },
    ...[null, [], {}, { ...descriptor().templateAsset, extra: 1 },
      { ...descriptor().templateAsset, name: 'template.apk' },
      ...[0, true, '3', 0.5, 512 * 1024 ** 2 + 1].map((size) => ({ ...descriptor().templateAsset, size })),
      ...['bad', `${HASH}\n`, HASH.toUpperCase()].map((sha256) => ({ ...descriptor().templateAsset, sha256 })),
    ].map((templateAsset) => ({ ...descriptor(), templateAsset })),
  ];
  for (const d of invalid) assert.throws(() => validateDescriptor(d, release), /descriptor/i);
  for (const digest of [null, undefined, `sha256:${HASH}`]) {
    release.assets[NAME].digest = digest;
    assert.equal(validateDescriptor(descriptor(), release).url, asset(NAME).browser_download_url);
  }
  const largest = descriptor();
  largest.templateAsset.size = 512 * 1024 ** 2;
  release.assets[NAME].size = largest.templateAsset.size;
  assert.equal(validateDescriptor(largest, release).templateAsset.size, 512 * 1024 ** 2);
});

test('descriptor cannot replace missing or mismatching GitHub template provenance', async () => {
  const { validateDescriptor } = await releases();
  for (const mutation of ['missing', 'size', 'digest']) {
    const release = await readyRelease();
    if (mutation === 'missing') delete release.assets[NAME];
    if (mutation === 'size') release.assets[NAME].size += 1;
    if (mutation === 'digest') release.assets[NAME].digest = `sha256:${'f'.repeat(64)}`;
    assert.throws(() => validateDescriptor(descriptor(), release), /missing|size\/checksum/i);
  }
});

test('template byte verification hashes the actual selected bytes using SHA-256', async () => {
  const { verifyTemplateBytes, validateDescriptor } = await releases();
  assert.equal(typeof verifyTemplateBytes, 'function');
  const d = validateDescriptor(descriptor(), await readyRelease());
  for (const input of [new TextEncoder().encode('aab'), new TextEncoder().encode('aab').buffer,
    new TextEncoder().encode('xaaby').subarray(1, 4)]) {
    assert.deepEqual(await verifyTemplateBytes(input, d), new TextEncoder().encode('aab'));
  }
});

test('template verification rejects truncation, corruption and invalid byte inputs', async () => {
  const { verifyTemplateBytes, validateDescriptor } = await releases();
  const d = validateDescriptor(descriptor(), await readyRelease());
  for (const input of [new TextEncoder().encode('bad'), new TextEncoder().encode('aabx'),
    new Uint8Array(), 'aab', [97, 97, 98], null, new Uint16Array([97, 97, 98])]) {
    await assert.rejects(verifyTemplateBytes(input, d), /checksum|size|bytes/i);
  }
  for (const size of [0, true, '3', 512 * 1024 ** 2 + 1]) {
    await assert.rejects(verifyTemplateBytes(new TextEncoder().encode('aab'), {
      ...d, templateAsset: { ...d.templateAsset, size },
    }), /descriptor/i);
  }
});

test('verification returns a private snapshot bound to the pre-await hash', async () => {
  const { verifyTemplateBytes, validateDescriptor } = await releases();
  const d = validateDescriptor(descriptor(), await readyRelease());
  const input = new TextEncoder().encode('aab');
  const pending = verifyTemplateBytes(input, d);
  input.fill(0);
  d.templateAsset.sha256 = '0'.repeat(64);
  assert.deepEqual(await pending, new TextEncoder().encode('aab'));
});

test('descriptor requires a published, bounded descriptor record from the resolved release', async () => {
  const { validateDescriptor, MAX_DESCRIPTOR_SIZE } = await releases();
  assert.equal(MAX_DESCRIPTOR_SIZE, 65536);
  for (const mutation of ['missing', 'large', 'zero', 'url', 'source']) {
    const release = await readyRelease();
    const d = descriptor();
    if (mutation === 'missing') delete release.assets[DESCRIPTOR];
    if (mutation === 'large') release.assets[DESCRIPTOR].size = 65537;
    if (mutation === 'zero') release.assets[DESCRIPTOR].size = 0;
    if (mutation === 'url') release.assets[DESCRIPTOR].browser_download_url = 'https://evil.example/descriptor';
    if (mutation === 'source') d.sourceCommit = release.sourceCommit = 'main';
    assert.throws(() => validateDescriptor(d, release), /descriptor|commit/i);
  }
  const release = await readyRelease();
  release.assets[DESCRIPTOR].size = 65536;
  assert.equal(validateDescriptor(descriptor(), release).url, asset(NAME).browser_download_url);
});

test('latest source resolves to its tag commit even without a template', async () => {
  const { resolveLatestRelease } = await releases();
  const { fetchJson, calls } = fetcher();
  assert.deepEqual(await resolveLatestRelease(fetchJson), {
    sourceRef: TAG, sourceCommit: COMMIT, versionName: '0.1.528',
    templateAvailable: false, assets: {},
  });
  assert.deepEqual(calls, [`${API}/releases/latest`, `${API}/git/ref/tags/${TAG}`]);
});
