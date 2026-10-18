import forge from 'node-forge';

/** Browser-only upload identity. This is NOT a desktop/JKS backup format.
 * Persist the entire JSON object before building. Creation is explicit only;
 * callers must never invoke it as a fallback for a missing/corrupt identity.
 * versionCodeHighWaterMark belongs to the caller's transactional persistence.
 */
export async function createIdentity(applicationId) {
  validateApplicationId(applicationId);
  const pair = await crypto.subtle.generateKey({
    name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048,
    publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256',
  }, true, ['sign', 'verify']);
  const privateKeyPem = forge.pki.privateKeyInfoToPem(forge.asn1.fromDer(
    toBinary(new Uint8Array(await crypto.subtle.exportKey('pkcs8', pair.privateKey)))));
  const privateKey = forge.pki.privateKeyFromPem(privateKeyPem);
  const certificate = forge.pki.createCertificate();
  certificate.publicKey = forge.pki.setRsaPublicKey(privateKey.n, privateKey.e);
  const serial = crypto.getRandomValues(new Uint8Array(16));
  serial[0] = (serial[0] & 0x7f) | 1;
  certificate.serialNumber = forge.util.bytesToHex(toBinary(serial));
  certificate.validity.notBefore = new Date(Date.now() - 300_000);
  certificate.validity.notAfter = new Date();
  certificate.validity.notAfter.setUTCFullYear(certificate.validity.notAfter.getUTCFullYear() + 30);
  const subject = [{ name: 'commonName', value: `OAL upload ${applicationId}` }];
  certificate.setSubject(subject);
  certificate.setIssuer(subject);
  certificate.setExtensions([
    { name: 'basicConstraints', cA: false },
    { name: 'keyUsage', digitalSignature: true },
    { name: 'subjectKeyIdentifier' },
  ]);
  certificate.sign(privateKey, forge.md.sha256.create());
  return {
    format: 'oal-web-upload-identity', formatVersion: 1, applicationId,
    versionCodeHighWaterMark: 0, privateKeyPem,
    certificatePem: forge.pki.certificateToPem(certificate),
  };
}

function validateApplicationId(applicationId) {
  if (typeof applicationId !== 'string' || applicationId.length > 255 ||
      !/^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/.test(applicationId)) {
    throw new Error('Invalid Android applicationId');
  }
}

/** Validate untrusted stored/restored identity using WebCrypto, NEVER forge's
 * RSA verifier (GHSA-86w9-cpqp-85rv). Returns the same object, never a new key.
 * This is not CA trust validation or signing-time validity validation: expired
 * certificates remain recoverable. signEntries checks validity and its actual
 * generated signature separately; this key proof alone does not validate CRT.
 */
