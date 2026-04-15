<p align="center">
  <img src="assets/play_store_512.png" alt="OpenAutoLink" width="128">
</p>

# OpenAutoLink

[![CI](https://github.com/mossyhub/openautolink/actions/workflows/ci.yml/badge.svg)](https://github.com/mossyhub/openautolink/actions/workflows/ci.yml)

OpenAutoLink is an open-source wireless Android Auto bridge for AAOS head units. An SBC handles the phone's Android Auto session over WiFi, then streams video, audio, and control data to an app on the car's display over Ethernet. The goal is to restore a native-feeling Android Auto experience on vehicles that ship with AAOS but without built-in Android Auto support.

<p align="center">
  <img src="docs/screenshots/AA-Streaming-Screenshot.jpg" alt="Android Auto streaming on a 2024 Blazer EV via OpenAutoLink" width="720">
  <br>
  <em>Android Auto streaming wirelessly on a 2024 Chevrolet Blazer EV</em>
</p>

> This is a hobby project under active development. Core features are implemented and working on real hardware, but the project is not yet polished or broadly validated across vehicles.

> **Discuss on XDA:** [OpenAutoLink — Wireless Android Auto bridge for AAOS (GM EVs)](https://xdaforums.com/t/open-source-openautolink-wireless-android-auto-bridge-for-aaos-gm-evs.4785192/) — questions, feedback, and build reports welcome.

## Contents

- [Why This Exists](#why-this-exists)
- [What It Does](#what-it-does)
- [How It Works](#how-it-works)
- [Why Not a USB Adapter?](#why-not-a-usb-adapter)
- [Video, Resolution, and Display Behavior](#video-resolution-and-display-behavior)
- [What You Need](#what-you-need)
- [Quick Start](#quick-start)
- [Repository Layout](#repository-layout)
- [Documentation](#documentation)
- [Status](#status)
- [Known Issues](#known-issues)
- [Compatibility](#compatibility)
- [Acknowledgments](#acknowledgments)
- [License](#license)

## Why This Exists

Starting with the 2024 model year, GM dropped Apple CarPlay and Android Auto from its electric vehicles in favor of Google built-in infotainment. OpenAutoLink exists to bring Android Auto back by bridging a phone's Android Auto session to the car's AAOS head unit over the network, without relying on proprietary USB adapter hardware.

The current design is purpose-built for this setup:

- The phone connects wirelessly to the SBC over Bluetooth and WiFi.
- The SBC runs the Android Auto session and relays it over TCP.
- The AAOS app renders video and audio, forwards touch and car data, and manages reconnection.

## What It Does

- Wireless Android Auto over Bluetooth + WiFi, no phone cable required
- Up to 1080p60 by default, with 1440p and 4K available through AA Developer Mode and supported codecs
- Automatic display adaptation for wide and irregular AAOS displays
- Audio forwarding for media, navigation, phone calls, and voice assistant
- Touch input, steering wheel controls, and microphone forwarding
- Instrument-cluster integration for turn-by-turn and media metadata
- Multi-phone pairing and one-tap switching
- Automatic reconnect after car sleep / power loss
- Bridge auto-update from GitHub Releases
- Fully open-source app, bridge, protocol, and deployment scripts

<details>
<summary>📱 App Screenshots</summary>
<br>
<p>
  <a href="docs/screenshots/01-projection-screen-idle.png"><img src="docs/screenshots/01-projection-screen-idle.png" alt="Projection screen (idle)" width="400"></a>
  <a href="docs/screenshots/02-settings-connection-tab-top.png"><img src="docs/screenshots/02-settings-connection-tab-top.png" alt="Settings — Connection" width="400"></a>
</p>
<p>
  <a href="docs/screenshots/03-settings-phones-tab.png"><img src="docs/screenshots/03-settings-phones-tab.png" alt="Settings — Phones" width="400"></a>
  <a href="docs/screenshots/04-settings-bridge-tab-top.png"><img src="docs/screenshots/04-settings-bridge-tab-top.png" alt="Settings — Bridge" width="400"></a>
</p>
<p>
  <a href="docs/screenshots/05-settings-display-tab-top.png"><img src="docs/screenshots/05-settings-display-tab-top.png" alt="Settings — Display" width="400"></a>
  <a href="docs/screenshots/06-settings-video-tab-top.png"><img src="docs/screenshots/06-settings-video-tab-top.png" alt="Settings — Video" width="400"></a>
</p>
<p>
  <a href="docs/screenshots/07-settings-audio-tab-top.png"><img src="docs/screenshots/07-settings-audio-tab-top.png" alt="Settings — Audio" width="400"></a>
  <a href="docs/screenshots/09-diagnostics-system-tab-top.png"><img src="docs/screenshots/09-diagnostics-system-tab-top.png" alt="Diagnostics — System" width="400"></a>
</p>
</details>

## How It Works

An SBC bridges the phone's Android Auto session to the car over WiFi + Ethernet. The car runs the OpenAutoLink app and the SBC runs the bridge. [DietPi](https://dietpi.com/) is the recommended OS — it is lightweight, boots quickly from eMMC, and supports a wide range of ARM64 boards out of the box.

```
Android Phone ──WiFi TCP:5277──▶                    ┌── Control :5288 (JSON lines)
                  BT pairing    ▶ SBC Bridge ──Eth──▶├── Video   :5290 (binary frames)
                                                    └── Audio   :5289 (binary frames)
                                                          ▼
                                                  Car Head Unit App (AAOS)
                                                    Renders video/audio
                                                    Forwards touch/GNSS/VHAL
```

The short version is simple: the phone talks Android Auto to the SBC, and the SBC talks OpenAutoLink's TCP protocol to the AAOS app.

## Why Not a USB Adapter?

The CPC200-CCPA and similar USB adapters proved that Android Auto on AAOS was possible, but they are closed-source dongles with fixed assumptions about resolution, capabilities, and car integration. OpenAutoLink replaces that model with a software-defined bridge that can adapt to the vehicle and evolve over time.

| | CPC200 / USB Adapter | OpenAutoLink (SBC Bridge) |
|---|---|---|
| **USB permission prompt** | Every startup on some GM vehicles | Never, because the car sees a network device instead of a USB accessory session |
| **Resolution** | Usually fixed at 800×480 or sometimes 720p | Up to 1080p60 by default, with 1440p and 4K available through AA Developer Mode and supported codecs |
| **Codec** | H.264 only | H.264, H.265, and VP9 protocol support, with auto-negotiation |
| **Display adaptation** | Fixed output, often stretched or letterboxed | Reads AAOS display dimensions and cutout insets to compute safe areas and aspect handling |
| **Vehicle data to AA** | None or minimal | Full forwarding pipeline from VHAL and sensors; AA and Maps appear to use only a small subset today, but the broader data path is already there for future support |
| **EV support** | None | Sends EV-related data such as battery percentage, range, fuel type, and connector type |
| **Navigation cluster** | Not available | Turn-by-turn on the instrument cluster |
| **Media cluster** | Not available | Album art and track info on cluster-capable vehicles |
| **Multi-phone** | Not available | Pair multiple phones, choose a default, switch in app |
| **Microphone** | Typically phone-side only | Car mic or phone mic, configurable |
| **Steering wheel buttons** | Limited | Media and voice-related forwarding where AAOS allows it |
| **Updates** | Replace or reflash hardware | Bridge updates via GitHub Releases, app updates via Play Console |
| **Wide display / ultra-wide** | Usually poor | Native adaptation for very wide AAOS displays |
| **Customization** | None | Insets, display mode, video scaling, DPI, and related settings |
| **Source code** | Closed | Fully open-source |
| **Cost** | Adapter hardware | Commodity SBC + USB Ethernet adapter |

## Video, Resolution, and Display Behavior

### Video Modes

By default, OpenAutoLink uses auto-negotiation. The bridge offers supported codec and resolution tiers in the Android Auto service discovery response, and the phone picks the best combination it supports.

| Resolution | Codec | Notes |
|-----------|-------|-------|
| 480p (800×480) | H.264 | Always available |
| 720p (1280×720) | H.264 | Always available |
| 1080p (1920×1080) | H.264, H.265, VP9 | Main tier |
| 1440p (2560×1440) | H.265, VP9 | Requires AA Developer Mode on the phone |
| 4K (3840×2160) | H.265, VP9 | Requires AA Developer Mode on the phone |

H.264 generally tops out at 1080p for practical phone encoder support. H.265 and VP9 are the paths to 1440p and 4K.

<details>
<summary>🎬 Video Settings Screenshots</summary>
<br>
<p>
  <a href="docs/screenshots/06-settings-video-tab-top.png"><img src="docs/screenshots/06-settings-video-tab-top.png" alt="Video — Auto Negotiate" width="400"></a>
  <a href="docs/screenshots/14-settings-video-manual-top.png"><img src="docs/screenshots/14-settings-video-manual-top.png" alt="Video — Manual Mode" width="400"></a>
</p>
<p>
  <a href="docs/screenshots/14-settings-video-manual-scrolled1.png"><img src="docs/screenshots/14-settings-video-manual-scrolled1.png" alt="Video — Resolution Tiers" width="400"></a>
  <a href="docs/screenshots/06-settings-video-tab-scrolled1.png"><img src="docs/screenshots/06-settings-video-tab-scrolled1.png" alt="Video — Scaling & Margins" width="400"></a>
</p>
</details>

### Enabling Higher Resolutions

1. On the phone, enable [Android Auto Developer Mode](https://developer.android.com/training/cars/testing#developer-mode).
2. Open Android Auto settings, tap the version entry 10 times, then open Developer settings.
3. Set Video resolution to the highest available tier.
4. In the OpenAutoLink app, go to Settings → Video.
5. Leave Auto enabled to let the phone pick the best supported mode, or manually choose a codec and resolution.

### Display Adaptation

OpenAutoLink is designed for AAOS displays that are not simple 16:9 rectangles. It reads display dimensions and cutout insets, computes safe areas, and keeps interactive Android Auto UI away from clipped edges while still letting maps and backgrounds fill the available space.

For the 2024 Blazer EV, the bridge is pre-configured with a right-side stable inset in `bridge/sbc/openautolink.env` so interactive elements stay away from the curved bezel.

<details>
<summary>🖥️ Display & Layout Screenshots</summary>
<br>
<p>
  <a href="docs/screenshots/05-settings-display-tab-top.png"><img src="docs/screenshots/05-settings-display-tab-top.png" alt="Display Mode & Safe Area" width="400"></a>
  <a href="docs/screenshots/15-safe-area-editor.png"><img src="docs/screenshots/15-safe-area-editor.png" alt="Safe Area Editor" width="400"></a>
</p>
<p>
  <a href="docs/screenshots/16-content-inset-editor.png"><img src="docs/screenshots/16-content-inset-editor.png" alt="Content Inset Editor" width="400"></a>
  <a href="docs/screenshots/05-settings-display-tab-scrolled1.png"><img src="docs/screenshots/05-settings-display-tab-scrolled1.png" alt="Display — Features & Toggles" width="400"></a>
</p>
</details>

## What You Need

### Hardware

| Item | Notes |
|------|-------|
| **Single-board computer (SBC)** | ARM64 with onboard Ethernet, 5 GHz WiFi, and Bluetooth 4.0+ |
| **USB Ethernet adapter** | USB-C strongly recommended so it plugs directly into the car |
| **Short Ethernet cable** | Connects the SBC's onboard Ethernet to the USB adapter |
| **Power for the SBC** | USB-C power supply or in-car USB-C power source |
| **Storage** | eMMC preferred for faster boot; microSD works but is slower |

### Choosing an SBC

The bridge relays already-encoded video and audio, so raw CPU performance matters less than boot time, WiFi quality, and reliable networking.

| Priority | Why |
|----------|-----|
| **Onboard Ethernet NIC** | Required for the car-side network link |
| **5 GHz WiFi (onboard)** | Required for stable wireless Android Auto streaming |
| **Bluetooth 4.0+** | Required for pairing and WiFi credential exchange |
| **eMMC storage** | Faster and more reliable than microSD; meaningfully reduces boot time |
| **CPU / RAM** | Mostly affects boot time; modest ARM64 hardware is enough |
| **Size** | Smaller is easier to hide in the console |

**Recommended OS: [DietPi](https://dietpi.com/)** — lightweight Debian-based, fast to boot from eMMC, and supports a wide range of ARM64 boards. Use the headless server image.

#### Development / tested boards

The primary development board is a **Khadas VIM4** (Amlogic A311D2, 8 GB RAM, 32 GB eMMC, Wi-Fi 6, GigE). The **Raspberry Pi 5 / CM5** is also tested and works well. Both are overkill for this workload — the bridge does not benefit from their CPU headroom — but they are convenient for development.

#### Recommended boards (DietPi, all criteria met)

These boards hit the right balance of cost, boot speed, and wireless performance — all have onboard GigE, onboard 5 GHz WiFi + Bluetooth, and eMMC:

| Board | Approx. Price | SoC | RAM | WiFi | Notes |
|-------|--------------|-----|-----|------|-------|
| **Orange Pi 5B** | ~$65–$80 | RK3588S | 8 GB | Wi-Fi 6 (onboard) | Best value sweet spot; 16–128 GB eMMC onboard |
| **Radxa ROCK 5B** | ~$90–$130 | RK3588 | 4–16 GB | Wi-Fi 6E (M.2 module) | Dual 2.5 GbE; eMMC slot |
| **Orange Pi 3B** | ~$35–$45 | RK3566 | 1–8 GB | Wi-Fi 5 (onboard) | Good lower-cost option; eMMC slot |

#### Lower-end boards (pending validation on DietPi)

These meet the spec on paper and are cheap enough to be worth trying. They will be added to the recommended list above if they perform well enough for daily use:

| Board | Approx. Price | Notes |
|-------|--------------|-------|
| **Orange Pi Zero 3** | ~$20–$30 | Allwinner H618; onboard GigE + Wi-Fi 5 + BT 5.0; up to 4 GB RAM; compact |
| **Radxa ROCK 3A / 3C** | ~$35–$50 | RK3566; eMMC slot; onboard WiFi on some variants |

### Physical Connection

```
┌─────────────┐    Ethernet     ┌─────────────────┐         ┌──────────────┐
│     SBC     │───── cable ────▶│  USB Ethernet   │──USB-C─▶│  Car USB     │
│  (bridge)   │                 │    adapter      │         │    port      │
│             │                 └─────────────────┘         │ (head unit)  │
│  WiFi AP ◀──── phone connects via BT + WiFi               └──────────────┘
│  Power ◀────── USB-C from 12V adapter or spare USB port
└─────────────┘
```

1. Connect the SBC's onboard Ethernet port to the USB Ethernet adapter.
2. Plug the USB Ethernet adapter into the car's USB port.
3. Power the SBC from a 12 V USB-C adapter or a spare USB power source.
4. Pair the phone over Bluetooth and let it join the SBC's 5 GHz WiFi AP.

Blazer EV note: the USB-C port inside the center console armrest compartment is the one known to enumerate USB network devices to the head unit.

### Placement

The SBC, adapter, and cable can usually live entirely inside the center console compartment. If the SBC needs more airflow, use a slightly longer Ethernet cable and move it to a better-ventilated spot while keeping the USB NIC on the working car port.

## Quick Start

> There is no public Play Store listing today. Each user must publish the AAOS app through their own Google Play Console account.

### Bridge Setup

No local build is required. The SBC install script downloads the latest prebuilt bridge binary from GitHub Releases.

1. Flash [DietPi](https://dietpi.com/) (or any ARM64 Linux server image) onto your SBC.
2. Connect the SBC's onboard Ethernet to your router or laptop to get a DHCP address.
3. SSH in and run:

```bash
curl -fsSL https://raw.githubusercontent.com/mossyhub/openautolink/main/bridge/sbc/install.sh | sudo bash
```

The installer downloads packages, deploys the bridge, and applies the network configuration. When it finishes, it prints a summary showing that the onboard Ethernet is now a static car connection and the WiFi radio is a phone hotspot — **the SBC no longer has internet or general SSH access through those adapters.**

After a reboot, all services start automatically and the bridge is ready for the car.

See the [Bridge Setup Guide](bridge/sbc/BUILD.md) for configuration, SSH access after install, and troubleshooting.

### App Setup

Because this is an AAOS app rather than a normal phone app, installation on the car goes through your own Google Play Console account and an AAOS release track.

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

6. Add testers to the AAOS track in Play Console.
7. Upload the AAB and publish the release.
8. Accept the tester opt-in invitation for each Google account used on the car.
9. Install the app from the Play Store on the head unit.
10. Manually grant the **Car Information** permission in Settings → Apps → OpenAutoLink → Permissions.

### Run Tests

```powershell
.\gradlew :app:testDebugUnitTest
```

## Repository Layout

| Component | Language | Location |
|-----------|----------|----------|
| **Car App** | Kotlin / Compose | `app/` |
| **Bridge** | C++20 | `bridge/openautolink/headless/` |
| **BT/WiFi Services** | Python | `bridge/openautolink/scripts/` |
| **SBC Deployment** | Bash / systemd | `bridge/sbc/` |
| **aasdk fork** | C++ | `external/opencardev-aasdk/` |

## Documentation

| Doc | Purpose |
|-----|---------|
| [Architecture](docs/architecture.md) | Component islands and system structure |
| [Wire Protocol](docs/protocol.md) | Control, video, and audio protocol details |
| [Embedded Knowledge](docs/embedded-knowledge.md) | Lessons learned from real-car testing |
| [Networking](docs/networking.md) | Phone, car, and SSH network model |
| [Local Testing](docs/testing.md) | Emulator and SBC testing workflow |
| [Bridge OTA Updates](docs/bridge-update.md) | Update flow and security model |
| [Work Plan](docs/work-plan.md) | Remaining work and future ideas |
| [Bridge Build Guide](bridge/sbc/BUILD.md) | Build and deployment details for the SBC |

## Status

Active development. Core features are implemented and working on real hardware on a 2024 Chevrolet Blazer EV:

- Video
- Audio
- Touch input
- Vehicle and sensor data forwarding
- Cluster navigation
- Media metadata
- Microphone support
- Steering wheel control forwarding
- Automatic reconnect

<details>
<summary>🔍 Diagnostics & Vehicle Data Screenshots</summary>
<br>
<p>
  <a href="docs/screenshots/09-diagnostics-system-tab-top.png"><img src="docs/screenshots/09-diagnostics-system-tab-top.png" alt="Diagnostics — System" width="400"></a>
  <a href="docs/screenshots/10-diagnostics-network-tab.png"><img src="docs/screenshots/10-diagnostics-network-tab.png" alt="Diagnostics — Network" width="400"></a>
</p>
<p>
  <a href="docs/screenshots/11-diagnostics-bridge-tab.png"><img src="docs/screenshots/11-diagnostics-bridge-tab.png" alt="Diagnostics — Bridge" width="400"></a>
  <a href="docs/screenshots/12-diagnostics-car-tab-top.png"><img src="docs/screenshots/12-diagnostics-car-tab-top.png" alt="Diagnostics — Car / VHAL" width="400"></a>
</p>
<p>
  <a href="docs/screenshots/12-diagnostics-car-tab-scrolled1.png"><img src="docs/screenshots/12-diagnostics-car-tab-scrolled1.png" alt="Diagnostics — VHAL Property Status" width="400"></a>
  <a href="docs/screenshots/13-diagnostics-logs-tab.png"><img src="docs/screenshots/13-diagnostics-logs-tab.png" alt="Diagnostics — Logs" width="400"></a>
</p>
</details>

## Known Issues

- **Steering wheel controls on GM EVs:** the left-side rocker currently maps to skip forward and play/pause only. Previous-track is not exposed by GM. The steering-wheel voice button is consumed by the system before the app can intercept it.
- **Occasional audio jitter / cutout:** intermittent and still under investigation.
- **"Unsupported device" popup on GM EVs:** the USB Ethernet adapter can trigger a brief cosmetic warning even though networking still works.
- **USB gadget networking does not work:** presenting the SBC directly as a USB network gadget has not worked in testing; the external USB Ethernet adapter remains the working approach.
- **The Android Auto stream green** UI may start out blocky and green, but will self recover in 10-20 seconds. Still fighting this one...

## Compatibility

OpenAutoLink is not yet known to work across all AAOS vehicles. It is currently validated on a 2024 Chevrolet Blazer EV. Other GM vehicles on similar AAOS platforms may work, but that has not been verified broadly, and non-GM AAOS vehicles may impose different restrictions around USB networking or app access.

## Acknowledgments

OpenAutoLink is built from scratch, but it draws heavily on prior open-source Android Auto research and implementation work.

### Where It Started

- **[metheos/carlink_native](https://github.com/metheos/carlink_native)** / **[lvalen91/carlink_native](https://github.com/lvalen91/carlink_native)** and the **[XDA CarLink thread](https://xdaforums.com/t/carlink.4774308)** inspired the original proof of concept and helped demonstrate that AAOS-side Android Auto bridging was feasible.

### Core Dependency

- **[opencardev/aasdk](https://github.com/opencardev/aasdk)** by [Michal Szwaj (f1x)](https://github.com/nickel110) is the Android Auto protocol library underneath the bridge. OpenAutoLink maintains a [fork](https://github.com/mossyhub/aasdk) with NavigationStatus extensions.

### Projects I Learned From

- **[opencardev/openauto](https://github.com/opencardev/openauto)** for head unit emulator architecture and session patterns.
- **[Crankshaft](https://github.com/nickel110/crankshaft-ng)** for the embedded Android Auto distribution model and deployment ideas.
- **[nickel110/WirelessAndroidAutoDongle](https://github.com/nickel110/WirelessAndroidAutoDongle)** for Bluetooth pairing and WiFi credential exchange reference points.

### On AI Assistance

This project is heavily AI-assisted, but it is grounded in extensive real hardware testing. The code moves faster with Copilot; the driveway testing, log analysis, and protocol debugging are still what determine whether the result is actually good.

## License

TBD
