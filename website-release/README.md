# Durable public template → existing Azure website refresh

This directory contains **generic public deployment tooling only**. It must never
contain the private website source. There is no website build, source checkout,
Azure login, token retrieval, infrastructure creation, DNS or billing operation.

## One-time operator setup

1. Privately build/review the browser implementation supporting the contract below.
   Package **only** its compiled `index.html`, `assets/*`, `brand/oal-logo.png` and
   `staticwebapp.config.json` as a root-relative ZIP. No wrapper directory,
   directory entries, source maps, source, templates, reports, `.env` or credentials.
   Use an immutable versioned filename such as `shell-<reviewed-build-id>.zip`.
2. Generate the checked-in exact archive and per-file pins from that reviewed ZIP:

   ```sh
   python3 -I -S website-release/refresh.py pin-runtime \
     --archive /trusted/path/shell-<reviewed-build-id>.zip
   ```

   This validates the bounded compiled-only archive and updates
   `website-release/runtime.json`; it does not upload anything. The initial null
   registry is intentionally disabled and fails closed. Review the resulting diff.
3. Publish the ZIP as an additional public asset of tag `web-builder-runtime` in
   `mossyhub/openautolink`. **That release MUST be a public prerelease**, so it cannot
   become latest Android release. Never overwrite an old asset; use a new filename
   and reviewed registry update. The pipeline checks GitHub's archive size/digest,
   release/asset IDs and every extracted file's exact size/digest.
4. Set repository secret `OAL_WEBSITE_DEPLOYMENT_TOKEN` to the deployment token for
   **only** the existing app/resource group `oal-web-preview`, subscription
   `de1ebbeb-11f8-4375-bb7f-182f7d30c6a2`. It is exposed only to the upload step in
   the deploy job, not validation, preparation, npm installation or readback.
   This tooling does not retrieve, print or place the token in command arguments.
5. Review/merge through the normal repository process. The workflow must exist on
   the default branch. It responds to successful own-repository completion of
   `Publish personalization template` and supports manual dispatch. It does not
   execute upstream workflow artifacts or check out an event-supplied commit.
6. The initial site must already serve a valid `/templates/current.json` using
   this contract (the parent operator's initial reviewed deployment provides it).
   A missing/malformed manifest blocks automatic upload; there is no silent
   bootstrap bypass that could overwrite an unknown/newer served release.

## Browser / publisher contract

`/templates/current.json` is strict UTF-8 JSON, with exactly:

- `schemaVersion`: integer `1`;
- `sourceRef`: stable `vMAJOR.MINOR.PATCH`;
- `sourceCommit`: independently peeled 40-lowercase-hex commit;
- `assets`: exactly two records, descriptor first, then exact-tag template. Each
  has exactly `name`, positive integer `size`, required `digest` (`sha256:<64 hex>`),
  and canonical `browser_download_url` GitHub provenance identifier.

The descriptor is `openautolink-personalization.json` (at most 65536 bytes); the
AAB is `openautolink-template-<sourceRef>.aab` (at most 512 MiB). Their original
verified bytes are served at `/templates/<sourceRef>/<name>`. GitHub URLs in the
manifest are provenance only, not browser fetch targets. The browser uses only
same-origin requests. This shape matches the authoritative browser implementer's
`site-release-manifest-contract.json`; no private browser implementation is copied.
The manifest represents the published builder version, **not a claim that the
browser queried latest upstream**.

## Gates and ordering

- An authenticated read-only `github.token` is used only on fixed repository API
  URLs. Asset downloads and HTTPS site readbacks never receive it. API/site
  redirects are rejected; bounded asset redirects use exact GitHub/CDN hostnames,
  HTTPS only, no userinfo/nondefault ports/fragments, and no forwarded auth.
- Resolve all releases with bounded pagination, excluding drafts/prereleases.
  Choose the greatest stable semantic version having both exact named assets.
  A newer incomplete release does not displace the last complete eligible release;
  a complete selected release with missing/invalid digests **fails**, without
  retrying an older release. Never use moving `target_commitish` as commit proof.
- Require published SHA-256 for both assets, exact sizes, strict raw descriptor
  hash/JSON/schema/tag/commit/template agreement and exact AAB checksum.
- The existing production Python personalization validator rejects unsafe,
  credential-bearing and signed archives, validates via pinned bundletool 1.18.3,
  and performs the actual Java protobuf transformation dry-run. The probe output
  is discarded. The deployed template remains byte-for-byte original.
- Download the pinned compiled shell (at most 32 MiB), verify its exact digest,
  then validate every ZIP member before writes: exact registry, safe names/types,
  CRC, sizes/digests, no duplicates/symlinks/source maps and bounded expansion.
  Only after validation inject original descriptor/AAB plus current manifest.
- Keep provenance/inventory outside `dist`. Revalidate copied bytes and record
  SHA-256/size of every upload file. No source or old template directory is added.
- Immediately before upload, re-resolve latest complete release and runtime proof,
  compare them with preparation and rehash the snapshot. Read the current site's
  strict manifest: a higher served version or conflicting same-tag commit/digests
  blocks upload. Missing metadata also fails closed.
- Global workflow concurrency, shared across manual/release invocations, never
  cancels an active run. GitHub may coalesce pending invocations; each survivor
  resolves the latest complete eligible release afresh. Do not run another manual
  uploader concurrently outside this concurrency group.
- Deploy with pinned SWA CLI **2.0.10**, `--env production` on the existing preview
  resource only, no keychain, no Azure login. The slot name does not establish
  production-offer/billing eligibility; that remains an owner decision.
- Rehash local snapshot after upload, then anonymously read back and hash every
  public file with bounded retries, including current manifest/descriptor/AAB.
  Azure consumes `staticwebapp.config.json`; it is verified in the upload inventory
  and explicitly marked not HTTP-readable. HTTP hashes do not prove all routing
  semantics. Save the verified report only after successful readback.

There is an unavoidable point-in-time race if a new release arrives after the
final upstream check; the next queued run resolves it. Failures never automatically
roll back. An upload/readback failure may already have changed Azure and must be
investigated, not reported as success. Repository ownership + HTTPS + pinned
hashes are the trust roots, not publisher signatures or GitHub immutable-release
attestations. No Android installation, Play or CarPlay qualification is claimed.

## Tests (no credentials, builds or deployments)

```sh
python3 -I -S -m unittest discover -s website-release/tests -v
python3 -m unittest discover -s .github/workflows/tests -v
```

Synthetic tests cover the full prepare → recheck → readback path, safe ZIP and
registry boundaries, raw hashing, strict manifest, unsigned validation, transport
credential isolation, anti-rollback, immutable proof drift and workflow security.
Fixtures are explicitly not public-release or Azure-deployment evidence. CI runs
these tests in a secret-free job on pushes/PRs. The refresh workflow itself has no
pull-request trigger and fetches no untrusted workflow artifacts.
