import { readArchive, writeArchive } from './archive.mjs';
import { transformAabEntries } from './transform.mjs';
import { backupIdentity, validateIdentity } from './identity.mjs';
import { signEntries } from './sign.mjs';
import { verifyTemplateBytes } from './releases.mjs';
import { validateCarplayPair, injectCarplayEntries } from './carplay.mjs';

/** Build locally with an explicitly supplied, existing browser upload identity.
 * templateBytes: Uint8Array|ArrayBuffer; descriptor: already release-validated
 * descriptor (e.g. validateDescriptor result). The SHA/size are rechecked here;
 * this API cannot establish the caller's release-metadata provenance.
 * identity: original/pre-reservation identity; versionCode: explicit allocation
 * greater than its high-water mark, <= Android's 2100000000 maximum.
 * backupPassword: 8–1024 UTF-8 bytes, used only for the returned encrypted backup.
 * carplay: optional raw {keyBytes: Uint8Array, certificateBytes: Uint8Array};
 * omit or use null/undefined for a credential-free build. When supplied, both
 * files are required (1–16384 bytes each): canonical unencrypted DER PKCS8
 * P-256 key plus matching single DER X.509 or single-certificate DER PKCS7.
 * Send raw bytes across worker structured clone/transfer, NOT a branded result
 * from validateCarplayPair: its validation provenance cannot cross that boundary.
 * This pipeline validates locally and retains the original branded pair for
 * injection after transformation and before signing. Bundled bytes are
 * extractable from the AAB; they NEVER enter identity state or its backup and
 * must be supplied again for updates. Validation is not Apple authorization or
 * proof of iPhone compatibility. Malformed/partial/mismatched pairs reject.
 * Returns {aabBytes: Uint8Array, identity: object, backupBytes: Uint8Array}.
 * No persistence/network/key creation occurs here. Caller must serialize and
 * durably reserve the version BEFORE calling (e.g. reserveVersion), retaining
 * reservations on failure. Supply the original identity here, not the ledger's
 * already-incremented copy. On success retain the updated identity AND backup
 * before releasing the AAB; never roll back a durable reservation on failure.
 * Caller-owned inputs are snapshotted before the first await and never changed.
 */
export async function buildPersonalizedAab({ templateBytes, descriptor, identity, versionCode, backupPassword, carplay }) {
  identity = structuredClone(identity);
  descriptor = structuredClone(descriptor);
  if (carplay != null && (typeof carplay !== 'object' || Array.isArray(carplay) ||
      (carplay.keyBytes == null && carplay.certificateBytes == null))) {
    throw new Error('CarPlay requires a raw keyBytes/certificateBytes pair, or null/undefined to omit it');
  }
  if (descriptor?.schemaVersion !== 1 || descriptor.formatVersion !== 1 ||
      descriptor.transformContract !== 'oal-identity-v1' ||
      descriptor.templateApplicationId !== 'com.openautolink.template' ||
      typeof descriptor.sourceCommit !== 'string' || !/^[0-9a-f]{40}(?![\s\S])/.test(descriptor.sourceCommit)) {
    throw new Error('Unsupported personalization descriptor');
  }
  // Both validators snapshot their raw byte inputs synchronously. Start both
  // before yielding; structuredClone alone would alias SharedArrayBuffer views.
  // Keep the branded validation result itself: cloning loses its provenance.
  const [verifiedTemplateBytes, carplayPair] = await Promise.all([
    verifyTemplateBytes(templateBytes, descriptor),
    validateCarplayPair(carplay?.keyBytes, carplay?.certificateBytes),
  ]);
  templateBytes = verifiedTemplateBytes;
  await validateIdentity(identity);
  if (!Number.isInteger(versionCode) || versionCode < 1 || versionCode > 2100000000 ||
      versionCode <= identity.versionCodeHighWaterMark) {
    throw new Error('Explicit versionCode must exceed the identity high-water mark and be at most 2100000000');
  }
  const transformed = transformAabEntries(readArchive(templateBytes), {
    applicationId: identity.applicationId, versionCode, versionName: descriptor.releaseTag.slice(1),
  });
  const aabBytes = writeArchive(await signEntries(injectCarplayEntries(transformed, carplayPair), identity));
  identity.versionCodeHighWaterMark = versionCode;
  const backupBytes = await backupIdentity(identity, backupPassword);
  return { aabBytes, identity, backupBytes };
}
