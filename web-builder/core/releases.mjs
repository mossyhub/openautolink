/**
 * Public GitHub provenance, ported from desktop-builder/oal_builder/releases.py.
 * Hashes bind bytes, not a publisher signature: repository ownership is the root
 * of trust. No older-release, moving-branch, APK or source-compilation fallback.
 */
const API = 'https://api.github.com/repos/mossyhub/openautolink';
const WEB = 'https://github.com/mossyhub/openautolink';

// (?![\s\S]) is an absolute end assertion; JS $ also matches before a final newline.
function matches(pattern, value) {
  return typeof value === 'string' && pattern.test(value);
}

function validateTag(tag) {
  if (!matches(/^v(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})(?![\s\S])/, tag)) {
    throw new Error('Expected a stable release tag vMAJOR.MINOR.PATCH; retry or contact the publisher');
  }
  return tag;
}

function validateCommit(commit) {
  if (!matches(/^[0-9a-f]{40}(?![\s\S])/, commit)) {
    throw new Error('Release tag did not resolve to an immutable 40-hex source commit; retry or contact the publisher');
  }
  return commit;
}

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

async function fetchMetadata(fetchJson, url) {
  let value;
  try {
    value = await fetchJson(url);
  } catch {
    throw new Error('Could not read release metadata; check connectivity or GitHub rate limit and retry');
  }
  if (!isObject(value)) throw new Error('Malformed release metadata; retry or contact the publisher');
  return value;
}

function assetUrl(tag, name) {
  validateTag(tag);
  if (!matches(/^[A-Za-z0-9][A-Za-z0-9._-]{0,199}(?![\s\S])/, name)) {
    throw new Error('Unsafe release asset name; contact the publisher');
  }
  return `${WEB}/releases/download/${tag}/${name}`;
}

function validateAssets(records, tag) {
  if (!Array.isArray(records) || records.length > 100) {
    throw new Error('Missing or excessive release assets; retry or contact the publisher');
  }
  const assets = {};
  for (const record of records) {
    if (!isObject(record)) throw new Error('Malformed release asset; contact the publisher');
    const { name, size } = record;
    const url = assetUrl(tag, name);
    const digest = record.digest ?? null;
    if (Object.hasOwn(assets, name) || record.browser_download_url !== url) {
      throw new Error('Duplicate or untrusted release asset URL; contact the publisher');
    }
    if (!Number.isSafeInteger(size) || size <= 0 || size > 4 * 1024 ** 3) {
      throw new Error('Invalid release asset size; contact the publisher');
    }
    if (digest !== null && !matches(/^sha256:[0-9a-f]{64}(?![\s\S])/, digest)) {
      throw new Error('Invalid release asset checksum; contact the publisher');
    }
    assets[name] = { name, size, browser_download_url: url, digest };
  }
  return assets;
}

export const MAX_TEMPLATE_SIZE = 512 * 1024 ** 2;
export const MAX_DESCRIPTOR_SIZE = 65536;
const DESCRIPTOR_NAME = 'openautolink-personalization.json';
const DESCRIPTOR_ERROR = 'Unsupported or inconsistent personalization descriptor; update the builder or contact the publisher for a supported template';

function exactKeys(value, keys) {
  return isObject(value) && Object.keys(value).length === keys.length
    && keys.every((key) => Object.hasOwn(value, key));
}

function validateTemplateAsset(item, tag) {
  if (!exactKeys(item, ['name', 'size', 'sha256'])
      || item.name !== `openautolink-template-${tag}.aab`
      || !Number.isSafeInteger(item.size) || item.size <= 0 || item.size > MAX_TEMPLATE_SIZE
      || !matches(/^[0-9a-f]{64}(?![\s\S])/, item.sha256)) {
    throw new Error(DESCRIPTOR_ERROR);
  }
  return item;
}

/**
 * Validate a parsed schema-1 descriptor against resolveLatestRelease's result.
 * The transport MUST first bound raw descriptor bytes to MAX_DESCRIPTOR_SIZE,
 * match the descriptor asset's exact size and optional GitHub SHA-256 digest,
 * decode strict UTF-8 and reject duplicate JSON fields before calling this.
 * A parsed object alone cannot prove those raw-byte properties.
 * Only the reviewed oal-identity-v1 transformer may consume this contract.
 */
