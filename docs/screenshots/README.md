# OpenAutoLink App Screenshots

## Current Audio settings and actual GM approval page

- [Audio settings, landscape](Audio-Settings-Landscape.png): actual Compose UI rendered with Robolectric NativeGraphics, API 34, 1000×700 dp, OAL dark theme. The call setup state is an explicit test fixture, not a live account/vehicle approval claim.
- [CarPlay call controls](Audio-Settings-CarPlay-Calls.png): same actual landscape render scrolled to handler, setup, extra ringtone Off and optional system shortcuts.
- [Android Auto volume offsets, narrow / large text](Audio-Settings-Narrow-Volume.png): percentage values remain visible alongside wrapped labels.
- [Audio settings, narrow / large text](Audio-Settings-Narrow-Large-Text.png): same implemented UI at 320×640 dp with 150% font scale; scroll and Back are exercised by the Compose test.
- [GM Calling accounts](GM-Calling-Accounts-Redacted.jpg): actual owner photograph, cropped to remove system-status details and black-redacted over the private Bluetooth address. The historical account label is not retouched: production uses **OpenAutoLink**. Independent switches show Android account approval, not audio ownership.

Generated app renders are not GM vehicle screenshots. No before-548 screenshot is available. The real GM image is not an emulator/mock. No raw private photograph or identifiers are published. See [calls and microphones](../phone-calls.md).

## Historical screenshot archive

The remaining screenshots below describe older app revisions, not current setup instructions. In particular, historical Bridge/Phones/call-quality controls are not current production Audio controls.

Captured from the BlazerEV AAOS emulator (2400×960, Android 13 API 33).
Emulator VHAL provides mock vehicle data for the Car diagnostics tab.

## Projection Screen (Main)
The primary screen — SurfaceView for Android Auto projection with overlay buttons.

| Screenshot | Description |
|------------|-------------|
| [01-projection-screen-idle](01-projection-screen-idle.png) | Main projection screen when idle (no bridge connected). Shows "Disconnected" HUD, OpenAutoLink logo, and overlay buttons (Settings, Stats, Phone Switcher). |

## Settings — Connection Tab
Bridge IP, port, network interface selection, and mDNS discovery.

| Screenshot | Description |
|------------|-------------|
| [02-settings-connection-tab-top](02-settings-connection-tab-top.png) | Network interface selector, bridge IP/port fields. NavigationRail sidebar with all settings tabs. |
| [02-settings-connection-tab-scrolled](02-settings-connection-tab-scrolled.png) | Scrolled: mDNS discovery section. |

## Settings — Phones Tab
Paired Bluetooth phones and default phone selection.

| Screenshot | Description |
|------------|-------------|
| [03-settings-phones-tab](03-settings-phones-tab.png) | Paired phones list (empty when no bridge connected), refresh button, default phone section. |

## Settings — Bridge Tab
Bridge updates, phone connection mode (wireless/USB), WiFi settings, identity.

| Screenshot | Description |
|------------|-------------|
| [04-settings-bridge-tab-top](04-settings-bridge-tab-top.png) | Bridge auto-update toggle, version info, check for update button. |
| [04-settings-bridge-tab-scrolled1](04-settings-bridge-tab-scrolled1.png) | Scrolled: phone connection mode (Wireless/USB), wireless settings (WiFi band, country, SSID, password), identity (head unit name). |

## Settings — Display Tab
Display mode, AA safe area, content insets, drive side, features, overlay buttons, AA UI customization.

| Screenshot | Description |
|------------|-------------|
| [05-settings-display-tab-top](05-settings-display-tab-top.png) | Display mode picker (System UI Visible / Fullscreen Immersive), AA Safe Area editor button, Content Insets editor button. |
| [05-settings-display-tab-scrolled1](05-settings-display-tab-scrolled1.png) | Scrolled: drive side (LHD/RHD), features (GPS forwarding, cluster navigation, IMU sensors), overlay button toggles (Settings, Stats, Phone Switch). |
| [05-settings-display-tab-scrolled2](05-settings-display-tab-scrolled2.png) | Scrolled: Android Auto UI section — sync AA theme, hide clock/signal/battery toggles, distance units (Auto/Metric/Imperial). |

## Settings — Video Tab (Auto Mode)
Video negotiation in auto mode, DPI slider, video scaling.

