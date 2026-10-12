# CarPlay automatic resolution handoff

## Current presentation contract

This earlier handoff describes the original Automatic/Manual implementation. The
new [window fill and manual safe-area contract](carplay-window-safe-area-contract.md)
adds an independent opt-in Fit to window flag for standard selected detail tiers;
Custom and geometry-named presets remain exact, and older saved records remain
aspect-fitted until explicitly opted in. The chrome-free parent now owns next-session
measurement, independently of the fitted video Surface. Safe control calibration
is CarPlay-only and does not shrink video or HID space. Automatic resolution,
physical UI sizing, codec preference and maximum FPS remain separate.


## Implemented

- Independent persisted `CarPlaySettings.autoResolution` Automatic/Manual selector in Video. Default remains Manual for migration compatibility; H.264 codec default unchanged. Manual resolution/custom dimensions/FPS and physical-size Auto/manual calibration are not overwritten by switching policy.
- Codec label is `Automatic (H.264 + qualified HEVC)` versus `H.264`, retaining existing `hevc`/`h264` storage IDs. Automatic codec is an optional offer, not a HEVC-only demand. Parent reported physical HEVC render/touch success on its phone/TCL after the SETUP echo-gate correction; this worker neither changed that gate nor performed device QA.
- `CarPlayVideoQualification.selectAutomatic` locally ranks 1440-class pixel-area geometry at actual measured viewport aspect, then 1080-class, then 720-class. Each tier tries even, 8- and 16-aligned exact geometries (deduplicated; at most nine candidates), bounded to 4096 per axis and two-percent aspect error. This favors 1440 detail even when Android app layout pixels hide native panel detail, rather than defaulting to 1080 or indiscriminate 4K. Area/axis bounds are product policy, not Apple capability claims. Portrait and wide surfaces supported; unusable/extreme aspect falls back.
- AVC exact size/rate/format/alignment qualification is mandatory; HEVC is queried only at the selected AVC-safe tuple. Failed exact probes are cached, including null, so a mutable second query cannot promote an initially unqualified baseline. Safe fallback is exactly AVC 1280x720@30; unavailable baseline fails closed. No fake display rows/max-pixel fields or second stream.
- Next-session bootstrap waits for the existing display-mode-qualified measured Surface receipt whether resolution Auto or physical-size Auto is enabled. Selection is frozen before runtime construction into `display.qualifiedVideoSelection`. Callbacks only update next-session receipt; no new reconnect/recomposition effects. Auto initially lays out the full available Surface rather than allowing saved manual aspect to trap initial measurement; safe fallback retains aspect-fit.
- Status reports selected geometry, maximum FPS, optional codec offer and finite selection reason: `automatic_1440_class`, `automatic_lower_qualified`, `automatic_viewport_unavailable_baseline`, `automatic_unqualified_baseline`.

## REQUIRED parent integration hook (exclusive receiver ownership respected)

**This worker did NOT edit `OalCarPlayRuntime.kt` or `ProjectionReceiver.kt`.** Runtime already forwards `config.display`, including the new receipt. In `ProjectionReceiver.kt`, replace its private `videoSelection` initialization:

```kotlin
private val videoSelection = CarPlayDisplayPolicy.videoSelection(display)
```

instead of separately calling `CarPlayVideoQualification.select(display.width, display.height, ...)`. The existing displaySelection, `/info`, session and sink forwarding remain unchanged. The helper validates exact receipt dimensions/FPS/codec preference and returns the *same instance* without inventory reconstruction; legacy callers without receipt still qualify normally. Until this one-line parent hook lands, the receiver independently re-probes and cannot claim single immutable receipt authority. Existing lexical receiver tests expecting the old call need parent-owned updates.

## Tests and execution

Host commands use `ssh -F /home/hermeswebui/.hermes/home/.ssh/config lance-host`, mirror `/mnt/Temp/Hermes_Work/oal-carplay-integration`, JDK17 `/home/lance/.local/jdks/temurin-17`, SDK `/home/lance/.local/android-sdk`, and shared lock `/mnt/Temp/Hermes_Work/.oal-gradle.lock`:

```sh
flock /mnt/Temp/Hermes_Work/.oal-gradle.lock ./gradlew \
  :app:testTabletDebugUnitTest --tests '*CarPlay*' --tests '*SaveReconnect*' \
  --max-workers=1 --no-daemon \
  -Dorg.gradle.jvmargs='-Xmx1536m -XX:ActiveProcessorCount=2' \
  -Pkotlin.compiler.execution.strategy=in-process
```

Compiling assertion RED -> GREEN executed separately for persistence (unknown JSON field discarded), 1440-class selection (old manual720 result), next-session/UI wiring (missing independent policy), immutable receipt consumer (missing entry point), and failed-baseline requery (mutable inventory incorrectly promoted second probe). New policy tests also exercise portrait/wide/alignment bounds, absent viewport baseline, unavailable baseline fail-closed, lower qualified geometry retaining FPS, HEVC omitted when unqualified, and stable manual physical calibration. Initial RED discovery uses method lookup only to compile against absent APIs; completed tests use direct production calls.

Two library compilation attempts hit stale incremental top-level symbols and sibling in-progress metadata APIs; `--rerun-tasks` removed the stale top-level failures. Tests were placed in app test source to obtain compiling behavioral RED without removing sibling source. A later combined focused run compiled both modules: app CarPlay/reconnect suite passed; library old `CarPlayVideoQualificationTest.hevcOfferRequiresSameDimensionsRateAndProposedFeature` failed at its parent-owned obsolete echo assertion after parent's negotiation correction. Parent must update that test, not revert corrected negotiation.

Final validation: serialized combined focused gate returned **BUILD SUCCESSFUL in 2m19s**. Parsed JUnit XML: **app 179 tests / 0 failures; carplay-lab 7 tests / 0 failures** (`CarPlayVideoPlatform*` + `CarPlayDisplayPolicy*`). No test-source exclusions were used; both modules' complete test sources compiled. Existing Kotlin/JVM deprecation/nullability warnings remain. The parent-owned old HEVC proposed-feature assertion is outside this final focused library selection and remains a separate required update/gate.

## Limits / parent acceptance

- No devices, credentials, uploads or commits performed. Parent owns private-asset assembled APK and phone/tablet QA.
- Initial codec inventory is not configure/start/CSD/decode or phone acceptance proof. Parent's reported physical HEVC control is scoped to its actual hardware, manual2560x1440@30; automatic geometry on GM is still unverified.
- `panelWidth/panelHeight` are accepted snapshot metadata, intentionally not a hard encoded-detail cap. Current Auto policy targets 1440-class even on smaller app layouts; it does not claim native pixel density or automatic4K. Native panel proportional detail tiers above the current area target are not implemented.
- Requested maximum FPS remains independently saved1..60. Owning Display refresh ceiling is not yet captured/used; no guaranteed constant encoded or rendered rate.
- Existing physical sizing remains separate. No new physical-DPI proof/normalization introduced.
- No automatic phone-rejection retry. Explicit H.264 + Save & Reconnect remains fallback guidance.
- Source wiring contracts are labeled as such, not rendered Compose/race execution evidence. Existing Surface receipt ownership/start-once tests remain regression gates; actual bar/fullscreen/resize lifecycle acceptance is parent-owned.
