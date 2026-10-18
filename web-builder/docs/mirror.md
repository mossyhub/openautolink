# Prepare the public template mirror (release operator only)

`scripts/mirror-template.mjs` is a Node preparation tool, **not a server, upload
endpoint, deployment command, template compiler or personal AAB builder**. It
requires no signing key, CarPlay files, GitHub token or owner-private inputs.
It only reads anonymous public GitHub release metadata and the exact public
release descriptor/AAB belonging to `mossyhub/openautolink`.

The browser cannot directly fetch GitHub release downloads reliably because of
CORS. This tool prepares the original published bytes for the browser transport's
same-origin paths:

```text
<output-dir>/templates/<resolved-latest-tag>/openautolink-personalization.json
<output-dir>/templates/<resolved-latest-tag>/openautolink-template-<resolved-latest-tag>.aab
```

## CLI

Run from `web-builder`, using Node 22 and the project's installed dependencies:

```sh
node scripts/mirror-template.mjs --help

# Resolve current public latest and fully validate its public template if present.
# Read-only: does not create any directories/files.
node scripts/mirror-template.mjs --check

# Prepare a dedicated staging static root; does NOT publish or deploy it.
# Use an absolute path in a trusted, operator-owned directory.
node scripts/mirror-template.mjs --output-dir /absolute/release-work/static-root
```

`--check` and `--output-dir` are mutually exclusive. There are no repository, tag,
URL, local-template, credential, token, force-overwrite or upload CLI options.
Unknown/duplicate options fail. Exit code is `0` on success (including an already
identical mirror), `1` on any validation, availability or filesystem failure.
Failures emit sanitized JSON to stderr; successful checks/preparations emit one
sanitized provenance JSON object to stdout. No report is written automatically
under the static root. To retain one, redirect stdout **outside every served web
root**, for example:

```sh
node scripts/mirror-template.mjs --output-dir /absolute/release-work/static-root \
  > /absolute/release-work/mirror-provenance.json
```

Do not serve `/absolute/release-work` itself. Only `static-root` is intended as a
future web root. Report fields are `status` (`checked`, `prepared`, or
`already-identical`), `sourceRef`, `sourceCommit`, `descriptorSha256`,
`templateSha256`, `transport`, and `validation`. Reports never contain signed
redirect URLs/query strings, response headers/bodies, local key paths, or keys.
Do not add verbose HTTP/URL debugging to this tool.

No npm script or CI/deployment workflow is installed by this change. An operator
must separately review any publication step, serve the two files at the same
HTTPS origin as the wizard, and read back/verify their bytes after deployment.
The browser still resolves current latest and independently verifies the mirrored
bytes. An old mirror is not a fallback when latest changes.

## Node API

```js
import {prepareMirror} from './scripts/mirror-template.mjs';

const checked = await prepareMirror({checkOnly: true});
const prepared = await prepareMirror({
  outputDir: '/absolute/release-work/static-root',
});
```

Supported options:

- `outputDir`: required absolute, non-root directory when preparing files. There
  is no implicit current-directory output. Must not traverse symlinks.
- `checkOnly`: boolean, default `false`; when `true`, omit `outputDir` entirely.
- `timeoutMs`: integer `1..120000`, default `120000`; one abort deadline covers
  the asynchronous fetch/stream sequence rather than resetting per redirect.
  Synchronous archive/transform work and local filesystem operations cannot be
  forcibly interrupted by a JavaScript timer; archive size/expansion bounds still
  apply. Use an external process limit for hard CPU/RSS/wall-time isolation.
- `fetchImpl`: fetch-compatible **test seam**, default native `globalThis.fetch`.
  Tests must honor manual redirects and stream bodies. An injected transport is
  always labeled `injected-fixture-not-public-proof`, never `public-github`.
  This is not an HTTP API or a user-configurable URL/local-file source.

The API throws on failure and never returns an unvalidated bundle. It does not
accept or return personal identities, signing keys or transformed AABs.

## Verification and transport contract

1. Reuse `core/releases.mjs` to resolve `/releases/latest` on every operation,
   require a public stable `vMAJOR.MINOR.PATCH`, and dereference its exact tag to
   an immutable 40-hex source commit (including bounded annotated tag chains).
   No moving branch, `target_commitish`, older release, local fixture, signed APK,
   source compilation or alternate repository can substitute for latest.
2. GitHub API metadata requests go only to the fixed repository endpoints formed
   by the core resolver. API redirects are rejected. Metadata is strict UTF-8,
   duplicate-key-free JSON bounded to 1 MiB with bounded nesting.
3. Initial asset URLs must exactly equal the core-validated
   `https://github.com/mossyhub/openautolink/releases/download/TAG/NAME` URLs.
   Manually follow at most five redirects per asset, HTTPS only, without userinfo,
   non-default ports or fragments. Redirect hosts are exactly `github.com`,
   `release-assets.githubusercontent.com`, and `objects.githubusercontent.com`.
   A `github.com` redirect must be the identical initial asset URL, not another
   repository/path/query. No suffix matching or API-supplied arbitrary object
   URLs. Signed queries are used only on allowlisted asset hosts and never logged.
