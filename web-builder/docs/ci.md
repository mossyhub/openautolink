# Web-builder CI qualification boundaries

Workflow: [`.github/workflows/qualify-web-builder.yml`](../../.github/workflows/qualify-web-builder.yml).
This is additive, read-only qualification. It does not replace or modify desktop
workflows, publish releases, deploy a site, upload to Play, or use repository
secrets.

## Automatic checks

Pull requests touching `web-builder/**` or this workflow run the **Node 22 core
and build** matrix. Pushes with those paths run on `feat/**` branches; manual
runs also run this matrix. Hosts are Ubuntu 22.04 x86-64, Windows Server 2022
x86-64, macOS 15 Intel, and macOS 15 ARM64.

Each job:

- Checks out and asserts the exact PR head SHA (not the synthetic merge), or
  push/manual SHA, with persisted Git credentials disabled.
- Uses `setup-node` for Node 22 and `npm ci` against the committed
  `web-builder/package-lock.json`. There is no lockfile regeneration or floating
  `npx` package download.
- Uses `setup-python` for Python 3.12 and installs `cryptography==44.0.3`, matching
  the existing desktop runtime pin. `CARPLAY_FIXTURE_PYTHON` is the actual setup
  interpreter path, not a Linux-only development virtualenv path.
- Creates fresh scratch outside the checkout under the runner's native
  `RUNNER_TEMP`, then sets `TMPDIR`, `TMP`, and `TEMP` to that directory. Paths
  written to `GITHUB_ENV` come from Python, avoiding Git Bash path conversion on
  Windows. The same rules apply to the browser matrix.
- Runs `node --test tests/*.test.mjs` with Bash glob expansion on each OS, and
  builds the production Vite assets into scratch.

**A green automatic run is core/build evidence, not browser qualification.**
Browser UI, real-template Java parity, real pipeline qualification and the local
mirror fixture case are opt-in tests and remain disabled in this job. Optional
Java corroboration in core tests depends on runner tooling; this workflow does
not promise independently Java-validated AAB output. Synthetic CarPlay fixtures
are generated locally, not fetched from or copied out of a user's credentials.

## Explicit local-template Chromium qualification

Manually run this workflow on the desired feature commit and enable
`local_template_chromium`. Leave it false for core/build only. Enabling it opts
into the existing CI Android-license consent and the substantial runtime of the
**entire existing desktop AAB qualification workflow**, not just a template
fetch.

### Existing producer API, unchanged

Inspection of `qualify-desktop-aab.yml` shows a `workflow_call` API with:

- `template_mode`
- required `expected_release_tag`
- required `expected_source_commit`
- required `builder_commit`

It has no separate template-only reusable job and no workflow outputs. Its
producer shares the four allowlisted public files in the same-run artifact
`native-template-inputs`: `release.json`, `template.aab`,
`openautolink-personalization.json`, and `provenance.json`.

The web workflow therefore calls this existing API only on explicit manual
opt-in, using `template_mode: local`, the exact manual-run SHA for
`builder_commit`, and empty expected-release/source strings. Those strings are
intentional: the existing API only accepts an externally asserted expected
release tuple in **published** mode. They are not an empty template or a bypass
of template validation. No desktop files are changed or copied into a new
producer implementation.

The existing producer resolves latest stable once, binds the immutable release
source tuple, compiles and verifies the intentional credential-free unsigned
template on Linux, and records any allowlisted source preparation. It does not
relabel a retained historical AAB. Its native desktop jobs must also succeed
before the web browser jobs start. The called workflow's own concurrency group
is unchanged: avoid a competing desktop qualification run on the same ref,
which can cancel this dependency. A canceled or failed dependency is not a
qualified browser run.

Each Chromium host downloads only `native-template-inputs` from **this run**,
then calls the existing `scripts.ci_template.load_inputs` validator. That checks
the descriptor hash, AAB size/hash, release/descriptor source binding, and
bounded preparation provenance. The browser job requires
`local-linux-template` provenance and reports the public source tag/commit.
The builder checkout SHA and template's upstream release SHA are separate
identities, not interchangeable. Missing/incompatible inputs fail; there is no
older-release fallback, invented ZIP, arbitrary artifact-run ID, or local path
input supplied by the dispatcher.