export function validateDescriptor(descriptor, release) {
  validateTag(release?.sourceRef);
  validateCommit(release?.sourceCommit);
  const record = release.assets?.[DESCRIPTOR_NAME];
  if (!isObject(record) || !Number.isSafeInteger(record.size)
      || record.size <= 0 || record.size > MAX_DESCRIPTOR_SIZE
      || record.browser_download_url !== assetUrl(release.sourceRef, DESCRIPTOR_NAME)) {
    throw new Error('Missing, oversized or untrusted personalization descriptor; retry after the publisher provides a supported template');
  }
  if (!exactKeys(descriptor, [
    'schemaVersion', 'formatVersion', 'releaseTag', 'sourceCommit',
    'templateApplicationId', 'transformContract', 'templateAsset',
  ]) || descriptor.schemaVersion !== 1 || descriptor.formatVersion !== 1
      || descriptor.releaseTag !== release.sourceRef || descriptor.sourceCommit !== release.sourceCommit
      || descriptor.templateApplicationId !== 'com.openautolink.template'
      || descriptor.transformContract !== 'oal-identity-v1') {
    throw new Error(DESCRIPTOR_ERROR);
  }
  const item = validateTemplateAsset(descriptor.templateAsset, release.sourceRef);
  const actual = Object.hasOwn(release.assets, item.name) ? release.assets[item.name] : null;
  if (!actual) {
    throw new Error('The latest release template AAB is missing. Retry after the publisher provides a supported template');
  }
  if (actual.size !== item.size || (actual.digest != null && actual.digest !== `sha256:${item.sha256}`)) {
    throw new Error('Template size/checksum disagrees with GitHub release metadata; retry or contact the publisher');
  }
  return { ...descriptor, url: assetUrl(release.sourceRef, item.name) };
}

/**
 * Verify an ArrayBuffer/Uint8Array against an already validated descriptor.
 * Returns a private Uint8Array snapshot: transform this result, not the input.
 * Size/hash verification does not establish bundle structure or credential safety.
 */
export async function verifyTemplateBytes(bytes, descriptor) {
  const item = validateTemplateAsset(descriptor?.templateAsset, validateTag(descriptor?.releaseTag));
  if (!(bytes instanceof Uint8Array) && !(bytes instanceof ArrayBuffer)) {
    throw new Error('Template download must contain bytes; discard the download and retry');
  }
  const view = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  if (view.byteLength !== item.size) {
    throw new Error('Template download size mismatch; discard the download and retry');
  }
  const expectedHash = item.sha256;
  const verified = new Uint8Array(view);
  const hash = await globalThis.crypto.subtle.digest('SHA-256', verified);
  const digest = Array.from(new Uint8Array(hash), (byte) => byte.toString(16).padStart(2, '0')).join('');
  if (digest !== expectedHash) {
    throw new Error('Template download checksum mismatch; discard the download and retry');
  }
  return verified;
}

/**
 * fetchJson(url) must perform bounded, credential-free HTTPS JSON GETs.
 * target_commitish and API-supplied object URLs are intentionally never trusted.
 */
export async function resolveLatestRelease(fetchJson) {
  const metadata = await fetchMetadata(fetchJson, `${API}/releases/latest`);
  const tag = validateTag(metadata.tag_name);
  if (metadata.draft !== false || metadata.prerelease !== false) {
    throw new Error('Latest release is not stable/public; retry or contact the publisher');
  }
  if (metadata.html_url !== `${WEB}/releases/tag/${tag}`) {
    throw new Error('Release URL does not match its tag and repository; retry or contact the publisher');
  }
  const ref = await fetchMetadata(fetchJson, `${API}/git/ref/tags/${tag}`);
  if (ref.ref !== `refs/tags/${tag}`) {
    throw new Error('Release tag resolution mismatch; retry or contact the publisher');
  }
  let object = ref.object;
  let sha;
  const seen = new Set();
  for (let depth = 0; depth <= 8; depth++) {
    if (!isObject(object)) throw new Error('Missing release tag object; retry or contact the publisher');
    sha = validateCommit(object.sha);
    if (object.type === 'commit') break;
    if (object.type !== 'tag' || seen.has(sha) || depth === 8) {
      throw new Error('Invalid or excessive annotated release tag chain; contact the publisher');
    }
    seen.add(sha);
    const annotated = await fetchMetadata(fetchJson, `${API}/git/tags/${sha}`);
    if (annotated.sha !== sha || (depth === 0 && annotated.tag !== tag)) {
      throw new Error('Annotated release tag mismatch; contact the publisher');
    }
    object = annotated.object;
  }
  const assets = validateAssets(metadata.assets, tag);
  return {
    sourceRef: tag, sourceCommit: sha, versionName: tag.slice(1),
    templateAvailable: Object.hasOwn(assets, 'openautolink-personalization.json')
      && Object.hasOwn(assets, `openautolink-template-${tag}.aab`),
    assets,
  };
}
