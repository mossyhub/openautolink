# CarPlay settings: tablet QA and known remaining work

## Scope

Parent-driven physical TCL9466X tests against the corrected tablet build following automotive 0.1.509 publication. These changes are not an uploaded replacement release. Owner reported confusing settings and Save & Reconnect errors; that report reproduced on-device.

## Reproduced defect

The Video menu offered 30–60 maximum FPS in steps of 5, but the shared commit validator accepted only 30/60. A 720p/35 save failed with `Choose a supported resolution and frame rate in Video.` Existing 1440p/40 could therefore also block a Display-only save. The validator now uses the receiver's canonical rates. Resolution selection also needs to atomically preserve or choose a qualified rate.

## Completed physical receipts for the existing preset UI

- 720p: all offered caps 30, 35, 40, 45, 50, 55, 60 saved, reconnected and rendered.
- 1080p: all offered caps 30, 35, 40, 45, 50, 55, 60 saved, reconnected and rendered.
- 1440p: all offered caps 30, 35, 40 saved, reconnected and rendered.
- Sanitized `BENCH_DIAG videoOutput` receipts confirm advertised/coded/visible dimensions match: 1280x720, 1920x1080 and 2560x1440 respectively. Screenshots independently show actual CarPlay content for all 17 combinations. These are not measured constant-FPS guarantees.
- 4K was not exposed by this tablet's exact local decoder qualification. No physical 4K result is claimed.
- Seven existing manual width choices (100–400 mm in 50 mm steps) saved and rendered; screenshots show decreasing UI size/increasing content as physical width increases. The two largest labels produced the same visible phone layout, so a label change does not guarantee a distinct iPhone layout.
- Auto sizing saved and rendered. XML confirms the switch persisted true and the manual button's parent is disabled; its text child misleadingly reports enabled=true, so inspect ancestor semantics.
- Both System UI Visible and Fullscreen Immersive saved and rendered. The former currently overlays system bars on CarPlay content rather than avoiding them; this needs correction before claiming proper viewport-aware behavior.
- Post-matrix touch at a Maps dock icon opened full-screen Maps, confirming input after the reconnect sequence.

Private screenshots/logs and append-only matrix receipts are under the active profile's cache/scratch: `oal-settings-matrix.json`, `oal-display-matrix.json`, `qa-video-matrix-collage.jpg`, `qa-display-collage.jpg`, `oal-autoscale-check.log`. They are not committed because maps/media/device details can be private.

## Additional defect under investigation

With CarPlay enabled but runtime unselected/stopped after launch, the global reconnect dispatcher uses runtime selection alone and can dispatch the Android Auto reconnect path. Manual Connect CarPlay followed by the same size tests works. A tablet launcher appearance was observed, but there is no contemporaneous crash-buffer receipt; it must not be labeled a proved app crash.

## Clarity changes already exercised

Connection shows Bluetooth settings with normal GM/iPhone pairing explanation; no separate OAL pairing is required. Authentication/import/replacement is collapsed recovery functionality, not routine onboarding for asset-bearing internal builds. Raw phone addresses are behind Phone details. Video and Display say Android Auto controls below do not apply to CarPlay. The original prominent Connect CarPlay button and manual size menu are being revised following owner feedback.

## Firmware-backed scope, not yet complete

Existing presets and stepped rates are product limitations, not an exhaustive CarPlay contract. Static iOS 24A446 evidence supports independent pixel dimensions, integer maximum-FPS caps and a conditional HEVC encoder path. Full sources and executable addresses: active scratch `carplay-video-contract-24A446.md`. Custom dimensions/rates, proper HEVC receive/decode/negotiation, a manual sizing slider, launch/retry behavior and exact Auto viewport metadata are still being implemented/validated. No future codec/custom profile is claimed physically working in this report.

## Gate boundary

The FPS-validator-only tablet build passed the app gate. A later combined gate including UI cleanup reported 957 app tests, five failed source-string contracts and one skipped; those failures are not waived. The final combined gate and new settings physical QA remain mandatory before another release.
