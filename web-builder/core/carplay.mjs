import forge from 'node-forge';

const validatedPairs = new WeakSet();

/**
 * Browser-local, optional CarPlay file validation; NOT Apple authentication.
 * No network, persistence, logging, certificate-chain/signature verification,
 * validity-period enforcement, revocation, Apple authorization or iPhone
 * compatibility claim. Only DER structure, P-256 key format and key pairing.
 * Forge handles ASN.1, not forge.pki's RSA-centric certificate/key importer;
 * all elliptic-curve operations use WebCrypto in a secure browser context.
 *
 * Inputs: null/undefined for neither, otherwise two Uint8Array views, each
 * 1–16384 bytes. PKCS8 must be unencrypted and match WebCrypto's canonical
 * DER export. Certificate input is single DER X.509 or DER PKCS7 SignedData
 * containing exactly one certificate (not a chain); original bytes survive.
 * Inputs are copied synchronously before the first await, including Buffers.
 *
 * Result: null or a frozen in-memory pair with keyBytes/certificateBytes
 * getters returning independent Uint8Array snapshots. Getters deliberately
 * are non-enumerable: JSON, spread and structuredClone omit credentials.
 * Keep the original result for injection; cloning loses validation provenance.
 * Never put these files/snapshots in upload-identity state, backups or logs.
 * This is accidental-serialization protection, not secrecy from code which
 * explicitly reads the getters. JS cannot guarantee secure memory erasure.
 */