### What the browser jobs exercise

The browser matrix uses the same four native hosts as core. It installs only
**Chromium** from the locked Playwright dependency:

```sh
# Ubuntu, including required runner libraries:
node node_modules/playwright/cli.js install --with-deps chromium
# Native Windows and macOS:
node node_modules/playwright/cli.js install chromium
```

Each host builds production assets into scratch and serves them from a bounded
loopback-only Python HTTP server with the shipping `staticwebapp.config.json`
headers, including CSP. The test process and server run in one step with
bounded execution and guaranteed server teardown. The suite runs with
`OAL_UI_TEST=1`:

```sh
node --test --test-reporter=tap tests/ui-flow.test.mjs tests/ui-state.test.mjs
```

The test file imports and launches `chromium`; no Firefox/WebKit label is
attached to that code. These are real wizard/worker tests with intercepted
release metadata and same-origin **real template** bytes. They exercise backup
confirmation gates, signed AAB downloads, restore/version reservations,
cancellation/retry, optional synthetic CarPlay inclusion and backup exclusion,
private-form lifecycle clearing, unavailable-template behavior, narrow-screen
behavior, and unsupported browser capability errors.

An opted-in UI job must have a successful process exit, a nonempty TAP summary,
all tests passing, and zero failures, cancellations, skips or TODOs. Missing
Python dependencies, unavailable Chromium, template validation failures and
timeouts fail the job. A manually disabled job is not counted as passed browser
qualification.

## Private-output boundary

The new core and browser jobs have **no artifact upload or cache-save step**.
The browser suite writes disposable encrypted identity backups, screenshots
(including private-form screens), generated synthetic pairs and AABs only to
fresh runner scratch. Raw UI TAP diagnostics also stay there; CI prints only
numeric test totals. Do not add a wildcard upload of scratch, the checkout,
`qualification-output`, downloads, logs or screenshots—even encrypted test
backups contain signing identities. No real user keys or CarPlay files are
accepted by this workflow.

The unchanged called desktop workflow still uploads its existing allowlisted
credential-free template inputs, sanitized result/failure reports, and
credential-free disposable signed AABs. This is not a claim that the combined
run produces no artifacts. No browser screenshots, backups, synthetic
CarPlay-bearing AABs or private diagnostics are added to those artifacts.

The checkout action SHA is reused from `build-builder-image.yml`. Existing
workflows have no SHA pins for setup-node, setup-python or download-artifact;
the new workflow pins their upstream v4/v5/v4 commits, respectively. Actions in
the called desktop workflow retain their existing version tags; this additive
change does not claim to have hardened or repinned that workflow.

## Evidence and remaining gates

A hosted matrix declaration is not a native-platform pass. Record actual run
URL, checkout SHA, template source tuple and each job conclusion after running
Actions. This change was validated locally with actionlint 1.7.12, the Node 22
core suite/build, and the embedded server/UI runner against a validated retained
local-template input using an already installed Linux Chromium. Local checking
did not install packages, run the producer, download the CI-pinned browser, or
execute hosted Windows/macOS jobs. It is not evidence that those jobs have
already passed.

Remaining independent gates:

- Hosted Ubuntu, Windows, Intel Mac and ARM Mac results for the exact integrated
  commit, with the workflow-installed Playwright Chromium and freshly produced
  template. Native failures must be fixed, not hidden with allowed failures.
- Firefox and WebKit acceptance: first parameterize the actual browser test
  harness, then execute those engines. Chromium jobs cannot establish this.
- Independent Java/bundletool verification of the **browser-downloaded** AABs
  (`tests/ui-flow-verify.mjs` with explicitly prepared tools); the called desktop
  jobs verify desktop output, not these browser downloads.
- Live latest published-template download, mirror/CORS delivery and public
  hosting behavior. These UI tests intercept metadata and template transport;
  they qualify local-template processing, not publication or production CORS.
- Installed app updates, Play acceptance, vehicle behavior and authorized
  iPhone/CarPlay compatibility. Synthetic test credentials prove none of these.

For existing standalone local UI-test instructions, see
[`tests/ui-flow-README.md`](../tests/ui-flow-README.md). Never point those test
helpers at a user's signing identity or private backup.
