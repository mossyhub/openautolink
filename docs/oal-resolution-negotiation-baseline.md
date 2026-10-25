# OpenAutoLink panel-geometry negotiation baseline

This document records what the current OpenAutoLink (OAL) source advertises and
negotiates for Android Auto video geometry. The 2914×1134 example is a
**vehicle-specific observation** from a 2024 Chevrolet Blazer EV; it is not a
claim about every AAOS display.

---

## TL;DR

OAL does not advertise an arbitrary 2914×1134 codec resolution. The Android Auto
video protocol used here exposes nine fixed landscape/portrait resolution enum
values. OAL therefore:

1. advertises an ordered ladder of standard tiers for one codec family (repository
   default H.264 Baseline: 1920×1080, 1280×720, 800×480);
2. computes a per-tier `width_margin` or `height_margin` so the codec frame's
   inner content rectangle matches the live render rectangle's aspect ratio;
3. derives automatic per-tier density from the measured drawable width, panel
   density, and each tier's post-margin inner width; and
4. makes the renderer use the same margin formula when scaling and clipping the
   decoded frame.

For a measured 2914×1134 render rectangle with the repository defaults, OAL's
top H.264 tier is:

- `VIDEO_1920x1080` at 60 fps;
- `height_margin = 333`, yielding an inner 1920×747 rectangle;
- `density = 131` when the runtime panel density is 200 dpi;
- `pixel_aspect_ratio_e4 = 10000` (square pixels);
- `viewing_distance = 700` and `decoder_additional_depth = 1`.

The 2914×1134 dimensions are an input to margin/orientation/density math, not a
codec resolution on the wire. If the renderer has not measured its rectangle at
session creation, `SessionManager.startSession` falls back to window metrics for
panel dimensions and to the saved manual DPI for density (repository default
160); the 131 value is therefore conditional on the live 2914×1134/200-dpi
measurement being available.

---

## 1. Two-stage path

### Stage 1 — Kotlin assembles `AasdkSdrConfig`

File and symbol:
`app/src/main/java/com/openautolink/app/session/SessionManager.kt` —
`SessionManager.startSession`

#### Resolution preference → codec dimensions

`aaResolution` maps to the Android Auto enum-compatible dimensions:

- `480p` → 800×480
- `720p` → 1280×720
- `1080p` → 1920×1080
- `1440p` → 2560×1440
- `4k` → 3840×2160
- `_p` variants → 720×1280, 1080×1920, 1440×2560, or 2160×3840

`AppPreferences.DEFAULT_AA_RESOLUTION` is `1080p`. This selected size is the
single tier in manual mode. In auto-negotiate mode, C++ chooses the actual tier
ladder and computes density independently for every advertised tier.

#### Live render rectangle

`ProjectionScreen` measures the content region where the AA `SurfaceView` is
placed and calls `ProjectionViewModel.setRenderRect`, which forwards it to
`SessionManager.setRenderRect`. `startSession` sends that live rectangle as
`panelWidth`/`panelHeight`; when unavailable, it falls back to
`WindowManager.currentWindowMetrics.bounds`.

The distinction matters:

- `fullscreen_immersive` (the repository default) normally gives the full panel
  rectangle;
- `system_ui_visible` gives the smaller chrome-free content rectangle, so margin
  and density calculations intentionally differ.

The exact `system_ui_visible` height is runtime-derived and should not be treated
as a vehicle-independent constant.

Files and symbols:

- `app/src/main/java/com/openautolink/app/ui/projection/ProjectionScreen.kt` —
  render-rectangle measurement
- `app/src/main/java/com/openautolink/app/ui/projection/ProjectionViewModel.kt` —
  `setRenderRect`
- `app/src/main/java/com/openautolink/app/session/SessionManager.kt` —
  `setRenderRect`, `startSession`

#### Pixel aspect ratio

With `aaPixelAspect = -1` (auto), `startSession` sends
`pixelAspectE4 = 10000`, meaning 1:1 square pixels. A positive value is an
explicit override; zero omits the field. The current comments record that tested
phone implementations ignored non-1.0 pixel-aspect values, so OAL uses margins
and uniform rendering rather than depending on this field for aspect correction.

#### Auto-DPI

With `aaAutoDpi = true`, Kotlin first converts the measured draw space into one
logical-width target:

