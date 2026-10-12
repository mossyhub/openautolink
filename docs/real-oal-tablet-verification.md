# Real OAL tablet verification — 2026-10-09

## Artifact and scope
- Worktree: `/hermes_work/oal-carplay-integration`, branch `feat/native-carplay-integration`, base main `114ae144ea6eacf786fbd66b6ea116e28bede9ed`.
- Actual `OalApplication` and `MainActivity` retained; no BenchActivity registered.
- Tablet-only manifest relaxes automotive eligibility and omits the automotive CarAppActivity renderer. Vehicle/release manifest unchanged. The suspected hidden-renderer focus issue is not conclusively causal; owner interaction also affected automation.
- Same `com.openautolink.app` installed as an upgrade, preserving owner-private no-backup storage. No credentials bundled or uploaded.

## Executed gates
- Combined unfiltered build: app 805 tests / 0 failures / 1 skip, receiver 127 / 0 failures / 0 skips; receiver lint 0 errors; tablet APK packaged.
- After WPP correction and switcher transport guard: unfiltered app 808 tests / 0 failures / 1 skip; updated tablet APK packaged and installed.
- WPP default regression first failed on actual `hotspot` default, then passed after changing default to `wpp`. Explicit shared-Wi-Fi and USB selections are preserved. Switcher tests first failed on old car-hotspot-only condition, then passed after requiring shared-Wi-Fi transport.
- Installed UI hierarchy confirms Wireless (WPP) checked. CarPlay toggle initially off.

## Positive physical observations
- CarPlay enabled explicitly in real Connection overlay, paired test phone selected, shared-LAN override configured on wlan0. Existing encrypted passphrase retained; owner-device Bluetooth identity supplied without logging it.
- Real authenticated CarPlay video rendered inside OAL's actual projection UI at requested baseline 720p/30. Decoder coded dimensions still require a separate numeric receipt before claiming an exact negotiated size.
- Settings opened from the live CarPlay overlay. Closing Settings kept projection alive. Subsequent touch changed the projected app from maps to the media player; runtime authentication/render evidence remained true.
- Floating Settings access works in this run. Dragging, diagnostics integration, other overlays, and reconnect/stop remain to be exercised.

## Follow-up: audio and higher-resolution proof

- Installed and exercised the reference-compatible output audio catalog candidate. Capture: `carplay-lab/build/audio-reference-20261009-141733.log`.
- Actual audio ownership transitioned to entity 1/permanent 1; type 102 AAC-LC 48 kHz (`audioFormat=8388608`) was accepted. Runtime reported audio focus granted and AAC-LC decoder configured.
- Owner explicitly confirmed sound now comes from the TCL speakers, not the iPhone. This supersedes the earlier phone-speaker failure for this candidate; it does not establish Siri/call audio or vehicle routing.
- Video offer, coded dimensions and visible dimensions were all 2560×1440 in the same real OAL run. This establishes tablet 1440p rendering, not 4K or every frame-rate/profile combination.
- Combined gate before this installation: app 862 tests, zero failures/errors, one skip; receiver 141 tests, zero failures/errors/skips. APK built, installed and launched using real MainActivity.
- The output-catalog change is a demonstrated successful discriminator; individual removed bits are not independently proved causal.

## Earlier failure / remaining incomplete work
- Owner confirms audio still plays from iPhone speakers, not TCL. Default reference startup without automatic requestUI is therefore insufficient to solve audio routing. New capture did not contain the expected logcat markers because the runtime only retained receiver status in UI; zero matching log tags is not proof of no audio SETUP in this integrated run.
- Settings header incorrectly showed AA Disconnected during active CarPlay; protocol-specific status correction pending.
- Settings coherence/conciseness and placement redesign pending. Resolution/FPS move to Video; source-backed physical-size/UI-scale control belongs under Display.
- Higher-resolution and scale options pending capability qualification and phone/device proof, not established by source enums.
- Car AP/scoped IPv6 ingress, actual microphone/Siri/call paths, AA runtime regression testing, lifecycle/reconnect, permission/toggle denial, and store readiness remain unverified.
- Authentication provisioning in locked car has no verified user-accessible path yet. Existing tablet owner state does not establish distribution/provisioning readiness or rights.
- No Play upload, commit, PR, or release performed.

## Owner-private evidence
Screenshots and UI XML are in the active profile scratch directory; do not publish their private map/media/device contents. Integrated broad capture: `carplay-lab/build/integrated-attempt-20261009-133722.log` (ignored).
