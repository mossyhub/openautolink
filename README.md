<p align="center">
  <img src="assets/play_store_512.png" alt="OpenAutoLink" width="128">
</p>

# OpenAutoLink

> **Direct Mode — No Bridge Required**
> As of v0.1.177, OpenAutoLink runs Android Auto directly on the AAOS head unit — no SBC, no bridge hardware, no Ethernet cable. The phone connects wirelessly via Google Nearby Connections through the OpenAutoLink Companion app on the phone. The old bridge architecture has been fully removed.

[![CI](https://github.com/mossyhub/openautolink/actions/workflows/ci.yml/badge.svg)](https://github.com/mossyhub/openautolink/actions/workflows/ci.yml)

OpenAutoLink is an open-source wireless Android Auto solution for AAOS head units. The car app speaks the Android Auto protocol directly to the phone over WiFi — no SBC bridge, no USB adapter, no extra hardware. A companion app on the phone handles discovery and connection.

<p align="center">
  <img src="docs/screenshots/AA-Streaming-Screenshot.jpg" alt="Android Auto streaming on a 2024 Blazer EV via OpenAutoLink" width="720">
  <br>
  <em>Android Auto streaming wirelessly on a 2024 Chevrolet Blazer EV</em>
</p>

<p align="center">
  <img src="docs/screenshots/AA-EV-Battery-Maps.jpg" alt="Google Maps showing EV battery percentage via OpenAutoLink" width="720">
  <br>
  <em>Google Maps displaying the car's EV battery level — real vehicle data forwarded through OpenAutoLink into Android Auto</em>
</p>

> **First-of-its-kind EV integration:** OpenAutoLink forwards real EV battery percentage, range, fuel type, and charge port data from the car into Android Auto. Google Maps uses this to show battery level alongside navigation — something no other aftermarket solution provides.

> **Discuss on XDA:** [OpenAutoLink — Wireless Android Auto for AAOS (GM EVs)](https://xdaforums.com/t/open-source-openautolink-wireless-android-auto-bridge-for-aaos-gm-evs.4785192/)

## Contents

- [Why This Exists](#why-this-exists)
- [How It Works](#how-it-works)
- [Features](#features)
- [What You Need](#what-you-need)
- [Quick Start](#quick-start)
- [Video and Display](#video-and-display)
- [Repository Layout](#repository-layout)
- [Documentation](#documentation)
- [Status](#status)
- [Known Issues](#known-issues)
- [Compatibility](#compatibility)
- [Acknowledgments](#acknowledgments)
- [License](#license)

## Why This Exists

Starting with the 2024 model year, GM dropped Apple CarPlay and Android Auto from its electric vehicles in favor of Google built-in infotainment. OpenAutoLink brings Android Auto back — the car app runs the AA protocol directly, connecting to the phone over WiFi with no intermediate hardware.

## How It Works

The OpenAutoLink AAOS app runs the full Android Auto protocol stack natively. A companion app on the phone advertises via Google Nearby Connections. The car discovers the phone, upgrades to WiFi Direct, and starts an AA session — all wirelessly, no cables.

```
┌─────────────────┐                              ┌─────────────────────────┐
│   Android Phone  │   Google Nearby Connections  │   Car Head Unit (AAOS)  │
│                  │◀──── BT discovery ──────────▶│                         │
│  OAL Companion   │◀──── WiFi Direct upgrade ──▶│   OpenAutoLink App      │
│  app advertises   │◀──── AA protocol (TCP) ───▶│   renders video/audio   │
│                  │                              │   forwards touch/GNSS   │
└─────────────────┘                              └─────────────────────────┘
```

No SBC. No Ethernet. No bridge binary. The phone and car talk directly.

## Features

- **Zero hardware** — no SBC, no USB adapter, no cables. Phone to car, wirelessly
- **EV battery data in Android Auto** — battery %, range, fuel type, charge port forwarded from VHAL into AA. Google Maps shows battery level alongside navigation
- **H.264, H.265, and VP9** video with auto-negotiation. Up to 4K with AA Developer Mode
- **AAC-LC audio** — compressed audio reduces WiFi bandwidth ~10× vs PCM
- **Multi-phone support** — set a default phone, switch between phones with an overlay chooser
- **Pixel-perfect display adaptation** for wide and ultra-wide AAOS screens
- **Per-purpose audio volume** — separate sliders for media, navigation, and assistant
- **Custom key remapping** — map any physical button to any AA action
- **Microphone enhancement** — NoiseSuppressor, AGC, AcousticEchoCanceler
- **Instrument cluster** — turn-by-turn navigation and media metadata on supported vehicles
- **WiFi band detection** — shows 2.4 GHz vs 5 GHz in the stats overlay
- **Bluetooth service announcement** — phone can discover the car's BT for HFP/A2DP
- **Phone status** — signal strength and call state parsed from the AA protocol
- **Full sensor suite** — GPS, accelerometer, gyroscope, compass, EV energy model (21 sensor types)
- **Steering wheel controls** — media, voice, and DPAD forwarded to AA
- **Configurable display** — fullscreen/windowed, safe area insets, DPI, margins, scaling mode
- **Stats overlay** — codec, resolution, FPS, bitrate, WiFi band, decoder info
- **Automatic reconnect** — car sleep → wake → projection resumes

## What You Need

| Item | Notes |
|------|-------|
| **AAOS vehicle** | Tested on 2024 Chevrolet Blazer EV. Other GM EVs likely work |
| **Android phone** | Running the OpenAutoLink Companion app |
| **Google Play Console account** | To publish the AAOS app to your car |

That's it. No SBC, no Ethernet adapter, no extra hardware.

### Phone Setup

Install the **OpenAutoLink Companion** app on your phone. It handles:
- Nearby Connections advertising (phone discovery)
- Android Auto Service auto-start detection
- Custom device name for multi-phone identification

You can either download a prebuilt APK from [GitHub Actions](https://github.com/mossyhub/openautolink/actions/workflows/build-companion.yml) (click the latest run → Artifacts → `companion-debug-apk`) or build it yourself from the `companion/` directory.

## Quick Start

### 1. Install the Companion App (Phone)

**Option A — Download prebuilt APK:**
1. Go to [Build Companion APK](https://github.com/mossyhub/openautolink/actions/workflows/build-companion.yml) on GitHub Actions.
2. Click the latest successful run.
3. Download the `companion-debug-apk` artifact.
4. Unzip and install the APK on your phone (enable "Install from unknown sources" if prompted).

**Option B — Build from source:**
```powershell
cd companion
..\gradlew assembleDebug
adb install -r build/outputs/apk/debug/*.apk
```

The companion APK is signed with the Android debug key, which is fine for sideloading.

### 2. Build and Publish the Car App (AAOS)

Because this is an AAOS app, installation on the car goes through your own Google Play Console account:

1. Create a [Google Play Console](https://play.google.com/console/) developer account.
2. Create a new app and configure an AAOS release track.
3. Change the package name in `app/build.gradle.kts` from `com.openautolink.app` to your own unique ID.
4. Generate an upload keystore:
   ```powershell
   .\scripts\create-upload-keystore.ps1
   ```
5. Build and sign the release AAB:
   ```powershell
   .\scripts\bundle-release.ps1
   ```
6. Upload the AAB to Play Console, publish, and install on the car via Play Store.
7. Grant the **Car Information** permission: Settings → Apps → OpenAutoLink → Permissions.

### 3. Connect

1. Open the Companion app on the phone and tap **Start**.
2. Open OpenAutoLink on the car — it discovers the phone automatically.
3. Android Auto projection starts within seconds.

## Video and Display

### Resolution Tiers

| Resolution | Codec | Notes |
|-----------|-------|-------|
| 480p (800×480) | H.264 | Always available |
| 720p (1280×720) | H.264 | Always available |
| 1080p (1920×1080) | H.264, H.265, VP9 | Default tier |
| 1440p (2560×1440) | H.265, VP9 | Requires AA Developer Mode |
| 4K (3840×2160) | H.265, VP9 | Requires AA Developer Mode |

By default, the app uses auto-negotiation — the phone picks the best codec and resolution it supports.

### Display Adaptation

OpenAutoLink auto-computes pixel aspect ratio for wide AAOS displays so the 16:9 AA stream renders correctly. A safe area editor lets you push UI inward from screen edges to avoid curved bezels.

> **Blazer EV tip:** 1440p at 230 DPI works well. Pull the top safe area inset down ~50px.

## Repository Layout

| Component | Language | Location |
|-----------|----------|----------|
| **Car App** | Kotlin / Compose | `app/` |
| **Companion App** | Kotlin / Compose | `companion/` |
| **Documentation** | Markdown | `docs/` |
| **Build Scripts** | PowerShell / Bash | `scripts/` |

### Historical (removed)

The bridge architecture (SBC + C++ binary + Python BT scripts) was removed in the `feature/direct-mode` branch. The app now runs the AA protocol directly. Bridge code remains in the `main` branch history for reference.

## Documentation

| Doc | Purpose |
|-----|---------|
| [Architecture](docs/architecture.md) | Component islands and system structure |
| [Wire Protocol](docs/protocol.md) | AA wire protocol details |
| [Embedded Knowledge](docs/embedded-knowledge.md) | Lessons from real-car testing |
| [Multi-Phone Plan](docs/multi-phone-nearby.md) | Multi-phone Nearby Connections design |
| [HUR Feature Comparison](docs/headunit-revived-feature-comparison.md) | Feature parity tracking vs HUR |
| [Local Testing](docs/testing.md) | Emulator and device testing workflow |

## Status

Active development, stable for daily use on a 2024 Chevrolet Blazer EV.

**Working:**
- Video (H.264/H.265/VP9, up to 4K)
- Audio (PCM and AAC-LC)
- Touch input
- Microphone (with NS + AGC + AEC)
- Vehicle data forwarding (VHAL → AA)
- EV energy model (battery %, range, fuel type, charge port)
- Cluster navigation (turn-by-turn)
- Media metadata (cluster + MediaSession)
- Steering wheel controls
- Multi-phone (default + chooser)
- Automatic reconnect
- WiFi band detection

## Known Issues

- **Steering wheel controls on GM EVs:** the left-side rocker maps to skip forward and play/pause only. Previous-track is not exposed by GM. The voice button is consumed by the system before the app can intercept it.
- **"Unsupported device" popup:** if using a USB Ethernet adapter for the legacy bridge mode, GM shows a brief cosmetic warning.

## Compatibility

Validated on a **2024 Chevrolet Blazer EV** running AAOS 12L. Other GM EVs on similar AAOS platforms likely work but have not been broadly tested. Non-GM AAOS vehicles may have different restrictions.

The companion app runs on any Android phone with Google Play Services (Nearby Connections requires it).

## Acknowledgments

### Where It Started

- **[metheos/carlink_native](https://github.com/metheos/carlink_native)** / **[lvalen91/carlink_native](https://github.com/lvalen91/carlink_native)** and the **[XDA CarLink thread](https://xdaforums.com/t/carlink.4774308)** inspired the original proof of concept.

### Projects I Learned From

- **[opencardev/aasdk](https://github.com/opencardev/aasdk)** — Android Auto protocol library (used in the now-removed bridge).
- **[opencardev/openauto](https://github.com/opencardev/openauto)** — head unit emulator architecture.
- **[nickel110/WirelessAndroidAutoDongle](https://github.com/nickel110/WirelessAndroidAutoDongle)** — BT pairing and WiFi credential exchange reference.
- **[andrerinas/headunit-revived](https://github.com/nickel110/headunit-revived)** — AA receiver app reference for protocol implementation and feature ideas.

### On AI Assistance

This project is heavily AI-assisted, but grounded in extensive real hardware testing. The code moves faster with Copilot; the driveway testing, log analysis, and protocol debugging are what determine whether the result is actually good.

## License

TBD