```text
targetLayoutWidthDp = floor(renderRectWidth * 160 / panelDensityDpi)
```

The render rectangle is mode-specific: fullscreen uses the full drawable panel,
while `system_ui_visible` uses the smaller chrome-free content area. Kotlin passes
that target to C++. For every advertised resolution, C++ then computes:

```text
innerWidth = codecWidth - effectiveWidthMargin
density    = floor(innerWidth * 160 / targetLayoutWidthDp)
```

This is equivalent to the width-based GM formula, but it is evaluated for every
tier Gearhead may select rather than once for the hidden/manual base resolution.
Native density is clamped to at least 80 dpi. If the render rectangle is
unavailable, OAL uses the saved `aaDpi` value and logs the fallback.

For a measured 2914-pixel-wide render rectangle and 200-dpi panel, the target is
2331 dp. A 2560-wide 1440p tier uses density 175, while the 4K tier uses density
263. Both therefore expose approximately the same logical AA width.

`aaTargetLayoutWidthDp` remains a separate explicit override. In manual mode
Kotlin computes DPI for the selected tier; in auto-negotiate mode C++ applies the
target independently to each advertised tier.

#### Config object

`SessionManager.startSession` constructs `AasdkSdrConfig` with the selected base
size, effective density, manual/automatic margin controls, live render rectangle,
codec family, frame rate, pixel aspect, viewing distance, and decoder depth.

`AasdkSdrConfig` itself defaults to 1920×1080, 60 fps, density 160,
`autoMargins = true`, `autoNegotiate = true`, and codec `h265`; however,
`SessionManager` supplies the user preference, whose repository default is
`h264`.

Files:

- `app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSdrConfig.kt`
- `app/src/main/java/com/openautolink/app/data/AppPreferences.kt`

### Stage 2 — C++ emits the SDR video configurations

File and symbol:
`app/src/main/cpp/jni_session.cpp` —
`JniSession::buildServiceDiscoveryResponse`

`JniSession::start` reads the Kotlin DTO fields into `JniSession::SdrConfig`.
`buildServiceDiscoveryResponse` then constructs the video channel.

#### Fixed resolution table

`kDims` maps the protocol's enum indices to codec dimensions:

| Enum index | Dimensions |
|---:|---:|
| 1 | 800×480 |
| 2 | 1280×720 |
| 3 | 1920×1080 |
| 4 | 2560×1440 |
| 5 | 3840×2160 |
| 6 | 720×1280 |
| 7 | 1080×1920 |
| 8 | 1440×2560 |
| 9 | 2160×3840 |

There is no 2914×1134 value. Supporting a new protocol enum would require source
changes to this table and the tier selection logic.

#### Orientation and tier ladders

The source classifies a render rectangle as portrait only when
`panelWidth < panelHeight`. Every wider-than-tall rectangle uses a landscape
ladder; there is no separate ultrawide class.

In auto-negotiate mode OAL advertises only the chosen codec family, largest first:

- H.264 Baseline: landscape `[3, 2, 1]`, portrait `[7, 6]`;
- H.265: landscape `[5, 4, 3, 2, 1]`, portrait `[9, 8, 7, 6]`.

The source intentionally avoids mixing H.264 and H.265 in one SDR. H.264 is
capped at 1080p; selecting H.265 enables 1440p and 4K tiers.

#### Per-tier margins — `autoMargins` / `applyVideoConfig`

For each advertised tier, the C++ `autoMargins` lambda mirrors
`MarginAutoCalc.compute`:

```text
codecAR = codecWidth / codecHeight
panelAR = panelWidth / panelHeight

if relative difference < 0.5%:
    widthMargin = heightMargin = 0
else if codecAR > panelAR:
    innerWidth  = round(codecHeight * panelAR)
    widthMargin = codecWidth - innerWidth
else:
    innerHeight  = round(codecWidth / panelAR)
    heightMargin = codecHeight - innerHeight
```

When a margin is nonzero, `applyVideoConfig` also splits it between the two sides
in `UiConfig.margins`. For odd values, the second side receives the extra pixel.
This is the SDR's centering hint.

Current renderer code still anchors the inflated `SurfaceView` at the top-left.
Its comment scopes the reason as a phone-specific observation: tested Pixel and
Samsung implementations ignored the UI-margin hint and placed usable content at
the top/left, with margin pixels at the bottom/right. This renderer choice should
not be generalized to every phone without a new observation.

