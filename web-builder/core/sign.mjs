import forge from 'node-forge';
import { validateIdentity } from './identity.mjs';

const encoder = new TextEncoder();

/** JAR/v1 upload signing for an AAB, NOT APK v2/v3/v4 signing.
 * Input/output are uncompressed ZIP entries. Caller must ZIP returned bytes
 * without editing signed files, validate AAB structure, and reserve/persist
 * versionCodeHighWaterMark itself; signing never resets or mutates identity.
 */
export async function signEntries(entries, identity) {
  identity = structuredClone(identity);
  entries = cleanEntries(entries);
  await validateIdentity(identity);
  const files = entries.filter(entry => !entry.name.endsWith('/'));
  const sections = [];
  for (const entry of files) {
    sections.push(section(entry.name, await digest(entry.data)));
  }
  const manifest = encoder.encode('Manifest-Version: 1.0\r\n\r\n' + sections.join(''));
  const sfSections = [];
  for (let i = 0; i < files.length; i++) {
    sfSections.push(section(files[i].name, await digest(encoder.encode(sections[i]))));
  }
  const sf = encoder.encode(`Signature-Version: 1.0\r\nSHA-256-Digest-Manifest: ${await digest(manifest)}\r\n\r\n` + sfSections.join(''));
  const certificate = forge.pki.certificateFromPem(identity.certificatePem);
  // Recovery/backup validation is deliberately time-independent. Only signing
  // requires a currently valid certificate; never replace an expired identity.
  const now = Date.now();
  const notBefore = certificate.validity.notBefore.getTime();
  const notAfter = certificate.validity.notAfter.getTime();
  if (!Number.isFinite(notBefore) || !Number.isFinite(notAfter) ||
      notBefore > notAfter || now < notBefore || now > notAfter) {
    throw new Error('Certificate validity does not permit signing now');
  }
  const signedData = forge.pkcs7.createSignedData();
  signedData.content = forge.util.createBuffer(binary(sf));
  signedData.addCertificate(identity.certificatePem);
  signedData.addSigner({ key: identity.privateKeyPem, certificate: identity.certificatePem,
    digestAlgorithm: forge.pki.oids.sha256 });
  signedData.sign({ detached: true });
  // WebCrypto's key proof may repair/ignore damaged CRT parameters that forge
  // uses verbatim. Verify the actual signer output over these exact SF bytes
  // before exposing any artifact: a faulty CRT signature can disclose factors.
  // No authenticated attributes are configured above; the SF is signed directly.
  // NEVER use forge's RSA verifier here (GHSA-86w9-cpqp-85rv).
  const algorithm = { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' };
  const publicKey = await crypto.subtle.importKey('spki', Uint8Array.from(forge.asn1.toDer(
    forge.pki.publicKeyToAsn1(certificate.publicKey)).getBytes(), c => c.charCodeAt(0)),
  algorithm, false, ['verify']);
  const signature = Uint8Array.from(signedData.signers[0].signature, c => c.charCodeAt(0));
  if (!await crypto.subtle.verify(algorithm, publicKey, signature, sf)) {
    throw new Error('Generated RSA signature failed verification');
  }
  const rsa = Uint8Array.from(forge.asn1.toDer(signedData.toAsn1()).getBytes(), c => c.charCodeAt(0));
  return [
    { name: 'META-INF/MANIFEST.MF', data: manifest },
    { name: 'META-INF/OAL.SF', data: sf },
    { name: 'META-INF/OAL.RSA', data: rsa },
    ...entries.map(({ name, data }) => ({ name, data: data.slice() })),
  ];
}

function cleanEntries(entries) {
  if (!Array.isArray(entries) || !entries.length || entries.length > 100000) throw new Error('Invalid ZIP entries');
  const seen = new Set();
  const result = [];
  for (const entry of entries) {
    if (!entry || typeof entry.name !== 'string' || !(entry.data instanceof Uint8Array)) {
      throw new Error('Each ZIP entry needs a name and Uint8Array data');
    }
    const { name, data } = entry;
    const path = name.endsWith('/') ? name.slice(0, -1) : name;
    if (!path || /[\x00-\x1f\x7f\\:]/.test(name) || encodeName(name) !== name ||
        encoder.encode(name).length > 65535 || path.split('/').some(part => !part || part === '.' || part === '..')) {
      throw new Error('Unsafe or invalid ZIP entry name');
    }
    if (seen.has(name)) throw new Error('Duplicate ZIP entry name');
    seen.add(name);
    if (name.endsWith('/') && data.length) throw new Error('ZIP directory entry has data');
    if (/^META-INF\/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC)|SIG-[^/]+)$/i.test(name)) continue;
    result.push({ name, data: data.slice() });
  }
  if (!result.some(entry => !entry.name.endsWith('/'))) throw new Error('No file entries to sign');
  return result;
}

function encodeName(name) {
  return new TextDecoder().decode(encoder.encode(name));
}

function section(name, hash) {
  return wrappedLine(`Name: ${name}`) + wrappedLine(`SHA-256-Digest: ${hash}`) + '\r\n';
}

// Keep each physical line <=72 UTF-8 bytes INCLUDING CRLF; never split a
// code point. A continuation's leading space counts toward its byte budget.
function wrappedLine(value) {
  let output = '';
  let line = '';
  let length = 0;
  for (const codePoint of value) {
    const size = encoder.encode(codePoint).length;
    if (length + size > 70) {
      output += line + '\r\n';
      line = ' ';
      length = 1;
    }
    line += codePoint;
    length += size;
  }
  return output + line + '\r\n';
}

async function digest(bytes) {
  return btoa(binary(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))));
}

function binary(bytes) {
  let value = '';
  for (const byte of bytes) value += String.fromCharCode(byte);
  return value;
}
