# CarPlay automatic scale handoff

## UI contract (parent owns UI)

- `CarPlaySettings.autoScale: Boolean = true` lives in the existing atomic, non-secret `carplay_settings_v1` record. Missing legacy field defaults to true. Explicit false survives canonical resolution/FPS/manual-width restoration.
- Label: **Automatic UI scale**. Help: **Uses the measured projection area and available display physical-size estimates. Applies on the next connection. Unreliable measurements use the saved calibration; calibration may be needed.**
- Preserve `widthPhysicalMm` (100..400, step 50) when toggling. Disable the manual control while automatic mode is on; turning automatic off advertises the exact saved width. Merge both edited fields into the latest settings aggregate, without writing secrets.
- Hardware estimates are NOT measured/calibrated proof. Do not present Android logical density as physical DPI, a protocol DPI field, or inferred CarPlay layout thresholds.

## Required parent-owned Surface integration

`ProjectionViewModel.onCarPlayViewportChanged(width: Int, height: Int, display: android.view.Display?)` is implemented and compiled. **The current Surface UI caller is intentionally not edited by this worker.** Without this caller the bounded wait ends in the explicit stable calibration fallback, not hardware automatic detection.

In `CarPlayProjectionLayer.kt`'s existing SurfaceView `apply` callback:

```kotlin
override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    viewModel.onCarPlayViewportChanged(width, height, this@apply.display)
    viewModel.onCarPlaySurfaceAvailable(holder.surface)
}
```

Call on every resize. Use the owning SurfaceView's Display, not the application/default Display. Surface destruction clears the exact current snapshot. Resizes only affect a subsequent receiver; they do not mutate/renegotiate active video or change touch coordinates.

## Research and policy basis

Inspected OAL base `114ae144ea6eacf786fbd66b6ea116e28bede9ed`:

- `ProjectionScreen.kt` detects the AA render rectangle from padded Compose constraints and logical `LocalConfiguration.densityDpi`.
- `SessionManager.kt` auto-DPI derives logical width from the live rectangle; `AutoDpiPolicy.kt` carries that target to native.
- `jni_session.cpp` `computeDensity` derives each tier's DPI from post-margin drawable width. That is an AA logical-density rule, not physical CarPlay DPI evidence.

Pinned DiPlay `e2fd8eadf5a6b705666f292055e72aa93387533d`:

- `shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlayDisplaySettings.kt:42-78` resolves physical millimeters using current/reference pixel ratio and WIDTH/HEIGHT basis, bounding reported millimeters to 1..2000.
- `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt:3591-3606` passes the actual activity/video-view dimensions versus maximum detected display dimensions. Those reference pixels are **not codec resolution tiers**.

OAL uses WIDTH basis. For the selected qualified profile, aspect-fit its video into the actual viewport. Hardware candidate: fitted viewport width × 25.4 / owning-display xdpi; height follows the qualified profile aspect. Invalid/nonfinite/out-of-bounds metrics, logical-density aliases, the common baseline 160/160 pair, inconsistent axes, or invalid geometry fall back to saved reference width × fitted viewport width / detected display width. Missing geometry uses the saved width exactly. Report `calibration needed`. Millimeters remain invariant across proportional codec tiers on the same viewport; this preserves advertised scale intent, not proven iPhone apparent-size behavior.

The metric sanity bounds are conservative engineering guards, not Apple rules or proof that a vendor's plausible xdpi is accurate. AOSP's `setToDefaults` initializes xdpi/ydpi from `DENSITY_DEVICE`, so populated fields alone do not establish independent physical measurement.[1] Accepted metrics are labeled `hardware estimate`. No physical metrics were collected in this task.

`CarPlayDisplayPreferences.autoScaleInput` is ephemeral. `CarPlayDisplayPolicy.select` retains it when selecting the exact capability-qualified baseline. `physicalSize` and `/info` conversion resolve against that selected profile. Manual stepped preferences are not replaced by computed advertisement values.

## Executed bounded verification

Host JDK17, shared `/mnt/Temp/Hermes_Work/.oal-gradle.lock`, one Gradle worker:

- Display/policy focused module run: **20 tests**, including **5 new pure automatic-scale tests**, zero failures/errors; exact XML totals parsed while holding the same lock.
- App settings/wiring focused run: **14 tests**, zero failures/errors; includes canonical restoration and a lexical VM wiring guard. `compileTabletDebugKotlin` succeeded. Existing unrelated compiler warnings remain.
- Runtime assertion RED observed for invalid metrics, default synthetic pair, preference advertisement, and atomic settings; wiring RED is explicitly lexical. Initial new-policy RED was compiler-unresolved, not behavioral proof.
- No settings UI changes, audio changes, devices, installs, SDK installs, commits, or releases. Parent still owns Surface callback integration, unfiltered combined debug/release gates, and physical-phone size/calibration validation.

## Sources

[1] https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/util/DisplayMetrics.java — AOSP DisplayMetrics source