Files and symbols:

- `app/src/main/cpp/jni_session.cpp` — `autoMargins`, `applyVideoConfig`
- `app/src/main/java/com/openautolink/app/video/MarginAutoCalc.kt` —
  `MarginAutoCalc.compute`
- `app/src/main/java/com/openautolink/app/ui/projection/ProjectionScreen.kt` —
  crop-mode surface sizing, clipping, anchoring, and touch mapping

#### Density and other fields

`computeDensity` uses `targetLayoutWidthDp` when it is positive. Auto-DPI now
derives that target from the live render rectangle and panel density, so each
auto-negotiated tier receives a density based on its own post-margin inner width.
If auto-DPI and the explicit target are both disabled, every tier receives the
manual `videoDpi` value by design.

`applyVideoConfig` conditionally emits:

- `pixel_aspect_ratio_e4` when positive;
- `real_density` when positive (current `SessionManager` does not set it, so the
  DTO default zero omits it);
- `viewing_distance` when positive;
- `decoder_additional_depth` when positive;
- safe-area content insets when configured.

#### Phone selection and OAL acceptance

`JniSession::onMediaChannelSetupRequest` advertises acceptance of every emitted
configuration index in auto mode. The phone communicates the selected index in
`VideoStart.configuration_index`, handled by
`JniSession::onMediaChannelStartIndication`.

The ordered list makes index 0 the highest advertised tier. Selection is still a
phone decision; source order alone is not proof that every phone chooses index 0.

`InputSourceService` exposes one advertised touchscreen capability rather than one
per video configuration. The automatic touchscreen follows the first video tier
for this capability, but active touch forwarding must follow the phone's actual
`VideoStart.configuration_index`. Phones can choose different tiers over USB and
wireless. The native session retains the exact offered list and synchronously
publishes the selected logical dimensions before acknowledging video focus;
input waits for that selection and ignores retired-session geometry callbacks.

Gearhead's inspected input consumer uses selected-video coordinates; it does not
normalize points from the advertised touchscreen dimensions. OAL therefore maps
its visible inner rectangle into the selected logical video tier while preserving
margin calculations. A padded HEVC decoder allocation such as 1280×736 must not
be treated as the logical 1280×720 tier. Decoder stats preserve the output's visible
crop rather than overwriting it with the original requested dimensions.

`Input touchscreen:` records the capability; `Selected video geometry:` and
`Touch mapping active:` record the active selection/mapping. These are distinct
boundaries. Automated host tests do not replace a physical touch retest.

---

## 2. Scoped 2914×1134 vehicle example

The vehicle facts used here come from `docs/embedded-knowledge.md` and are scoped
to a tested 2024 Chevrolet Blazer EV:

- reported framebuffer: 2914×1134;
- reported density: 200 dpi;
- non-rectangular physical screen represented by display cutout insets.

With a measured full-panel render rectangle and repository defaults
(`videoAutoNegotiate = true`, codec `h264`, base resolution `1080p`,
`aaAutoDpi = true`, `aaAutoMargins = true`, scaling `crop`, display mode
`fullscreen_immersive`), C++ advertises:

| idx | resolution | codec pixels | height margin | inner rectangle | density |
|---:|---|---:|---:|---:|---:|
| 0 | `VIDEO_1920x1080` | 1920×1080 | **333** | 1920×747 | **131** |
| 1 | `VIDEO_1280x720` | 1280×720 | **222** | 1280×498 | **87** |
| 2 | `VIDEO_800x480` | 800×480 | **169** | 800×311 | **80** (minimum) |

All three configurations also use H.264 Baseline, 60 fps,
`pixel_aspect_ratio_e4 = 10000`, `viewing_distance = 700`, and
`decoder_additional_depth = 1`. Width margin is zero because each codec frame is
narrower than the 2.57:1 render rectangle and therefore needs top/bottom trimming.
The SDR UI-margin splits are 166/167, 111/111, and 84/85 respectively.

Auto-DPI derives a 2331-dp target from the 2914-pixel draw width and 200-dpi panel.
The 1920 and 1280 tiers preserve that logical width to rounding error. The 800
tier reaches the protocol's 80-dpi floor and therefore exposes approximately
1600 dp rather than shrinking below the allowed density.