export async function validateCarplayPair(keyBytes, certificateBytes) {
  if (keyBytes == null && certificateBytes == null) return null;
  if (keyBytes == null || certificateBytes == null) throw new Error('CarPlay requires both files or neither');
  for (const bytes of [keyBytes, certificateBytes]) {
    if (!(bytes instanceof Uint8Array) || bytes.byteLength < 1 || bytes.byteLength > 16384) {
      throw new Error('CarPlay inputs must be Uint8Array values (1–16384 bytes each)');
    }
  }
  keyBytes = new Uint8Array(keyBytes);
  certificateBytes = new Uint8Array(certificateBytes);
  try {
    const key = await crypto.subtle.importKey('pkcs8', keyBytes, { name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign']);
    const canonical = new Uint8Array(await crypto.subtle.exportKey('pkcs8', key));
    if (!equal(keyBytes, canonical)) throw new Error();
    const spki = certificatePublicKey(singleCertificate(parseDer(certificateBytes)));
    const publicKey = await crypto.subtle.importKey('spki', derBytes(spki), { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
    const challenge = crypto.getRandomValues(new Uint8Array(32));
    const algorithm = { name: 'ECDSA', hash: 'SHA-256' };
    const signature = await crypto.subtle.sign(algorithm, key, challenge);
    if (!await crypto.subtle.verify(algorithm, publicKey, signature, challenge)) throw new Error();
    const pair = Object.freeze(Object.defineProperties({}, {
      keyBytes: { get: () => keyBytes.slice() },
      certificateBytes: { get: () => certificateBytes.slice() },
    }));
    validatedPairs.add(pair);
    return pair;
  } catch {
    throw new Error('CarPlay files must be a matching DER PKCS8 P-256 key and exactly one certificate in DER X.509 or DER PKCS7 format');
  }
}

/**
 * Run AFTER archive validation/personalization and BEFORE signEntries:
 *   signEntries(injectCarplayEntries(transformedEntries, pair), uploadIdentity)
 * Returns copied entries with the canonical two assets appended, or just
 * copied entries for null/undefined. Refuses existing/aliased offline-mfi
 * paths, file/directory collisions and already-signed input; never overwrites.
 * Does not replace archive ZIP/CRC/symlink/size checks or AAB validation.
 * Returned entries and the signed AAB inherently contain extractable secrets;
 * do not persist them in identity backups or upload them as diagnostics.
 */
export function injectCarplayEntries(entries, pair) {
  if (pair != null && !validatedPairs.has(pair)) throw new Error('CarPlay pair must be validated in this browser context');
  if (!Array.isArray(entries)) throw new Error('CarPlay injection requires entry arrays');
  const seen = new Set(), files = new Set();
  for (const entry of entries) {
    if (!entry || typeof entry.name !== 'string' || !(entry.data instanceof Uint8Array)) {
      throw new Error('CarPlay injection requires named Uint8Array entries');
    }
    const { name, data } = entry;
    const parts = name.replace(/\/$/, '').split('/');
    if (parts.some(part => !part || part === '.' || part === '..' || /[ .]$/.test(part)) || /[\\\\:\x00-\x1f\x7f-\uffff]/.test(name)) {
      throw new Error('CarPlay injection requires safe entry names');
    }
    const lower = name.toLowerCase();
    const normalized = lower.replace(/\/$/, '');
    if (seen.has(normalized) || (name.endsWith('/') && data.length)) throw new Error('CarPlay injection refuses duplicate or invalid entries');
    seen.add(normalized);
    if (!name.endsWith('/')) files.add(normalized);
    if (/^meta-inf\/(?:manifest\.mf|[^/]+\.(?:sf|rsa|dsa|ec)|sig-[^/]+)$/.test(lower)) {
      throw new Error('CarPlay injection must precede signing');
    }
    if (parts.some(part => part.toLowerCase() === 'offline-mfi') || ['base', 'base/assets'].includes(lower) ||
        (lower.startsWith('base/') && !name.startsWith('base/')) ||
        (lower.startsWith('base/assets/') && !name.startsWith('base/assets/'))) {
      throw new Error('CarPlay injection refuses existing or aliased credential assets');
    }
  }
  for (const name of seen) {
    const parts = name.split('/');
    for (let i = 1; i < parts.length; i++) {
      if (files.has(parts.slice(0, i).join('/'))) throw new Error('CarPlay injection refuses file/directory aliases');
    }
  }
  const output = entries.map(({ name, data }) => ({ name, data: new Uint8Array(data) }));
  if (pair != null) output.push(
    { name: 'base/assets/offline-mfi/identity.pk8', data: pair.keyBytes },
    { name: 'base/assets/offline-mfi/certificate.p7b', data: pair.certificateBytes },
  );
  return output;
}

function derBytes(node) {
  return Uint8Array.from(forge.asn1.toDer(node).getBytes(), c => c.charCodeAt(0));
}

function parseDer(bytes) {
  const node = forge.asn1.fromDer(String.fromCharCode(...bytes), { strict: true, parseAllBytes: true, decodeBitStrings: false });
  if (!equal(bytes, derBytes(node))) throw new Error();
  return node;
}

function expect(node, type, constructed = false, tagClass = forge.asn1.Class.UNIVERSAL) {
  if (!node || node.type !== type || node.tagClass !== tagClass || node.constructed !== constructed) throw new Error();
  return node.value;
}

function singleCertificate(root) {
  const fields = expect(root, 16, true);
  if (fields[0]?.type !== 6) return root;
  if (fields.length !== 2 || forge.asn1.derToOid(expect(fields[0], 6)) !== '1.2.840.113549.1.7.2') throw new Error();
  const wrapper = expect(fields[1], 0, true, forge.asn1.Class.CONTEXT_SPECIFIC);
  if (wrapper.length !== 1) throw new Error();
  const signed = expect(wrapper[0], 16, true);
  expect(signed[0], 2);
  expect(signed[1], 17, true);
  const content = expect(signed[2], 16, true);
  if (content.length < 1 || content.length > 2) throw new Error();
  expect(content[0], 6);
  if (content.length === 2) {
    const payload = expect(content[1], 0, true, forge.asn1.Class.CONTEXT_SPECIFIC);
    if (payload.length !== 1) throw new Error();
  }
  const certificates = expect(signed[3], 0, true, forge.asn1.Class.CONTEXT_SPECIFIC);
  if (certificates.length !== 1) throw new Error();
  let index = 4;
  if (signed[index]?.tagClass === forge.asn1.Class.CONTEXT_SPECIFIC) {
    expect(signed[index++], 1, true, forge.asn1.Class.CONTEXT_SPECIFIC);
  }
  expect(signed[index++], 17, true);
  if (signed.length !== index) throw new Error();
  return certificates[0];
}

function certificatePublicKey(certificate) {
  const fields = expect(certificate, 16, true);
  if (fields.length !== 3) throw new Error();
  const tbs = expect(fields[0], 16, true);
  algorithmIdentifier(fields[1]);
  const signature = expect(fields[2], 3);
  if (signature.length < 2 || signature.charCodeAt(0) !== 0) throw new Error();
  const offset = tbs[0]?.tagClass === forge.asn1.Class.CONTEXT_SPECIFIC ? 1 : 0;
  if (offset) {
    const version = expect(tbs[0], 0, true, forge.asn1.Class.CONTEXT_SPECIFIC);
    if (version.length !== 1) throw new Error();
    if (!['\x00', '\x01', '\x02'].includes(expect(version[0], 2))) throw new Error();
  }
  if (!expect(tbs[offset], 2).length) throw new Error();
  algorithmIdentifier(tbs[offset + 1]);
  distinguishedName(tbs[offset + 2]);
  const validity = expect(tbs[offset + 3], 16, true);
  if (validity.length !== 2) throw new Error();
  for (const date of validity) {
    const value = expect(date, date.type);
    if (date.type === 23) {
      if (!/^\d{12}Z$/.test(value) || forge.asn1.dateToUtcTime(forge.asn1.utcTimeToDate(value)) !== value) throw new Error();
    } else if (date.type === 24) {
      if (!/^\d{14}Z$/.test(value) || forge.asn1.dateToGeneralizedTime(forge.asn1.generalizedTimeToDate(value)) !== value) throw new Error();
    } else throw new Error();
  }
  distinguishedName(tbs[offset + 4]);
  expect(tbs[offset + 5], 16, true);
  let index = offset + 6;
  for (const tag of [1, 2]) {
    if (tbs[index]?.tagClass === forge.asn1.Class.CONTEXT_SPECIFIC && tbs[index]?.type === tag) {
      expect(tbs[index++], tag, false, forge.asn1.Class.CONTEXT_SPECIFIC);
    }
  }
  if (index < tbs.length) {
    const wrapper = expect(tbs[index++], 3, true, forge.asn1.Class.CONTEXT_SPECIFIC);
    if (wrapper.length !== 1) throw new Error();
    const extensions = expect(wrapper[0], 16, true);
    if (!extensions.length) throw new Error();
    for (const extension of extensions) {
      const values = expect(extension, 16, true);
      expect(values[0], 6);
      if (values.length === 3 && expect(values[1], 1) !== '\xff') throw new Error();
      if (values.length !== 2 && values.length !== 3) throw new Error();
      expect(values.at(-1), 4);
    }
  }
  if (index !== tbs.length) throw new Error();
  return tbs[offset + 5];
}

function algorithmIdentifier(node) {
  const fields = expect(node, 16, true);
  if (fields.length < 1 || fields.length > 2) throw new Error();
  expect(fields[0], 6);
}

function distinguishedName(node) {
  for (const rdn of expect(node, 16, true)) {
    const attributes = expect(rdn, 17, true);
    if (!attributes.length) throw new Error();
    for (const attribute of attributes) {
      const fields = expect(attribute, 16, true);
      if (fields.length !== 2) throw new Error();
      expect(fields[0], 6);
      if (fields[1].constructed) throw new Error();
    }
  }
}

function equal(a, b) {
  return a.length === b.length && a.every((byte, index) => byte === b[index]);
}