| Screenshot | Description |
|------------|-------------|
| [06-settings-video-tab-top](06-settings-video-tab-top.png) | Auto-negotiate switch (enabled), DPI slider and presets (120–320). |
| [06-settings-video-tab-scrolled1](06-settings-video-tab-scrolled1.png) | Scrolled: video scaling mode (Letterbox / Fill Screen), AA video margins (width/height margin, pixel aspect ratio). |

## Settings — Video Tab (Manual Mode)
Full manual video settings: codec, FPS, resolution, DPI, scaling, margins.

| Screenshot | Description |
|------------|-------------|
| [14-settings-video-manual-top](14-settings-video-manual-top.png) | Manual mode selected — codec picker (H.264/H.265/VP9), frame rate (30/60 FPS). |
| [14-settings-video-manual-scrolled2](14-settings-video-manual-scrolled2.png) | Scrolled: DPI slider, video scaling, AA video margins. |

## Settings — Audio Tab
Audio source, microphone source, call quality.

| Screenshot | Description |
|------------|-------------|
| [07-settings-audio-tab-top](07-settings-audio-tab-top.png) | Audio source (Bridge TCP / Bluetooth), microphone source (Car / Phone). |
| [07-settings-audio-tab-scrolled](07-settings-audio-tab-scrolled.png) | Scrolled: call quality (Normal / Clear / HD). |

## Settings — Diagnostics Tab
Remote diagnostics toggle, link to full diagnostics dashboard.

| Screenshot | Description |
|------------|-------------|
| [08-settings-diagnostics-tab](08-settings-diagnostics-tab.png) | Remote diagnostics enable toggle, "Open Diagnostics Dashboard" button. |

## Diagnostics — System Tab
Device info, display specs, video decoder capabilities.

| Screenshot | Description |
|------------|-------------|
| [09-diagnostics-system-tab-top](09-diagnostics-system-tab-top.png) | Device info (Android 13, Google emulator), display resolution/DPI/system bars, H.264/H.265/VP9 decoder list with HW/SW indicators. |
| [09-diagnostics-system-tab-scrolled](09-diagnostics-system-tab-scrolled.png) | Scrolled: remaining decoder entries. |

## Diagnostics — Network Tab
Bridge connection status and TCP channel states.

| Screenshot | Description |
|------------|-------------|
| [10-diagnostics-network-tab](10-diagnostics-network-tab.png) | Bridge host/port, session state, control/video/audio TCP channel states. |

## Diagnostics — Bridge Tab
Bridge identity, uptime, video/audio statistics.

| Screenshot | Description |
|------------|-------------|
| [11-diagnostics-bridge-tab](11-diagnostics-bridge-tab.png) | Bridge name/version (shows "—" when not connected), video decoder stats (codec, resolution, FPS, bitrate, frames), audio player stats. |

## Diagnostics — Car Tab (Vehicle Information)
Live VHAL data: powertrain, energy, EV driving, environment, property subscription status.

| Screenshot | Description |
|------------|-------------|
| [12-diagnostics-car-tab-top](12-diagnostics-car-tab-top.png) | Powertrain (speed, gear, parking brake), Energy (EV battery %, energy Wh, charge rate/state/time, charge port, range). |
| [12-diagnostics-car-tab-scrolled1](12-diagnostics-car-tab-scrolled1.png) | Scrolled: Environment (night mode, outside temp, ignition, distance units), VHAL Property Status showing subscribed vs not-exposed-by-HAL for each property. |

## Diagnostics — Logs Tab
Real-time log viewer with severity filtering.

| Screenshot | Description |
|------------|-------------|
| [13-diagnostics-logs-tab](13-diagnostics-logs-tab.png) | Log entries with severity filter chips (VERBOSE/DEBUG/INFO/WARN/ERROR), timestamp, tag, and message columns. Clear button. |

## Safe Area Editor
Visual editor for Android Auto safe area insets.

| Screenshot | Description |
|------------|-------------|
| [15-safe-area-editor](15-safe-area-editor.png) | Drag-handle editor for adjusting AA safe area boundaries (top/bottom/left/right). |

## Content Inset Editor
Visual editor for Android Auto hard content cutoff insets.

| Screenshot | Description |
|------------|-------------|
| [16-content-inset-editor](16-content-inset-editor.png) | Drag-handle editor for adjusting AA content inset boundaries. |

---

**Note:** Screenshots of the live Android Auto streaming view are best taken from a real car display with the bridge connected and a phone streaming.