For the user's H.265 auto-negotiation capture on the same draw rectangle, the 4K tier uses density 263 and the 1440p tier uses density 175. Gearhead selected
configuration index 0 (4K) in that capture.

The Settings UI labels widths below 880 dp as Canonical, 880–1240 dp as
Semi-widescreen, and above 1240 dp as Full widescreen. Those strings are an OAL
informational hint, not part of the wire protocol.

In the vehicle/phone captures that motivated this baseline, the phone selected
configuration index 0. That is an observation from that setup, not a guarantee
for other phones, versions, display modes, or saved settings.

The source comment in `ProjectionScreen` that estimates a 358-pixel margin is a
rough historical estimate. The shared current formula yields 333 for exactly
2914×1134.

---

## 3. Assumptions and compatibility watch points

1. **Fixed nine-value table.** OAL cannot emit arbitrary native panel geometry or
   a future enum value without source changes.
2. **Binary orientation test.** Width greater than or equal to height is treated
   as landscape; ultrawide panels are margin-corrected landscape, not a distinct
   class.
3. **Hardcoded 0.5% tolerance.** The same threshold exists in C++ and Kotlin and
   must remain synchronized.
4. **One codec family per SDR.** H.264 is capped at 1080p; H.265 enables larger
   tiers but is not mixed with H.264.
5. **Width-based auto-DPI.** Kotlin derives a logical-width target from the live
   render rectangle and panel density; C++ derives density from each tier's
   post-margin inner width. A phone version that changes its layout breakpoint or
   uses a different physical-size interpretation may still render differently.
6. **Manual-density exception.** When both auto-DPI and the explicit layout-width
   target are disabled, all fallback tiers intentionally reuse the manual DPI.
7. **Runtime measurement race.** Before `ProjectionScreen` reports the render
   rectangle, panel dimensions and density use fallback paths. A first session
   can therefore differ from a later session without any setting change.
8. **SDR margin hint vs observed phone placement.** C++ sends symmetric
   `UiConfig.margins`, while the renderer's top-left anchor is based on scoped
   phone observations. A phone-side change in margin placement can expose an
   alignment mismatch.
9. **Display-mode dependence.** `system_ui_visible` changes the render rectangle;
   it is expected to produce different margins and density from full-screen mode.

---

## 4. Source index

| Concern | Current file / symbol |
|---|---|
| Resolution mapping, auto-DPI, panel rectangle, SDR config | `app/src/main/java/com/openautolink/app/session/SessionManager.kt` — `startSession` |
| Render-rectangle reporting | `app/src/main/java/com/openautolink/app/session/SessionManager.kt` — `setRenderRect`; `ProjectionViewModel.setRenderRect` |
| Repository defaults | `app/src/main/java/com/openautolink/app/data/AppPreferences.kt` — `DEFAULT_VIDEO_AUTO_NEGOTIATE`, `DEFAULT_VIDEO_CODEC`, `DEFAULT_VIDEO_FPS`, `DEFAULT_DISPLAY_MODE`, `DEFAULT_AA_*` |
| Kotlin SDR DTO | `app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSdrConfig.kt` — `AasdkSdrConfig` |
| JNI field reads | `app/src/main/cpp/jni_session.cpp` — `JniSession::start` |
| Resolution table, tier ladders, margins, density, SDR emission | `app/src/main/cpp/jni_session.cpp` — `JniSession::buildServiceDiscoveryResponse`, `kDims`, `autoMargins`, `applyVideoConfig`, `computeDensity` |
| Configuration acceptance | `app/src/main/cpp/jni_session.cpp` — `JniSession::onMediaChannelSetupRequest` |
| Selected configuration log | `app/src/main/cpp/jni_session.cpp` — `JniSession::onMediaChannelStartIndication` |
| Shared Kotlin margin formula | `app/src/main/java/com/openautolink/app/video/MarginAutoCalc.kt` — `MarginAutoCalc.compute` |
| Crop renderer and phone-specific anchor observation | `app/src/main/java/com/openautolink/app/ui/projection/ProjectionScreen.kt` — crop-mode `SurfaceView` sizing and clipping |
| Layout-bucket hint | `app/src/main/java/com/openautolink/app/ui/settings/SettingsScreen.kt` — AA layout width helper text |
| Vehicle-scoped panel facts | `docs/embedded-knowledge.md` — `Car Hardware (GM Blazer EV, gminfo platform)` |
