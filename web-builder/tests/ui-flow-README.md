# Browser wizard tests

These are **local-template integration tests**, not public-release qualification. They intercept GitHub release/ref metadata and same-origin template downloads only. Identity creation/restoration, checksum verification, transformation, dedicated worker builds, signing, encryption, file downloads and version reservations are real. No mock signing is used. Optional CarPlay tests generate disposable synthetic files; they establish neither Apple authorization nor iPhone compatibility.

## Run

Start the parent project's Vite server on the desired local origin. Then set:

- `OAL_UI_TEST=1` to opt into browser tests (ordinary Node test runs skip browser cases).
- `OAL_UI_ORIGIN` if not `http://127.0.0.1:5187`.
- `OAL_WEB_TEMPLATE` to an actual prepared unsigned local template AAB.
- `OAL_WEB_DESCRIPTOR` optionally; defaults to `openautolink-personalization.json` beside that AAB.
- `CARPLAY_FIXTURE_PYTHON` to a Python interpreter with `cryptography`; otherwise uses `python` on Windows and `python3` elsewhere.
- `OAL_BROWSER` optionally; otherwise Playwright uses its installed Chromium. No machine-specific browser path is embedded in these tests.
- `OAL_UI_OUTPUT` for disposable test downloads/screenshots; defaults to `TMPDIR/oal-ui-flow-evidence` (cwd fallback when TMPDIR is absent).

Run `node --test tests/ui-flow.test.mjs tests/ui-state.test.mjs`.

To exercise built assets and the shipping CSP, first build into a scratch directory and set `OAL_UI_DIST` to it. Tests serve those files through route interception on the existing origin; they do not launch/change a host. Release/mirror fixtures remain test-only.

For independent validation of the three downloaded AABs, set `OAL_UI_OUTPUT`, `OAL_WEB_TEMPLATE` and `OAL_WEB_TOOLS` (directory containing `jdk-17/bin` and `bundletool-1.18.3.jar`), then run `node tests/ui-flow-verify.mjs`. This verifies bundletool structure, jarsigner strict signatures against the generated test certificates, identity/version correspondence with encrypted backups, and unchanged compiled template payloads. It writes `java-verification.json`. Never point this helper at a user's backup: its password belongs to disposable UI test artifacts.

## Coverage

- Missing latest template fails clearly, rechecks latest, and never falls back.
- New IDs accepted by the actual transformer; password confirmation; initial backup download and confirmation before building.
- Updated backup download and confirmation before AAB download; only public ledger data persisted.
- Real reload requires explicit web-backup restoration; desktop/JKS format incompatibility is stated.
- Update requires file, password and highest Play version; wrong password does not create/replace identity.
- Real worker cancellation retains its durable version reservation and retry advances it.
- Optional CarPlay pair/authorization requirements, mismatched pair rejection, real inclusion, and backup exclusion.
- Object URL revocation, explicit clearing, pagehide/pageshow cleanup, narrow-screen notice and horizontal overflow.
- Missing browser capabilities fail before requesting private inputs; restrictive static hosting headers.

## UI review scope

The Operate surface inherits desktop `oal_builder/ui.py`: charcoal `#101213`, panels `#191c1d`, mint `#99d8b6`, system UI type, conventional labeled controls and a four-step rail. No marketing treatment or shipping raster assets were added. Rendered desktop and narrow-screen captures were inspected in-thread; no independent design reviewer ran. The Impeccable launcher/detector were permission-denied in this environment; project token/code inspection substituted for context loading. Product/design documentation outside the assigned UI/test ownership was not changed.

A successful local build or these tests do not establish a public template's availability, cross-OS browser qualification, Google Play acceptance or vehicle compatibility. Test screenshots showing a template version depict the intercepted local fixture, not a live public release.