4. Every request is `GET`, `credentials: omit`, `redirect: manual`, `cache:
   no-store`, with no referrer, authorization, cookies or token lookup. API
   requests use GitHub JSON `Accept`; assets use octet-stream. Request identity
   content encoding and reject unexpected encoded responses, automatic redirects,
   non-200 final responses and malformed/mismatched `Content-Length`.
5. Stream into fixed bounded buffers, cancel on overflow/failure, and reject
   truncation. Descriptor raw bytes must exactly match the release asset size
   (at most 64 KiB) **and mandatory published GitHub SHA-256** before parsing.
   Reuse `core/strict-json.mjs` to reject duplicate keys, malformed UTF-8/JSON and
   excess nesting. Never trust a parsed descriptor without its original bytes.
6. Reuse `validateDescriptor` to require the exact schema, format, identity
   contract, release tag, source commit, template name, size and SHA-256. Match
   GitHub's optional AAB digest when present. Reuse `verifyTemplateBytes` to hash
   the actual exact-size AAB, at most 512 MiB.
7. Reuse `core/archive.mjs` to check ZIP headers, CRC, expansion limits, entry
   names/types and collisions. Reuse `transformAabEntries` for unsigned,
   credential-free **structure** and supported protobuf semantics. This rejects
   root signature records, known private-key/container extensions and `offline-mfi`
   paths. Perform an in-memory dry-run with a fresh random dummy application ID,
   version code `1`, and the resolved release version name. Discard that result;
   the mirrored descriptor and AAB remain byte-for-byte original upstream assets.

Repository ownership plus HTTPS is the trust root; these are not publisher
signatures or reproducible-build attestations. Structural checks cannot prove
that arbitrary opaque binaries hide no secrets. Only the public credential-free
upstream template is acceptable. This preparation does not establish Android
installation, Play acceptance or iPhone/CarPlay operation.

## Atomic output and overwrite policy

All public downloads, hashes and structure/dry-run checks finish before output
creation. Under a sibling lock, the tool checks the destination and writes both
files into a same-filesystem sibling staging directory outside `outputDir`.
One directory rename makes the complete tag directory visible. It then reads
back both exact output files before returning `prepared`.

An existing tag directory must contain exactly the two expected regular files,
with identical sizes **and bytes**. Identical output returns `already-identical`;
changed bytes, extra files, missing files, symlink members/ancestors or invalid
file types fail closed. It never repairs a partial directory or overwrites a
mismatch. Other tags are not removed or used as fallback.

Use a trusted, operator-owned local directory. The sibling lock serializes this
tool's cooperating invocations, not hostile filesystem writers; atomic rename
is visibility atomicity, not a power-loss durability guarantee. Staging and lock
paths are removed on normal success/failure. A killed process may leave a sibling
`.oal-mirror-lock-*` or `.oal-mirror-stage-*`; investigate and confirm that no
preparation remains active before removing stale paths. Never bypass a mismatch
by deleting published bytes blindly. Use a fresh preparation root instead.

## Tests and actual qualification

```sh
node --test tests/mirror-template.test.mjs

# Explicit opt-in LOCAL sanitized v0.1.528 integration, not a public download.
# These environment variables are consumed ONLY by the test, never by the CLI.
OAL_MIRROR_LOCAL_TEMPLATE=/absolute/sanitized/openautolink-template-v0.1.528.aab \
OAL_MIRROR_LOCAL_DESCRIPTOR=/absolute/sanitized/openautolink-personalization.json \
  node --test tests/mirror-template.test.mjs
```

Tests use injected synthetic public-release transports, real ZIP parsing and real
transformation; no private credentials or key generation. They cover redirect
allowlists/loops, malicious initial hosts/userinfo, omitted credentials, byte
bounds/truncation, hanging downloads, raw hashes, duplicate JSON, source tuple,
unsigned/credential-free structure, unsupported transformation, output mismatch,
symlinks, idempotency, and CLI input rejection. Optional local integration stages
real retained sanitized bytes into temporary output and removes it afterwards.
It is explicitly labeled local/injected and cannot publish or relabel old code as
public latest.

Actual execution using Node `v22.23.3`:

- With the opt-in retained local sanitized template: **56 tests passed, zero
  failures or skips**. Source `v0.1.528`, commit
  `09c75422b5f0529b7b521c628eefffe9a75273d7`, template SHA-256
  `dfa4ded58b0bd12b9f0cec1477983d15d15b36568d5d88715019ef869461eab1`.
  This is local integration evidence only.
- The live anonymous read-only `--check` exited **1**, intentionally, with:

```json
{"status":"error","message":"Latest public template unavailable: v0.1.529 (311bcd4f4d841af218dc6dcb7f7eefa89ec968aa); missing openautolink-personalization.json, openautolink-template-v0.1.529.aab; no older/local fallback"}
```

No public template was downloaded or mirrored for that latest release, and
nothing was published or deployed. Re-run `--check` rather than treating this
recorded availability observation as a permanent fact.
