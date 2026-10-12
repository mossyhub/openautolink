# CarPlay settings + launch/viewport recovery handoff

## Outcome

Recovered the exact surviving edits from deleg_a5e98e39/task-1.log and deleg_b7620920/task-0.log; did not recreate their implementation or touch devices/releases/commits. App display/settings scope is complete at the source/test/build level. **HEVC receiver/engine forwarding still needs parent integration; physical new-settings QA is not complete.**

## Source behavior

- Presets include standard, wide and portrait dimensions plus custom positive/even WxH. Every integer maximum FPS 1–60 is retained without step-5 quantization; 60 is a product ceiling, not a claimed protocol maximum. Partial/decimal/overflow drafts are rejected without silently restoring old values. Custom sizes are additionally qualified against the decoder and local U16 HID bound.
- Continuous 100–400 mm manual sizing slider retains intermediate 125/225/399 through storage/runtime/wire metadata tests; larger UI is to the right (smaller physical width). Auto disables manual dragging and estimates from actual aspect-fitted viewport, not coded buffer dimensions.
- Added CarPlay-only `videoCodec` h264/hevc persistence, validation and controls; default H.264. HEVC maps to `display.preferHevc`, never force. AVC qualification now delegates to codec-worker `CarPlayVideoQualification.androidProbe`, including exact profile/alignment instead of a second looser capability implementation.
- Preserved one-shot disk-backed launch, explicit Retry/setup guidance, suppression when Save/Retry takes ownership, and saved-enabled dispatch after awaited global commit. Enabled/unselected uses coordinator connect; disabled/selected awaits owner release before normal AA settings reconnect. AA/WPP production paths were not rewritten.
- Replaced hardcoded 16:9 CarPlay Surface with aspect fit from the actual qualified display receipt; runtime reports receiver `displayConfiguration` to the ViewModel. Touch uses that same rendered area. Surface callbacks retain exact-view/generation ownership. Post-layout `onGloballyPositioned` refreshes mode-qualified viewport even when display-mode changes reuse a Surface without SurfaceChanged.
- Shared system-bar/cutout padding applies before CarPlay's early return, constraining its real Surface parent. Recovery panel explicitly excludes rendered video so it cannot cover active CarPlay content.

## Files changed in this recovery

Production:
- app/data/CarPlaySettings.kt
- app/ui/settings/CarPlaySettings.kt
- app/ui/projection/ProjectionViewModel.kt, ProjectionScreen.kt
- app/carplay/CarPlayProjectionLayer.kt, OalCarPlayRuntime.kt
- carplay-lab/media/CarPlayDisplayPreferences.kt, CarPlayDisplayPolicy.kt

Tests:
- app/data/CarPlaySettingsTest.kt: fixed illegal cross-module internal bench helper access by constructing public AirPlayConfig over production display serialization; preserved full metadata assertions.
- app/ui/settings/CarPlayRecoveryIntegrationTest.kt: codec roundtrip/mapping, dynamic viewport wiring, and rendered-video recovery-panel exclusion. All three assertions executed RED before production edits, then passed.

Docs: carplay-settings-codec-api.md, carplay-settings-recovery-gates.json, this handoff. Prior interrupted settings/navigation tests and implementation were preserved; their old source contracts were already semantically updated in the surviving tree and are now green.

## Executed final gates

Host `/mnt/Temp/Hermes_Work/oal-carplay-integration`, JDK `/home/lance/.local/jdks/temurin-17`, SDK `/home/lance/.local/android-sdk`, serialized shared flock:

```sh
flock -w 300 /mnt/Temp/Hermes_Work/.oal-gradle.lock ./gradlew \
 :app:testDebugUnitTest :app:testTabletDebugUnitTest \
 :carplay-lab:testDebugUnitTest :app:assembleTabletDebug \
 --continue --rerun-tasks --max-workers=1 \
 -Dorg.gradle.jvmargs="-Xmx1536m -XX:ActiveProcessorCount=2" --console=plain
```

**BUILD SUCCESSFUL; 106 actionable tasks executed.** XML parsed after this exact run:

| Task | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| app:testDebugUnitTest | 985 | 0 | 0 | 1 |
| app:testTabletDebugUnitTest | 985 | 0 | 0 | 1 |
| carplay-lab:testDebugUnitTest | 257 | 0 | 0 | 0 |

APK: host `app/build/outputs/apk/tabletDebug/app-tabletDebug.apk`, 102933949 bytes, SHA256 `2f74ca10e41f0b9d759e64cb39e2da2df2574c8f81980dadb102da7f860c46d6`. Artifact existence/size/hash checked. No install/upload performed.

## Failures encountered, not concealed

1. Initial app test compile failed on surviving cross-module `internal benchReceiverConfig` use; corrected test without exporting receiver API.
2. Mixed incremental bytecode after adding a default constructor field caused 20 NoSuchMethodError failures. Forced --rerun-tasks rebuilt classes and removed them; no production workaround.
3. Concurrent codec recovery test initially failed `CarPlayHevcSessionTest` with NoSuchMethodException; codec worker corrected it, final receiver gate passes.
4. One intermediate full run failed unrelated `VehiclePermissionRefreshTest.synchronousBatteryErrorInvalidatesInitialRead` in both app variants. Untouched production/test source; forced final rerun passed both. Treat as observed intermittent failure, not a settings fix.
5. Existing Kotlin nullability/reified-intersection/deprecation warnings and SDK XML-version warning remain; no final compile failure.

## Exact remaining work / acceptance boundary

- **Parent-owned receiver bridge required:** `ProjectionReceiver` currently still calls display policy and constructs sink/session without the new immutable `CarPlayVideoSelection`; `CarPlayMediaEngine.onCodec` still rejects non-AVC. Codec worker handoff excludes those files, and this worker scope permits only receiver display-preferences/policy/AirPlayDisplaySettings. Read docs/carplay-hevc-handoff.md and docs/carplay-settings-codec-api.md. Forward one exact qualification receipt to sink/session/info; derive `hevc` from `.hevcOffered`; admit only `session.acceptsVideoCodec(codec)`. Settings carries preferHevc but **does not yet enable end-to-end HEVC**. Rerun combined gates after parent bridge.
- Parent installation + real tablet QA of every exposed preset/custom WxH/integer FPS/codec option and cross-tab saves remains mandatory. Prior 17 video and seven old manual-size physical receipts describe the previous build only.
- Prove custom wide/portrait/square aspect fit and touch, Auto physical mm from post-layout unobscured viewport, both bar modes, no chrome overlap, startup once, Retry, enabled-unselected Save routing to CP, and disabling CP releases ownership before AA/WPP resumes.
- HEVC capability/parser/sink JVM proof is not iPhone offer acceptance or actual HEVC decode/render evidence. Do not call this APK physically validated or release-ready yet.
