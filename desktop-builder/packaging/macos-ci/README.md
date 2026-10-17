# Mac CI coverage materializer

From `desktop-builder`, using Python 3.12 and the builder dependencies:

```sh
python packaging/coverage_macos_ci.py \
  --architecture arm64 --inventory /path/to/macos-arm64.json \
  --cache /private/evidence-cache --output /private/arm64-coverage --fetch
python packaging/coverage_macos_ci.py \
  --architecture x86_64 --inventory /path/to/macos-x86_64.json \
  --cache /private/evidence-cache --output /private/x86_64-coverage
```

`--fetch` uses the OS-specific pinned lock and the existing `prepare_native`
API. Add `--offline` to verify a populated cache without network. Only Qt Base
and PySide source archives are required in the cache for materialization without
`--fetch`; their notice bundles and other component notices are checked in.
Use a new output directory each time. The module uses `generate_native.generate`
for atomic staging and `build.coverage_asset` for exact asset validation.

The input may be the original diagnostic discovery document (its file digest,
identity, source/artifact hashes and payload are pinned) or a fresh packager
inventory with top-level `payload`. The latter still must match every reviewed
native path/hash/kind/size/target/hash_scope exactly. It does not update a review.
`--review` and `--base` allow a deliberately reviewed alternative; neither option
permits changed bytes to be silently adopted. This is not a manifest generator
from arbitrary filenames, installed package versions or wheel hashes.

Cross-materialization is supported: a private instance of the generic validator
is given the explicit target metadata without changing the host platform module.
This verifies assets and mapping on Linux but does not execute Mac native code.
The output status is intentionally `materialized-not-native-qualified`.

## Parent integration (shared files intentionally untouched)

* Pass each architecture's `coverage.json` into the native packager and keep
  both complete runtime directories in the single architecture-selecting DMG.
* The generic build policy currently does not know
  `LicenseRef-XZ-5.2.3-Public-Domain`. The OS module's `validate_xz_component`
  provides the exact notice hash, version, component identity and liblzma-only
  scope check. Materialization validates that extension separately, then uses
  the unchanged generic validator for every other component and all assets.
  The output retains the **original** XZ term, not a fabricated SPDX substitute.
  Parent must integrate this additive scoped policy into the final build gate;
  until then `build.py` correctly rejects the unsupported term. Do not loosen
  generic unknown-license rejection or assign GPL terms from XZ's CLI tools to
  embedded public-domain liblzma.
* Keep the final-byte gate. Mac bootloader load commands/signatures may change
  when the compliance archive changes. These manifests do not mask that drift.
  Never replace the pinned hash with the current hash without a new review.
* Native final freeze, replacement/recombination, UI launch, both-architecture
  DMG dispatch and signing acceptance remain outstanding. See REVIEW.md and
  REBUILD.md; materialization success is not release approval.

The evidence-acquisition scripts used for inspection stayed in scratch storage;
no supplier installer or source build was executed on this host. Suppliers were
fetched and inspected as data. The installer/member observations are retained
under `provenance/macos-ci/`, separately from frozen payload identities.
