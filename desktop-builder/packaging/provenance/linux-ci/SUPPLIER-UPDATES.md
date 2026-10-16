# Narrow supplier update: CI run 38953744570

The third Linux discovery (checkout
`656b4b2556b82748cec2e29c97d0a4b762acd6ec`, attempt 1) differs from the
previous approved payload at exactly four records. No entry was added or removed;
all kinds, sizes and symlink targets are unchanged. Discovery SHA-256:
`654031e73e9e799604ac9c5cbe11a3c40499e1bc1a0d127b9ceef6b4dbe37b9e`.

| Ubuntu amd64 binary package | Previous version | Observed and verified version |
| --- | --- | --- |
| libx11-xcb1 | 2:1.7.5-1ubuntu0.3 | 2:1.7.5-1ubuntu0.4 |
| libx11-6 | 2:1.7.5-1ubuntu0.3 | 2:1.7.5-1ubuntu0.4 |
| libfreetype6 | 2.11.1+dfsg-1ubuntu0.3 | 2.11.1+dfsg-1ubuntu0.4 |
| libpng16-16 | 1.6.37-3ubuntu0.6 | 1.6.37-3ubuntu0.7 |

`sourcePackage`/`sourceVersion` in the discovery agree with the official packages'
control metadata. `supplier-updates-run38953744570.json` records the official
Ubuntu HTTPS URLs, downloaded archive hashes/sizes, resolved regular members,
SONAME symlink chains, old/new native hashes, and unchanged copyright hashes.
The four `.deb` files were downloaded and read with `dpkg-deb`; no package was
installed and no maintainer script or supplier binary was executed. Each native
member matches the failed CI payload by SHA-256 **and size**. Each copyright file
matches both the discovery text and the already-retained notice byte-for-byte.
This is HTTPS supplier retrieval and byte correspondence, not a new OpenPGP or
reproducible-build attestation.

Only these four component versions/provenance/payload digests are advanced. Their
existing license choices, notice-bound scopes, notices and static edges remain;
the update evidence is additionally embedded as a hash-bound provenance asset.
The other 193 components, including all source obligations, are unchanged.
The source lock is byte-identical; its hash and the canonical hash of the unchanged
components are retained in the update evidence and regression-tested. All 18
previous corresponding-source archives remain in the materialized output. The
updated permissive library mappings do not remove source obligations from other
components. No supplier copyright bytes or policy routes were changed.

## Reproduce the official-package verification

On Linux with `dpkg-deb`, download the four `archive.url` values from the update
JSON into a private directory, retaining each URL's basename. The artifact test
checks the archive hashes before reading package metadata, symlinks, native
members and notices:

```sh
# From desktop-builder; the cache must contain actual downloaded .deb files.
OAL_LINUX_SUPPLIER_CACHE=/path/to/downloaded-debs \
  python -m pytest -q tests/test_linux_ci_coverage.py
python packaging/coverage_linux_ci.py \
  --inventory /path/to/linux-third.json \
  --cache /path/to/previously-verified-source-cache \
  --output /path/to/new-coverage-directory --offline
```

Without the environment variable, only the optional real-package replay skips;
all structural, exact-mapping, unchanged-obligation and drift-rejection tests run.
The actual four-package replay passed (14 tests). The complete actual-discovery
materialization passed the shared generic validator: 141 payload records,
197 components, 153 distinct assets including 18 corresponding-source archives.
The emitted manifest SHA-256 was
`ee8431950d275fb083484044f380d7b1d8ebc294fe1886126948ed23d458f50f`.
All materialized assets were independently rehashed afterward. This establishes
coverage materialization, **not** a successful new native freeze or GUI launch.

## Reproducibility recommendation for the workflow owner

This was Ubuntu package-version drift, not wheel or CPython-byte drift. Avoid an
unversioned apt install choosing the next runner-image/security-pocket revision.
For these four packages, request exactly:

```text
libx11-xcb1:amd64=2:1.7.5-1ubuntu0.4
libx11-6:amd64=2:1.7.5-1ubuntu0.4
libfreetype6:amd64=2.11.1+dfsg-1ubuntu0.4
libpng16-16:amd64=1.6.37-3ubuntu0.7
```

Prefer the exact HTTPS `.deb` URLs **and SHA-256 pins** already recorded in the
update evidence, or an immutable Ubuntu snapshot containing these versions;
ordinary archive retention and apt version strings alone are not a complete
reproducibility guarantee. Check installed versions and native-member hashes
before freezing. Install both X11 packages together: the xcb package has an exact
version dependency on libx11-6. Keep the complete final payload gate for every
other dependency; these four pins do not pin the entire runner image. Fail if a
pin becomes unavailable rather than silently accepting newer bytes. Refresh pins
only after another explicit supplier review. No workflow or host changes were
made for this update.
