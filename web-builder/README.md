# Browser AAB tool — implementation in progress

This is an additive browser delivery path, not a replacement for `desktop-builder/`.
The preserved desktop baseline is commit `8819cdbe90a8c41bf11922d978aac03dba77b9ee`
on `feat/desktop-personal-builder`. Do not remove native packaging, desktop identity
management, template production, release sequencing, or their qualification tests.

## Contract

- Personalize the maintainer's unsigned credential-free template AAB; do not compile
  Android source, repack an APK, or use the maintainer's signing key.
- Keep user signing identity and optional authorized CarPlay files local to the
  browser. No backend custody or upload of private inputs is authorized.
- Encrypted identity backup is mandatory. Preserve app ID/key and increasing version
  codes on update. Never imply browser and desktop backup formats are interchangeable
  without a verified importer/exporter.
- Fail closed on missing latest-release template, mismatched hash/source metadata,
  unsupported protobuf identity fields, unsafe ZIPs or identity loss.
- Desktop remains available as an implementation/fallback path while browser
  feasibility is tested. No promised desktop downloads are published by this work.

## Qualification before deployment

1. Transform a real sanitized template and independently inspect with bundletool.
2. Sign first/update AABs locally and independently verify with Java jarsigner.
3. Compare all DEX/native payload bytes and identity-related manifest/resource fields.
4. Exercise encrypted backup restore and wrong-password/corruption handling.
5. Execute the same pipeline in real desktop browsers; capture memory/time/compatibility.
6. Verify outbound requests contain no user private inputs, including error paths.
7. Resolve GitHub asset CORS through a same-origin public-asset mirror if necessary;
   never solve it by uploading private bundles to a server.
8. Complete UI, accessibility, cancellation, error/recovery and browser review.

The Azure domain `https://oal.mossyg.com` currently serves only Azure's welcome page.
Hosting details are in `/hermes_work/oal-web-infrastructure.json`; public production
eligibility is separate from this Visual Studio dev/test preview.

## Development

Use Node 22 with `npm ci`, `npm test`, and (once the interface exists) `npm run build`.
Dependencies are pinned in package-lock.json. Do not deploy the Vite development server.

## Verified local feasibility

The combined core executed in real headless Chromium 147 on Linux and generated
first/update AABs from the retained v0.1.528 local prepared fixture. Both outputs
passed independent `bundletool validate` and `jarsigner -verify -strict` with the
disposable public certificate explicitly trusted. The browser restored the same
identity between builds, rejected a wrong backup password, preserved compiled
payload bytes, and attempted only GET requests to its local test origin.
Evidence: `qualification-output/browser/result.json` (ignored test artifacts).
Dedicated-worker first/update tests also passed independent Java checks and measured
main-thread responsiveness, cancellation and worker cleanup. Synthetic CarPlay DER
and PKCS7 inputs have passed real Chromium worker builds; exact selected assets were
signed while encrypted backup restore excluded them. See
`qualification-output/worker/` and `qualification-output/carplay-browser.json`.
The production-built wizard also passed eight enabled Chromium UI/state tests,
including initial/updated backup gates, real worker cancellation, restore and optional
CarPlay. The downloaded create/update/CarPlay AABs independently passed bundletool
and strict jarsigner checks. This uses intercepted local release metadata, not a
published latest-template download. Final reviewed-tree, cross-browser, Play and
vehicle qualification remain separate.
The combined core suite passed 240 tests with one opt-in skip; a separate enabled
pipeline run passed all 26 tests including the real-artifact qualification CLI.

`core/version-ledger.mjs` reserves versions under Web Locks and persists only public
app/certificate-fingerprint/high-water metadata in localStorage. It keeps reservations
on failures, verifies persistence, and rejects damaged/foreign identity records.
This is origin-local protection, not cross-device synchronization. An old backup on a
new browser must be reconciled with the highest version used in Play; the UI must
explain and collect that explicitly. Web and desktop backup formats are still distinct.

## Open dependency finding

`npm audit` flags node-forge 1.4.0 for GHSA-86w9-cpqp-85rv (RSA signature verification).
The implementation uses WebCrypto instead of forge's affected verifier and checks the
actual emitted signer signature before returning artifacts. Certificate algorithm and
validity regressions are also covered. See [security boundaries](docs/security.md)
for the reachability assessment and remaining qualification limits. The advisory is
not a claim that the whole dependency is safe; continue tracking upstream fixes.
