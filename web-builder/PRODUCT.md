# Personal OAL builder — Web

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

Beginners creating or updating a personal OpenAutoLink car app from a desktop browser, without compiling Android source or managing a command-line signing workflow.

## Product Purpose

Personalize and locally sign the maintainer’s unsigned, credential-free template as an Android App Bundle (AAB). The intended next step is the owner’s Google Play Console app on its **dedicated Android Automotive OS Internal Test** track. An AAB is not directly installable on a car; a successful build does not establish Play acceptance, vehicle eligibility or compatibility.

The browser path is additive. Preserve the existing desktop builder, identity management, packaging and documentation; this work neither replaces them nor publishes desktop downloads.

## Operating Context

Use a current desktop Chrome or Edge on Windows, macOS or Linux; this is guidance, not a tested-platform matrix. The implementation requires a secure context, Web Crypto, workers and Web Locks, plus browser storage for safe version reservation. Narrow screens show a desktop-browser notice rather than a separate mobile product.

The implemented sequence is:

1. **Latest release:** check GitHub metadata, then download and verify the matching public template from the same-origin mirror. Missing or unsupported latest templates block progress; older releases are not a fallback.
2. **Your identity:** create a unique app ID/upload signing key, or restore an encrypted web backup with its password and the highest version code ever uploaded to Play. Failed restoration never creates a replacement identity.
3. **Save & build:** request the initial backup download and explicitly confirm it was saved before building locally in a worker. Reserve the version before building; cancellation and failure retain the reservation.
4. **Downloads:** request the updated backup download and confirm it was saved before enabling the AAB download. The updated backup preserves the identity and new version high-water mark. A browser download request is not proof that the file reached durable storage.

Installation is outside the builder: follow the [Internal Test guide](../docs/install-gm.md). The UI links to its branch-preview version; it does not automate Play Console or installation.

## Capabilities and Constraints

- Private inputs, passwords, identity and build data are processed in browser memory, not uploaded. Only public app/version-reservation metadata persists in browser storage. The interface loads no analytics or external scripts.
- Keep the same app ID and signing key for every update. Web backups and desktop/JKS backups are **not interchangeable**. There is no backup-password recovery.
- Reservations are origin-local, not cross-device synchronization. On update, supply the highest Play version across all tracks and uploaded bundles; the next version must exceed Play, the restored backup and browser reservations. Do not clear damaged/conflicting records to force a build.
- Clear/start-over and page exit discard the tab’s private state and revoke download URLs, while preserving version reservations. They do not delete files already downloaded. Cleanup is best-effort, not guaranteed secure erasure of JavaScript memory.
- Optional CarPlay inputs require an authorized matching pair: unencrypted DER PKCS8 P-256 key plus a single DER X.509 or DER PKCS7 certificate, each 1 byte–16 KiB. Both or neither must be supplied, with explicit authorization/extractability acknowledgment. Embedded files are extractable from the AAB, excluded from the web backup, and must be supplied again for updates. The builder supplies no credentials and establishes neither Apple authorization nor iPhone compatibility.

## Brand Commitments

Retain OpenAutoLink’s inherited desktop dark/mint identity. Use plain, task-focused language, explicit backup consequences and actionable errors. Keep developer review dispositions and internal design-decision metadata out of the shipping interface.

## Evidence on Hand

- The current public latest release lacks a supported template. This is a blocking product limitation, not permission to present an older fixture as latest or claim a usable public release path.
- Recorded local Chromium/Linux fixture runs produced first/update AABs with restored identity, independent bundletool/jarsigner checks and payload preservation. Worker, cancellation and synthetic CarPlay checks are local fixture evidence only; see [README](README.md) and its qualification-artifact references.
- The scoped [UI flow review](tests/ui-flow-review.md) records backup gates, restore/error behavior, optional CarPlay, narrow-screen adaptation and status/focus checks. Its disposition is not deployment approval or independent platform qualification.
- No broader browser/OS, accessibility certification, latest-public-template, Google Play, vehicle or iPhone qualification is established. **No hosting or production eligibility is granted.**

## Accessibility & Inclusion

The implemented UI has a skip link, semantic headings and field labels, visible keyboard focus, a current-step indicator, a polite status region and a focused alert for errors. Controls wrap/stack at narrow widths. Motion is limited to color transitions when reduced motion is not requested. These patterns do not constitute an accessibility certification.
