# Browser processing and trust boundaries

This document describes the implemented core, not a public-release certification.
The desktop builder remains a separate, preserved delivery path.

## What crosses the network

The public transport reads GitHub release/tag metadata and public unsigned template
files mirrored on the site's own origin. It does not accept an arbitrary download
URL, upload private inputs, or silently use an older release. The publisher must
prepare and verify the static mirror; this is not a signing server.

Signing keys, backup passwords and selected CarPlay files are inputs to local
browser code. A dedicated worker performs bundle transformation/signing. Generated
AAB and encrypted backup downloads are browser-generated. A user must still trust
the delivered website code, its origin and dependencies. Local processing does not
make a compromised website safe. Do not add analytics, remote scripts, error-body
telemetry, or private-file upload endpoints without a new threat review.

The published template descriptor binds release tag, immutable source commit,
format contract, size and SHA-256. This trusts GitHub/repository ownership and HTTPS;
it is not a maintainer signing-certificate signature over the template.

## Upload identity and recovery

RSA-2048/SHA-256 upload identities are generated explicitly using WebCrypto randomness.
Identity validation checks public/private key consistency and verifies the self-signed
certificate using WebCrypto. It does not create a replacement identity on failure.
The inner and outer certificate signature algorithms must agree. Signing checks the
certificate's current validity interval; backup/recovery does not invalidate an old
identity just because time passed.

The versioned web backup uses PBKDF2-SHA256 (600,000 iterations) and AES-256-GCM,
random salt/IV and authenticated format metadata. Parsing/field lengths and KDF
parameters are bounded. Backups contain the upload identity and version state,
not CarPlay inputs. Web and desktop backup formats are **not interchangeable**.
Keep the password separately and retain the latest backup after every build.

Only public application-ID, certificate-fingerprint and high-water metadata is
persisted by the browser version ledger. Web Locks serialize reservations on the
same origin. Failed/cancelled builds do not reclaim version codes. Browser storage
is not cross-device synchronization or the only recovery copy. Restore the latest
backup and reconcile the highest version already used in Play before updates on a
new computer, a cleared browser, or an old backup. No tool can infer uploads made
elsewhere solely from a stale backup.

## JAR signature checks and dependency advisory

`node-forge` 1.4.0 is reported by npm audit under GHSA-86w9-cpqp-85rv for its RSA
signature verifier. The application's certificate/key and final signature verification
use **WebCrypto**, not that verifier. Forge is used for DER/X.509/PKCS7 construction,
parsing and signature generation. This is a bounded reachability assessment, not a
claim that the package has no vulnerabilities. Changes introducing forge verification
require another review; continue monitoring upstream patches.

Because different WebCrypto engines can tolerate or repair malformed RSA CRT
components, the actual forge-generated signature is verified with WebCrypto over the
exact SF bytes **before any signed artifact is returned**. Regression tests mutate
private components and require safe rejection. This prevents exposure of the tested
faulty-signature cases. Valid artifacts are independently checked with Java jarsigner;
that test explicitly trusts the newly generated disposable public certificate.

## Optional CarPlay inputs

Both bounded canonical P-256 PKCS8 and matching single X.509/PKCS7 certificate inputs
are required, or neither. The branded validated pair stays local; raw byte snapshots
cross the worker boundary and are revalidated there. Injection follows transformation
and precedes signing. Format/key matching is not Apple trust, authorization, certificate
signature verification or iPhone acceptance. User-supplied bundled credentials remain
extractable from the resulting AAB/APK; users must have permission to distribute them.

## Resource limits and cancellation

ZIP inspection checks central/local consistency, CRC, expansion bounds, ratio bounds,
unsafe aliases, special file types and overlapping entries. ZIP64 is unsupported.
Fixed-size decompression output limits allocation even when expansion claims are
false. CPU cost is not bounded by that buffer, so builds run in a single-use worker
with a deadline and termination-based cancellation. Cancellation acknowledgement
and underlying browser-worker destruction are separate events; tests observe both.

## Proven versus pending

Proven locally: real prepared AAB first/update transformation and unchanged compiled
payloads, strict Java signature/bundletool verification, encrypted restore, tamper
rejection, Chromium worker responsiveness and cancellation cleanup. Optional CarPlay
pipeline tests use synthetic credentials only.

Still separate gates: completed wizard UX/backup flow, exact production bundle,
real published latest-template download, Firefox/WebKit and Windows/Mac browser
coverage, Play acceptance, installed update and car operation. No local test grants
production hosting eligibility on a Visual Studio dev/test subscription.
