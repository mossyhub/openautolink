# CarPlay 513 calibration parent fix

## Owner evidence and scope

- Inspected owner photos `IMG20261011200742.jpg` (editor) and `IMG20261011200728.jpg` (wide video) from attachment `20261011_181336_02591e`.
- The editor photo shows checkerboard/teal boundaries beneath OAL's Save/status header and beside its settings rail, with extra space above/below the editor. The video photo uses the wider/taller app-owned projection region reaching the curved right edge. This is a coordinate-parent disagreement, not a request to enlarge a dialog width.
- Owner confirms the entire 513 round stayed in **Fullscreen**. Only the Video **Fit to window** switch plus reconnect changed; it produced wide fill. No fullscreen/system-bar mode-switch or system-UI-visible physical validation was performed. Do not attribute this reproduced editor failure to system-UI-visible padding.
- Photos establish the visible mismatch; they do not provide trustworthy pixel dimensions of OEM chrome. No native-panel dimensions or automatic curvature detection are inferred.

## Implementation

- `CarPlayDisplaySettingsSection` now requests `onNavigateToCarPlaySafeAreaEditor`; it no longer constructs the calibration Dialog, editor, local window controller, or window-padding estimate.
- `ProjectionScreen` hosts calibration as the last sibling in the **same `ProjectionViewport` parent** as `CarPlayProjectionLayer`. The Android Auto ownership branch also hosts CarPlay calibration outside its settings `AnimatedVisibility`. Settings remains composed beneath the editor, retaining tab/scroll/draft state; the Surface stays in its unchanged composition slot. Opening/closing calibration neither navigates away from projection nor requests reconnect.
- `ProjectionViewport` extracts the existing shared parent rule without changing AA: fullscreen has zero additional chrome padding; system-UI-visible uses per-edge max(system bars, cutout) once. It measures the app-owned Activity rectangle, not the entire physical panel.
- Callsite audit found the `SETTINGS` destination constant but no registered ordinary settings destination. Added that destination with a sibling calibration viewport using exactly the same Activity/NavHost parent rule; no projection runtime/Surface is created to calibrate while in ordinary settings. Added its diagnostics destination so that existing settings callback is not a dead route. The existing AA editor destination/defaults remain unchanged.
- `CarPlaySafeEditor` is content only. It invokes AA's existing editor with 10,000-unit axes, maximum 4,999 and CarPlay labels. Its internal chrome mode is fullscreen because its parent already excludes bars where required. A full-size same-window gesture target prevents taps outside handles/buttons from activating settings or global Save behind the editor.
- Drag/manual/clear values remain local to the existing editor. Cancel/back dismisses without updating the draft. Done merges only CarPlay safe edges into the existing ViewModel draft and returns to settings. Only the established awaited global Save transaction persists/restarts. No audio/USB/runtime edits, installation, phone interaction or upload were performed.
- Existing runtime gate reads saved display mode and awaits a matching atomic parent receipt, not a fitted Surface size or prior fullscreen receipt. Source regressions retain that gate. No runtime mode-gate change was needed.

## Fit default change authorized by owner

- `CarPlaySettings.fitToWindow` defaults to **true**. Serialization uses that same default for absent fields: fresh records and old records without the flag fit standard 720p/1080p/1440p/4K detail tiers to the measured parent.
- Explicit saved `false` remains false. An explicitly saved 513 false choice is **not** rewritten by this default migration; the owner must turn Fit on for that record (and has confirmed doing so in the pictured successful wide-video test).
- Custom and geometry-named presets retain exact geometry regardless of the flag. Resolution/FPS/physical UI-size choices and CarPlay's **off-by-default** admission toggle are unchanged.

## Verified host gates

Host mirror `/mnt/Temp/Hermes_Work/oal-carplay-integration`, JDK `/home/lance/.local/jdks/temurin-17`, shared `/mnt/Temp/Hermes_Work/.oal-gradle.lock`, `--max-workers=1`, commands bounded by 420 seconds.

1. Compiling source-contract RED: nested calibration Dialog assertion failed (1 test, 1 expected failure). No unresolved-symbol failure is counted as RED.
2. Compiling default-policy RED: fresh/missing-flag Fit default assertion failed (2 tests, 1 expected failure). A preceding compile failure due to omitted shared-tree UI helpers was fixed by synchronizing the complete source tree; it is not behavioral evidence.
3. Focused GREEN: host/default/window tests passed after implementation.
4. Compiling modal-overlay RED: full-size gesture barrier assertion failed (5 tests, 1 expected failure), followed by the same-window gesture barrier implementation.
5. Final **unfiltered combined rerun**: `:app:testTabletDebugUnitTest :carplay-lab:testDebugUnitTest --rerun-tasks --no-daemon --max-workers=1 --console=plain`: **BUILD SUCCESSFUL in 2m49s**. XML readback: app **1,103 tests / 0 failures / 0 errors / 1 existing skip**; library **365 tests / 0 failures / 0 errors / 0 skips**. No test/source exclusions. Existing warnings remain.
6. `:app:assembleTabletDebug`: **BUILD SUCCESSFUL in 35s**, including arm64-v8a/x86_64 native build tasks. Candidate is a debug build, not a signed Play release. Merged manifest readback: `OalApplication`, launcher `MainActivity`; no bench replacement.

Host candidate:
`app/build/outputs/apk/tabletDebug/app-tabletDebug.apk`

SHA-256:
`9f42c2e7ff019bb4cf1d00a0f21e5be93e24f7355e76cdce49d745a16c1d4d20`

Debug metadata: package `com.openautolink.app`, version code `1`, version name `0.1.0`. Parent owns release versioning/signing/publication; this is not a newer published vehicle build.

Logs and XML aggregate receipt: `build/carplay-safe-editor-parent/` in the host mirror.

## Coverage and limits

- Fresh/missing-flag defaults, explicit-false roundtrip, exact Custom/geometry presets, and disabled CarPlay defaults.
- Synthetic fullscreen and system-UI-visible measured-parent ratios and encoded safe-edge alignment. Synthetic app origin plus settings rail/header offsets demonstrates that using settings bounds changes the physical safe boundary. Visible mode subtracts max chrome once, not sum/double bars; its offered aspect is neither stale fullscreen nor a forced 16:9 tuple.
- Source seams for both projection owners and the ordinary settings route; no calibration Dialog/window or navigation away from live Surface; mode-qualified receipt gate; AA editor defaults; full-size modal hit target.
- Shared-draft transaction regressions for cancel preserving existing cross-tab draft, Done staging only until global Save, latest-record merge, single save/restart ordering, failed Save retaining calibration without restart. These complement source callbacks; they are not rendered gesture/lifecycle tests.
- Bounded read-only emulator discovery: the actual SDK's `adb devices -l` returned no devices; no emulator binary/AVD was available and localhost emulator console 5554 refused connection. No phone/tablet/emulator was launched, connected, installed or used. Thus **no real Compose/emulator render test** was possible here.
- Vehicle alignment of the new editor against the physical curve, actual Surface lifetime under opening/closing, gesture hit testing and system-UI-visible rendering remain physical/rendered acceptance items. Owner's fullscreen wide-video success on 513 is separate from proof of this new editor fix. No claims about audio, USB, calls or reconnect interoperability are added.
