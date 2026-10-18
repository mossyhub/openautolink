# Browser AAB personalization core (feasibility boundary)

## API

```js
import { transformAabEntries } from './core/transform.mjs';
const personalized = transformAabEntries(entries, {
  applicationId: 'com.example.personal',
  versionCode: 42,
  versionName: '0.1.528', // optional ASSERTION; must match template, never rewritten
});
```

Synchronous ES modules, no runtime dependencies, Node imports, filesystem, network,
keys or global mutable transformation state. `entries` and the result are arrays
of `{ name: string, data: Uint8Array }`. Order and member names are retained.
Inputs are not modified. Unchanged payload arrays are shared with the input;
callers must not mutate them concurrently. New manifest/resources arrays are
returned. Errors contain constant `OAL_PERSONALIZE_*` codes, not input values.
Use a Worker for large bundles; synchronous scanning otherwise blocks the UI.

`versionName` is intentionally **preserve-only**: the reviewed desktop Java helper
changes versionCode, not versionName. Omitting it preserves the template name;
supplying a different name throws `OAL_PERSONALIZE_VERSION_NAME` rather than
silently ignoring it or adding an unreviewed transformation.

## Exact transformations

Source application ID is fixed to `com.openautolink.template` (the desktop release
contract). Only these six manifest attributes change, along with their existing
compiled string/raw-string or decimal representations:

- `/manifest@package`
- `/manifest@versionCode`
- `/manifest/permission@name` for `.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
- `/manifest/uses-permission@name` for the same permission
- `/manifest/application/activity@taskAffinity` for `.templates`, only on
  `androidx.car.app.activity.CarAppActivity`
- `/manifest/application/provider@authorities` for `.androidx-startup`, only on
  `androidx.startup.InitializationProvider`

The resources table must contain exactly one package, ID 127, with that source
name. Only its `package_name` is replaced. Every other resource payload is opaque.

The wire editor preserves original field order and untouched tags/lengths/values,
including unknown varint, fixed32, fixed64 and length-delimited fields. Changed
identity values and enclosing message lengths are the only rewritten wire bytes.
Unedited nested messages keep even noncanonical length encodings. Deprecated
protobuf groups are rejected, not discarded. Duplicate known singular fields,
competing oneof alternatives and wrong wire types in inspected fields fail
closed rather than applying protobuf last-one-wins/merge behavior.

## Validation and deliberate differences

Entry shape, ASCII paths, aliases, prefix spelling, Windows device names,
base-only module layout, DEX/native names, required members, expanded-size budgets,
credential filenames, and baked template IDs in UTF-8 / UTF-16LE / UTF-16BE follow
`desktop-builder/oal_builder/personalize.py`. The manifest element/attribute
vocabulary and framework resource IDs, compiled/raw agreement, routing/class
rules, permission strength, booleans, SDK values and exactly-six-edit count follow
`desktop-builder/oal_builder/resources/ProtoPersonalizer.java`.

- Rejects top-level JAR signatures instead of stripping them. This is deliberately
  stricter than `personalize_bundle`; only unsigned release templates are accepted.
- No optional CarPlay credential insertion. Known credential filenames and
  `offline-mfi` paths reject the template. This is NOT arbitrary secret detection.
- Known protobuf duplicates/oneof ambiguity and some noncanonical numeric inputs
  are rejected more strictly than Java's merge/truncation semantics.
- One protobuf message is capped at 200,000 wire fields. Protobuf member input size
  is capped at 32 MiB, each entry at 512 MiB, aggregate at 2 GiB, entries at 10,000.
  These are input validation ceilings, not a promise of browser memory capacity.

## Unclosed production boundaries

This accepts **expanded entries**, not a ZIP. Raw central/local header consistency,
ZIP encryption flags (including strong encryption), file modes/symlinks, CRC,
compressed/expanded size agreement, compression-ratio checks, archive-size limits,
and duplicate detection **before** an unzip library collapses names belong to the
ZIP reader. Entry validation cannot recover discarded raw ZIP metadata. In
particular, inspect the separate archive implementation before claiming desktop
ZIP-validation parity; the core does not supply it.

This is a selective protobuf parser, not generated AAPT bindings. It structurally
validates inspected messages but does not schema-validate every opaque resource
Type/Entry/Value, source position, namespace declaration, reference, BundleConfig,
DEX, ELF or XML resource. Malformed opaque nested messages can therefore pass the
core and still fail bundletool. Unknown fields are retained, not understood.

Publisher provenance, descriptor/hash binding, template freshness, monotonic
version reservation, persistent identity, signing, optional credential validation,
output ZIP safety and full bundletool verification remain separate caller gates.
No Play upload, car installation/update, browser memory-stress or platform matrix
qualification is established here. This is not a production-ready declaration.

## Tests

```sh
node --test web-builder/tests/transform*.test.mjs
```

Tests were developed via observed RED/GREEN cycles. Tests cover exact synthetic
wire output/unknown bytes, non-mutation, entry policy, manifest/resource semantics,
oneof ambiguity, malformed protobuf and Java-compatible whitespace handling.

Optional local-artifact check (no signing or key access):

```sh
OAL_WEB_TEMPLATE=/path/to/unsigned-credential-free-template.aab \
OAL_WEB_TOOLS=/path/to/managed-tools \
node --test web-builder/tests/transform-real.test.mjs
```

The tools directory must contain `jdk-17/bin/java`, `jdk-17/bin/javac`, and
`bundletool-1.18.3.jar`. The test uses the separate archive module, invokes the
existing desktop Java source read-only, compares **exact output protobuf bytes**,
hashes every unchanged payload, rebuilds an unsigned AAB and runs bundletool
`validate`. Temporary files under `tests/transform-real-*` are cleaned up.

Verified retained local template:
`dfa4ded58b0bd12b9f0cec1477983d15d15b36568d5d88715019ef869461eab1`.
Result: 223 entries, exactly 2 changed, 221 payloads unchanged (11 DEX/native
members included), exact Java protobuf parity, bundletool validation passed.
All 87 tests passed with this opt-in check enabled. This is local-artifact evidence,
not a live release download or provenance proof.
