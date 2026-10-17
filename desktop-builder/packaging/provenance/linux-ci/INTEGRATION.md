# Ubuntu CI integration boundary

The Ubuntu inventory introduces composite permissive grants not in the generic
schema-2 allowlist. Do not rename these to MIT, use Debian version-specific
LicenseRefs, monkeypatch the validator, or drop their notices merely to pass.
The Linux agent owns no edits to build.py. Parent integration must add narrow,
exact-notice-bound scoped routes for the entries recorded in
`license-policy.json`, analogous to build.py's existing `scoped_notices`, and
require the nonempty `license_scope` field. These routes are notice-only; they
do not waive source for copyleft components. The generator continues to call
the unchanged generic validator, and therefore fails closed until that policy
extension is integrated. This is a mechanical policy limitation, not missing
Ubuntu source acquisition.

No native Ubuntu rebuild, runner launch, or replacement qualification is
claimed by local source/evidence tests. The next native freeze must be compared
again to the complete exact payload map before coverage is accepted.

## CLI (from desktop-builder)

After integrating the six exact notice routes into the shared gate:

```sh
python packaging/coverage_linux_ci.py \
  --cache "$RUNNER_TEMP/oal-linux-ci-sources" \
  --output "$RUNNER_TEMP/oal-linux-ci-coverage"
python packaging/build.py \
  --native-coverage "$RUNNER_TEMP/oal-linux-ci-coverage/coverage.json"
```

The generator defaults to the committed exact payload map; the packager must
still audit and match the newly frozen executable independently. To replay
against a diagnostic artifact instead, add `--inventory /path/linux-x86_64.json`.
A standalone build inventory (`{"payload": [...]}`) is also accepted. Use
`--offline` only with a previously acquired cache. Use a fresh output directory;
any mismatch fails before acquisition, and any evidence/policy failure leaves
no output manifest. The generic APIs are called unchanged, not monkeypatched.

## Local verification record

* 141 unique payload entries mapped; 197 components including conservative
  static closure, 27 acquisition inputs, 18 corresponding-source archives.
* All source inputs downloaded/cached and hash-checked; Ubuntu source-member
  digests match retained exact-version `.dsc` descriptors.
* Full actual-discovery CLI replay reaches shared policy rejection on
  `LicenseRef-Ubuntu-libX11-1.7.5`; no release manifest emitted.
* A diagnostic-only supported subset passes the unchanged generic gate for
  131 entries. The remaining 10 entries are exactly the six scoped routes;
  a subset result is not release coverage.
* Combined local suite: 336 passed, 4 skipped, using existing private Qt
  libraries and `QT_QPA_PLATFORM=offscreen`. No host installation/execution,
  commit, push, CI publication or native-runner qualification was performed.
