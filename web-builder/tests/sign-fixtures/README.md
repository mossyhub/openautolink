# JAR signing fixtures and contract

`../sign.test.mjs` generates disposable RSA identities and ZIP fixtures at run time in `$TMPDIR`, removes them afterwards, and never stores private-key fixtures in the repository. Fixtures cover binary payloads, zero-byte files, Unicode names (including astral characters), long names, META-INF payloads, directory entries, prior signing metadata, and post-sign tampering.

## Independent verification

Requirements: Node 22+ (WebCrypto globals), installed `node-forge` and `fflate`, and a JDK providing both `jarsigner` and `keytool`. Set `JARSIGNER` to the full executable path (its adjacent `keytool` is used), or `JAVA_HOME`, or put both executables on PATH. `KEYTOOL` can override that executable separately.

```sh
JARSIGNER=/path/to/jdk/bin/jarsigner node --test tests/identity.test.mjs tests/sign.test.mjs
```

Tests import the generated self-signed certificate into a **disposable test-only** PKCS12 trust store, then execute:

```sh
keytool -importcert -noprompt -alias upload -file upload.pem \
  -keystore trust.p12 -storetype PKCS12 -storepass test-password
jarsigner -verify -strict -verbose -certs -keystore trust.p12 \
  -storepass test-password bundle.aab
```

The trusted fixture must report `jar verified.` and status 0, with `smk` payload entries. Altering the signed DEX bytes must produce a digest error and nonzero status. This tests cryptographic integrity, **not public CA trust**, timestamping, Play acceptance, or AAB schema validity. Without Java the JS assertions still run, but the test diagnostic says independent verification was unavailable; do not describe such a run as Java-verified.

## Public asynchronous API

- `createIdentity(applicationId) -> Promise<Identity>` explicitly creates RSA-2048/e=65537 with SHA256withRSA self-signed X.509 certificate valid for 30 years. It is never called by any recovery/signing function.
- `validateIdentity(identity) -> Promise<Identity>` validates the versioned web schema, application ID, high-water mark, RSA size (2048–4096)/exponent, certificate signature, and private/public key consistency. Returns the supplied object without replacing keys. Cryptographic verification uses WebCrypto only.
- `backupIdentity(identity, password) -> Promise<Uint8Array>` snapshots/validates the identity and encrypts its complete JSON representation.
- `restoreIdentity(bytes, password) -> Promise<Identity>` accepts only Uint8Array, validates the bounded/versioned envelope before KDF work, authenticates/decrypts, then validates the identity. Wrong password/damaged payload fails closed.
- `signEntries(entries, identity) -> Promise<Array<{name: string, data: Uint8Array}>>` snapshots input and returns the replacement `META-INF/MANIFEST.MF`, `META-INF/OAL.SF`, detached DER `META-INF/OAL.RSA`, then the retained input entries in order. Strips only root META-INF signing artifacts (case-insensitive manifest/SF/RSA/DSA/EC/SIG-*); retains services, licenses and nested files. Rejects duplicate/unsafe names, >65535-byte names, non-byte data, data-bearing directory entries, no payload files, or >100000 input entries. Lines use CRLF and stay within 72 UTF-8 bytes including CRLF, without splitting code points. SF digests cover the exact manifest and each complete serialized section, including its terminating blank line.

Identity JSON fields: `format: "oal-web-upload-identity"`, `formatVersion: 1`, `applicationId`, `versionCodeHighWaterMark` (integer 0–2100000000), `privateKeyPem` (PKCS8), `certificatePem` (X.509 PEM).

## Backup format v1 (web-only)

**Not interchangeable with the desktop backup/JKS format.** No import/export compatibility with desktop is claimed.

UTF-8 JSON envelope, exactly eight fields:

- `format: "oal-web-upload-backup"`
- `formatVersion: 1`
- `kdf: "PBKDF2-SHA256"`
- `iterations: 600000` (only this exact value accepted)
- `cipher: "AES-256-GCM"`
- `salt`: standard padded base64 of 16 random bytes
- `iv`: standard padded base64 of 12 random bytes
- `ciphertext`: standard padded base64 of ciphertext followed by 16-byte GCM tag

GCM additional authenticated data is the UTF-8 JSON serialization of the first five fields, in the order listed above, without extra spaces. Every backup uses fresh WebCrypto randomness. Passwords must be 8–1024 UTF-8 bytes (use a long unique passphrase); no normalization is applied. Envelope limit: 262144 bytes. Identity plaintext limit: 65536 UTF-8 bytes. Each PEM field limit: 16384 characters. Unknown versions/algorithms/iteration counts and malformed fields are rejected, not migrated or guessed.

## Security and integration boundaries

Core modules have no Node APIs or network/storage access. They need a secure browser context with `crypto.subtle`, `crypto.getRandomValues`, `TextEncoder`, `TextDecoder`, `structuredClone`, `atob` and `btoa`; a browser bundler must resolve `node-forge`.

The node-forge RSA verification advisory GHSA-86w9-cpqp-85rv is **not reached by these modules**: no forge RSA/certificate/PKCS7 verifier is called. Forge parses/encodes bounded PEM/ASN.1 and signs X.509/PKCS7; WebCrypto generates keys, validates certificate and key-possession signatures, computes manifest SHA256 digests, and performs PBKDF2/AES-GCM. This is a reachability mitigation, not a claim that the dependency is vulnerability-free.

Identity private keys necessarily exist unencrypted in JS memory and the serializable identity. The caller owns secure origin/storage, protection against XSS/extensions, explicit export consent, atomic persistence, and backups. Signing does not increment or reset `versionCodeHighWaterMark`: the caller must reserve/persist versions transactionally, reuse the same identity, and merge restored high-water marks with the maximum already recorded **for the same application/key**. An older standalone backup cannot know later versions or recover a lost key. Never silently create a new identity when loading fails. Prevent concurrent-tab version allocation conflicts.

Signing is JAR/v1 AAB upload signing, not APK v2/v3/v4 or Play app-signing identity management. The caller must ensure a valid AAB and unchanged returned entry bytes, then ZIP them. Memory is proportional to the uncompressed entries; this is not a streaming signer. Existing manifest attributes are replaced (appropriate for AABs; do not use as a general executable-JAR rewriter).