export async function validateIdentity(identity) {
  if (!identity || identity.format !== 'oal-web-upload-identity' || identity.formatVersion !== 1) {
    throw new Error('Missing or unsupported web upload identity');
  }
  if (encoder.encode(JSON.stringify(identity)).length > 65536) throw new Error('Identity size limit exceeded');
  validateApplicationId(identity.applicationId);
  if (!Number.isInteger(identity.versionCodeHighWaterMark) || identity.versionCodeHighWaterMark < 0 ||
      identity.versionCodeHighWaterMark > 2100000000) throw new Error('Invalid identity version high water mark');
  if (typeof identity.privateKeyPem !== 'string' || identity.privateKeyPem.length > 16384 ||
      typeof identity.certificatePem !== 'string' || identity.certificatePem.length > 16384) {
    throw new Error('Invalid identity key/certificate');
  }
  try {
    const key = forge.pki.privateKeyFromPem(identity.privateKeyPem);
    const cert = forge.pki.certificateFromPem(identity.certificatePem);
    if (key.n.bitLength() < 2048 || key.n.bitLength() > 4096 || key.e.intValue() !== 65537 ||
        !key.n.equals(cert.publicKey.n) || !key.e.equals(cert.publicKey.e) ||
        cert.signatureOid !== forge.pki.oids.sha256WithRSAEncryption ||
        cert.siginfo.algorithmOid !== cert.signatureOid) throw new Error('Invalid RSA identity');
    const binaryBytes = value => Uint8Array.from(value, c => c.charCodeAt(0));
    const algorithm = { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' };
    const publicKey = await crypto.subtle.importKey('spki', binaryBytes(forge.asn1.toDer(
      forge.pki.publicKeyToAsn1(cert.publicKey)).getBytes()), algorithm, false, ['verify']);
    if (!await crypto.subtle.verify(algorithm, publicKey, binaryBytes(cert.signature), binaryBytes(
      forge.asn1.toDer(cert.tbsCertificate).getBytes()))) throw new Error('Invalid certificate signature');
    const privateKey = await crypto.subtle.importKey('pkcs8', binaryBytes(forge.asn1.toDer(
      forge.pki.wrapRsaPrivateKey(forge.pki.privateKeyToAsn1(key))).getBytes()), algorithm, false, ['sign']);
    const challenge = crypto.getRandomValues(new Uint8Array(32));
    const proof = await crypto.subtle.sign(algorithm, privateKey, challenge);
    if (!await crypto.subtle.verify(algorithm, publicKey, proof, challenge)) throw new Error('Invalid private key');
  } catch {
    throw new Error('Invalid or mismatched identity key/certificate');
  }
  return identity;
}

const encoder = new TextEncoder();
const BACKUP_HEADER = {
  format: 'oal-web-upload-backup', formatVersion: 1,
  kdf: 'PBKDF2-SHA256', iterations: 600000, cipher: 'AES-256-GCM',
};
const aad = encoder.encode(JSON.stringify(BACKUP_HEADER));

/** UTF-8 JSON envelope, PBKDF2-SHA256/600000, AES-256-GCM, 16-byte
 * salt, 12-byte IV, 128-bit tag appended to ciphertext. Web format only.
 */
export async function backupIdentity(identity, password) {
  identity = structuredClone(identity);
  await validateIdentity(identity);
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const key = await backupKey(password, salt);
  const ciphertext = new Uint8Array(await crypto.subtle.encrypt({
    name: 'AES-GCM', iv, additionalData: aad, tagLength: 128,
  }, key, encoder.encode(JSON.stringify(identity))));
  return encoder.encode(JSON.stringify({ ...BACKUP_HEADER,
    salt: btoa(toBinary(salt)), iv: btoa(toBinary(iv)), ciphertext: btoa(toBinary(ciphertext)),
  }));
}

export async function restoreIdentity(bytes, password) {
  if (!(bytes instanceof Uint8Array)) throw new TypeError('Backup must be Uint8Array');
  if (bytes.length < 100 || bytes.length > 262144) throw new Error('Invalid backup size');
  try {
    const envelope = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
    if (!envelope || Object.keys(envelope).length !== 8 ||
        Object.entries(BACKUP_HEADER).some(([key, value]) => envelope[key] !== value)) {
      throw new Error('Unsupported backup format');
    }
    const decode = value => {
      if (typeof value !== 'string' || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value)) {
        throw new Error('Malformed backup encoding');
      }
      return Uint8Array.from(atob(value), c => c.charCodeAt(0));
    };
    const salt = decode(envelope.salt);
    const iv = decode(envelope.iv);
    const ciphertext = decode(envelope.ciphertext);
    if (salt.length !== 16 || iv.length !== 12 || ciphertext.length < 17 || ciphertext.length > 65552) {
      throw new Error('Invalid backup field size');
    }
    const plaintext = await crypto.subtle.decrypt({
      name: 'AES-GCM', iv, additionalData: aad, tagLength: 128,
    }, await backupKey(password, salt), ciphertext);
    return await validateIdentity(JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(plaintext)));
  } catch {
    throw new Error('Wrong password or damaged web identity backup');
  }
}

async function backupKey(password, salt) {
  if (typeof password !== 'string' || encoder.encode(password).length < 8 || encoder.encode(password).length > 1024) {
    throw new Error('Backup password must be 8–1024 UTF-8 bytes');
  }
  const material = await crypto.subtle.importKey('raw', encoder.encode(password), 'PBKDF2', false, ['deriveKey']);
  return crypto.subtle.deriveKey({ name: 'PBKDF2', hash: 'SHA-256',
    salt, iterations: BACKUP_HEADER.iterations,
  }, material, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}

function toBinary(bytes) {
  let result = '';
  for (const byte of bytes) result += String.fromCharCode(byte);
  return result;
}
