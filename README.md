<p align="center">
  <img src="assets/play_store_512.png" alt="OpenAutoLink" width="128">
</p>

# OpenAutoLink

> Wireless Android Auto for AAOS head units. No extra hardware.
>
> **⚠️ Under active development.** Core car connections and AA functionality are working. See [Known Issues](#known-issues) for current limitations.

[![CI](https://github.com/mossyhub/openautolink/actions/workflows/ci.yml/badge.svg)](https://github.com/mossyhub/openautolink/actions/workflows/ci.yml)
[![Release](https://github.com/mossyhub/openautolink/actions/workflows/release-apk.yml/badge.svg)](https://github.com/mossyhub/openautolink/releases/latest)
[![Buy Me A Coffee](https://img.shields.io/badge/Buy%20Me%20A%20Coffee-support-yellow?logo=buymeacoffee&logoColor=white)](https://buymeacoffee.com/mossyhub)

OpenAutoLink runs the full Android Auto protocol stack natively on an AAOS head unit using the [aasdk](https://github.com/opencardev/aasdk) C++ library via JNI. No SBC, no USB adapter, no extra hardware — the car and phone talk directly over WiFi.

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

## Walkthrough

See the full installation and setup walkthrough video on YouTube:

[![OpenAutoLink Walkthrough](https://img.youtube.com/vi/AmQOL05EM5k/0.jpg)](https://www.youtube.com/watch?v=AmQOL05EM5k)

> **Discuss on XDA:** [OpenAutoLink — Wireless Android Auto for AAOS (GM EVs)](https://xdaforums.com/t/open-source-openautolink-wireless-android-auto-bridge-for-aaos-gm-evs.4785192/)

## Contents

- [Why This Exists](#why-this-exists)
- [How It Works](#how-it-works)
- [Features](#features)
- [EV Range Estimates](#ev-range-estimates)
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

OpenAutoLink embeds the [aasdk](https://github.com/opencardev/aasdk) v1.6 C++ library directly into the AAOS app via JNI. The native layer handles the full AA protocol pipeline — SSL handshake, encryption, message framing, and channel multiplexing — while the Kotlin layer manages transport, video rendering, audio playback, and UI.

### Connection Modes

OpenAutoLink supports two connection modes. Pick one in Settings → Connection on **both** apps (they must match).

**Car Hotspot mode (default, recommended):**
The car's built-in WiFi hotspot is the network. One or more phones join it as clients. The companion app on each phone advertises itself via mDNS and a tiny identity probe; the car app discovers all connected phones, picks the preferred one (or shows a picker), and dials it directly over TCP.

- ✅ **Multi-phone**: two drivers' phones can be connected to the car at once. Switch active phone with one tap.
- ✅ **Zero hotspot toggling**: phones treat the car's WiFi like home WiFi — saved once, auto-rejoins forever.
- ✅ **Fast cold-start**: car wakes → AP comes up immediately → phones auto-rejoin → projection resumes.
- Requires a vehicle with a built-in WiFi hotspot (most modern GM EVs include one).

**Phone Hotspot mode:**
The phone is the access point; the car is a client. Single-phone optimized — simpler if your car doesn't have a built-in hotspot or if you don't want to use it.

```
┌─────────────────┐                              ┌──────────────────────────────┐
│   Android Phone  │                              │   Car Head Unit (AAOS)       │
│                  │                              │                              │
│  OAL Companion   │◀── mDNS / identity probe ──▶│   Kotlin: transport, UI,     │
│  joins car AP    │◀── AA protocol (TCP) ──────▶│   video, audio, sensors      │
│  (Car Hotspot)   │                              │          ▼                    │
│                  │                              │   C++ JNI: aasdk v1.6        │
│         or       │                              │   SSL → Cryptor → Messenger  │
│                  │                              │   → AA channels              │
│  USB cable       │◀── AOA v2 (bulk USB) ──────▶│                              │
│  (direct)        │                              │                              │
└─────────────────┘                              └──────────────────────────────┘
```

## Features

- **Car Hotspot mode (default)** — phones join the car's built-in WiFi like home WiFi. Multi-phone support: switch the active phone with one tap, no hotspot toggling
- **Phone Hotspot mode** — phone is the AP, car is the client. Simpler single-phone fallback for cars without a built-in hotspot
- **USB cable support** — AOA v2 direct connection for wired setups
- **aasdk v1.6 native protocol** — battle-tested C++ AA library via JNI, not a reimplementation
- **EV battery data in Android Auto** — battery %, range, fuel type, charge port forwarded from VHAL into AA. Google Maps shows battery level alongside navigation
- **H.264, H.265, and VP9** video with auto-negotiation. Up to 4K with AA Developer Mode
- **PCM and AAC-LC audio** — PCM for compatibility, AAC-LC for ~10× WiFi bandwidth reduction
- **Pixel-perfect display adaptation** — (Still a work in progress) auto-computed AA scaling for wide and ultra-wide AAOS screens to the full screen isused without stretching UI.
- **Per-purpose audio volume** — separate sliders for media, navigation, and assistant
- **Custom key remapping** — map any physical button to any AA action
- **Microphone enhancement** — NoiseSuppressor, AGC, AcousticEchoCanceler
- **Instrument cluster** — turn-by-turn navigation and media metadata on supported vehicles
- **Full sensor suite** — GPS, accelerometer, gyroscope, compass, EV energy model (21 sensor types)
- **Steering wheel controls** — media, voice, and DPAD forwarded to AA
- **Configurable display** — fullscreen/windowed, safe area insets, DPI, margins, scaling mode
- **Stats overlay** — codec, resolution, FPS, bitrate, WiFi band, decoder info
- **Automatic reconnect** — car sleep → wake → projection resumes with no user interaction
- **Built-in diagnostics** — USB device scanner, network probe, remote log server (TCP 6555), VHAL browser

## EV Range Estimates

Native AAOS Google Maps has a private, per-vehicle EV profile (charge curves, aerodynamics, real DCFC power) it uses to predict battery-on-arrival. Apps cannot read that profile. OpenAutoLink builds the next best thing: a tunable energy model from real VHAL data plus an EPA-derived profile database, sent to Maps as the standard `VehicleEnergyModel` sensor.

Open it from **Settings → EV** (its own tab in the Settings screen).

- **Detected vehicle card** — looks up `Make|Model|Year` from VHAL against a bundled database of 46 popular EVs (Blazer EV, Lyriq, Hummer EV, Mach-E, F-150 Lightning, Model 3/Y, IONIQ 5/6, EV6, EV9, ID.4, Rivian R1T/R1S, Polestar 2/3/4, Volvo EX30/EX90, BMW i4/iX, Mercedes EQE/EQS, Honda Prologue, Acura ZDX, and more). When matched, one tap applies EPA Wh/km and DCFC kW.
- **Four driving-rate modes**:
  - **Derived** *(default)* — uses the dashboard's range estimate. Behaves identically to previous releases.
  - **Multiplier** — scale the derived value 0.50× – 1.50× to nudge Maps optimistic or pessimistic.
  - **Manual** — set Wh/km directly with a slider (80–300).
  - **Learned** — auto-tunes from real driving. Computes a rolling Wh/km from `Δbattery ÷ Δdistance` (distance integrated from VHAL speed, since GM blocks `PERF_ODOMETER`). Per-vehicle state persists across car-off and reconnects. Skips ticks while charging or regenerating; rejects outliers; resets after long gaps.
- **Other tunable fields** — auxiliary load, aerodynamic coefficient, reserve %, max charge / discharge power.
- **Live readout** — shows current battery, range, charging power, derived rate, and the effective rate that will reach Maps.
- **Send Now** — push the updated model immediately so changes show up in Maps within seconds.
- **Profile database refresh** *(opt-in)* — fetch the latest profile JSON from network with a 4-second timeout, validated and cached locally. Defaults to OFF so the head unit doesn't depend on internet.

> The bundled profiles ship with the APK and work fully offline. Updates land via app releases or the manual refresh button — never as a silent background fetch.

## What You Need

| Item | Notes |
|------|-------|
| **AAOS vehicle** | Tested on 2024 Chevrolet Blazer EV. Other GM EVs likely work |
| **Android phone** | Running the OpenAutoLink Companion app |
| **Google Play Console account** | To publish the AAOS app to your car |

That's it. No SBC, no Ethernet adapter, no extra hardware.

### Phone Setup

Install the **OpenAutoLink Companion** app on your phone. It handles:
- Starting the TCP server for the head unit to discover and connect to automatically.
- Android Auto auto-start once TCP connection from the car is made.

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
# Windows
cd companion
..\gradlew assembleDebug
adb install -r build/outputs/apk/debug/*.apk
```
```bash
# Linux / macOS
cd companion
../gradlew assembleDebug
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
   # Windows (uses DPAPI-saved credentials)
   .\scripts\bundle-release.ps1
   ```
   ```bash
   # Linux / macOS (uses env-var credentials — see scripts/linux/README.md)
   export OAL_KEYSTORE_PASS='...'
   export OAL_KEY_PASS='...'
   scripts/linux/bundle-release.sh
   ```
6. Upload the AAB to Play Console, publish, and install on the car via Play Store.
7. Grant the **Car Information** permission: Settings → Apps → OpenAutoLink → Permissions.

### 3. Connect

OpenAutoLink defaults to **Car Hotspot mode** on both apps.

#### One-Time Setup

1. **Enable the car's WiFi hotspot.** On the head unit: Settings → Network & Internet → Hotspot (or your manufacturer's equivalent). Note the SSID and password. **No data plan is required** — OpenAutoLink only uses the WiFi network for local communication between the phone and car. The hotspot does not need an active OnStar or cellular data subscription.

2. **Connect your phone to the car's WiFi.** On your phone, go to Settings → WiFi and join the car's hotspot like you would any regular WiFi network. This saves the network so Android can auto-reconnect later. You only need to do this once per car.
   > **Tip:** If your car doesn't have a data plan (e.g. no OnStar subscription), the car's hotspot has no internet. Phones will detect this and pop a notification asking if you want to disconnect or switch networks — tell it to **stay connected**. Modern Android is smart enough to keep the car WiFi for the projection link while routing the phone's own internet traffic over cellular.

3. **Open the Companion app** on your phone and configure it:
   - Under **Car WiFi**, tap **Add Car WiFi** and enter the car's WiFi SSID and password. This allows the app to force-connect your phone to the car's WiFi even when you're already on another network (like your home WiFi in the driveway). Your password is stored locally on the phone only — it is never sent anywhere.
   - Tap **Connect Now** while near the car. This performs a one-time connection that grants the app permission to manage the car's WiFi automatically in the future. If the car WiFi is already a saved network on your phone, this happens silently. Otherwise, Android will show a one-time prompt — tap the car's WiFi to approve.
   - Under **Auto-Start**, the default is **Bluetooth + WiFi Scan (recommended)**. Tap **Select Devices** and check your car's Bluetooth name. This way, the companion service starts automatically when your phone connects to the car's Bluetooth.

4. **Configure Bluetooth for AA (important).** Keep your phone paired to the car's Bluetooth — the Bluetooth connection is what triggers the companion app to start. But go into your **phone's Bluetooth settings → tap the car's name → turn off Media Audio and Phone Calls.** When Android Auto is running, media and phone calls flow through the AA session natively. If you leave the car's Bluetooth media/calls enabled, GM's built-in apps will compete with AA for audio and calls, causing a poor experience (doubled audio, missed calls, steering wheel buttons not working correctly).

5. **Open OpenAutoLink on the car.** The phone chooser appears with every phone the car can see. Tap your phone to connect — that phone is saved as your default for future drives.

#### Day-to-Day

Once setup is complete, the daily experience is fully automatic:

1. Get in the car and start it.
2. Bluetooth pairs automatically → companion service starts.
3. The app connects your phone to the car's WiFi (even if you're still on home WiFi).
4. The car discovers the phone → Android Auto projection appears.

No interaction needed. If your phone was already on the car's WiFi (e.g. you weren't near your home network), it connects even faster since no WiFi switch is needed.

**Multiple drivers?** Both phones can be on the car's WiFi at the same time. The car connects to your default phone automatically. Tap the floating phone icon on the projection screen to switch — the chooser shows every visible phone with online/offline status.

**Changing the default phone.** Tap a different phone in the chooser to switch for this drive, or go to Settings → Connection → Known Phones → "Set Default" to change it permanently.

> **Tip:** The companion app has a built-in **Setup Guide** (tap the info button next to "Car WiFi") that walks you through these steps.

#### Alternative: Phone Hotspot mode

If your car doesn't have a built-in WiFi hotspot:

1. In Settings → Connection on **both** apps, switch to **Phone Hotspot**.
2. Turn on your phone's WiFi hotspot (Settings → Hotspot / Tethering).
3. On the head unit: Settings → Network & Internet → WiFi → join the phone's hotspot.
4. Open the Companion app and tap **Start**.
5. Open OpenAutoLink on the car.

> **Hotspot reconnect note:** When the car wakes from sleep, it should automatically rejoin the phone's hotspot — but in practice this can take 30+ seconds or occasionally fail. If the car doesn't reconnect, toggle the phone hotspot off and back on.

#### USB

1. Plug the phone into the head unit's USB port.
2. OpenAutoLink detects the device and performs the AOA v2 handshake.
3. Android Auto projection starts over the USB connection.

> **GM AAOS USB permission note:** GM head units ask for USB connection permission every time, even with "Always allow" checked. Known GM AAOS bug — there is no workaround.

### 4. Recommended Settings

- **Uninstall or disable music apps on the head unit.** If Spotify, YouTube Music, or another music app is installed on both the AAOS head unit and the phone, media controls (steering wheel buttons, play/pause, skip) can get confused — the car may try to control the AAOS app and the AA app simultaneously. Uninstall or disable the AAOS versions (Settings → Apps) so media controls go exclusively to the phone's AA session.
- **Disable the car's "Hey Google" detection.** The AAOS built-in Google Assistant and Android Auto's assistant will both try to respond to "Hey Google," causing conflicts. Turn off "Hey Google" detection in the car's Settings → Google → Google Assistant. The steering wheel voice button will still trigger the car's built-in assistant (this can't be changed), but "Hey Google" will go exclusively to the AA session on the phone.

### Video and Display

## Resolution Tiers

| Resolution | Codec | Notes |
|-----------|-------|-------|
| 480p (800×480) | H.264 | Always available |
| 720p (1280×720) | H.264 | Always available |
| 1080p (1920×1080) | H.264, H.265, VP9 | Default tier |
| 1440p (2560×1440) | H.265, VP9 | Requires AA Developer Mode |
| 4K (3840×2160) | H.265, VP9 | Requires AA Developer Mode |

By default, the app uses auto-negotiation — the phone picks the best codec and resolution it supports.

## Display Adaptation

OpenAutoLink tries to auto-compute a good  scale for AA UI to use for your screen, but you will want to play with and adjust it for your cars screen. This can be done using the DPI setting in the app. this controls the scale of the AA UI. There is no way to directly tell AA what layout to use, but you will notice by making changes to the DPI there are certain scales at which AA will change the layout...so choose a scale that is visually good on your screen, but also makes AA choose the wide side-by-side layout vs portrait layout (maps is a single wide banner at the top).

> **Blazer EV tip:** Pull the top safe area inset down ~50px.

### Historical

The original architecture used an SBC (single-board computer) running a C++ bridge binary and Python Bluetooth/WiFi scripts to relay Android Auto from the phone to the car over Ethernet. That was replaced by direct mode. The app initially reimplemented the AA protocol in Kotlin, which was then replaced by the current aasdk C++ JNI approach for protocol correctness and performance. The bridge code is preserved on the [`bridge-mode`](https://github.com/mossyhub/openautolink/tree/bridge-mode) branch.

## Documentation

| Doc | Purpose |
|-----|---------|
| [Architecture](docs/architecture.md) | Component islands and system structure |
| [Embedded Knowledge](docs/embedded-knowledge.md) | Lessons from real-car testing — **read before touching video/audio/VHAL** |
| [USB Transport Plan](docs/usb-transport-plan.md) | AOA v2 design and implementation |
| [Local Testing](docs/testing.md) | Emulator testing, remote diagnostics, no-ADB debugging |
| [Wire Protocol](docs/protocol.md) | OAL protocol details (bridge-mode reference) |
| [Multi-Phone Plan](docs/multi-phone.md) | Multi-phone design over Car Hotspot (TCP/mDNS) |
| [HUR Feature Comparison](docs/headunit-revived-feature-comparison.md) | Feature parity tracking vs Headunit Revived |

## Known Issues

- **H.265 video may appear green-tinted** on first connection for 30–45 seconds. May be Qualcomm-specific — not yet confirmed on other SoCs

If you encounter other problems, please [open an issue](https://github.com/mossyhub/openautolink/issues).

## Compatibility

Validated on a **2024 Chevrolet Blazer EV** running AAOS 12L. Other GM EVs on similar AAOS platforms likely work but have not been broadly tested. Non-GM AAOS vehicles may have different restrictions.

The companion app runs on any Android phone over WiFi. The car app and the phone need to be on the same network — either the car's hotspot or the phone's hotspot.

## Acknowledgments

### Core Dependency

- **[opencardev/aasdk](https://github.com/opencardev/aasdk)** — The Android Auto protocol library at the heart of OpenAutoLink. Our [fork](https://github.com/mossyhub/aasdk) (branch `openautolink`) adds NavigationStatus extensions and EV energy model sensor types. The C++ library runs directly on the head unit via JNI.

### Where It Started

- **[metheos/carlink_native](https://github.com/metheos/carlink_native)** / **[lvalen91/carlink_native](https://github.com/lvalen91/carlink_native)** and the **[XDA CarLink thread](https://xdaforums.com/t/carlink.4774308)** inspired the original proof of concept.

### Projects I Learned From

- **[opencardev/openauto](https://github.com/opencardev/openauto)** — head unit emulator architecture.
- **[nickel110/WirelessAndroidAutoDongle](https://github.com/nickel110/WirelessAndroidAutoDongle)** — BT pairing and WiFi credential exchange reference.
- **[andrerinas/headunit-revived](https://github.com/nickel110/headunit-revived)** — AA receiver app reference for protocol implementation and feature ideas.

### On AI Assistance

This project is heavily AI-assisted, but grounded in extensive real hardware testing. The code moves faster with Copilot; the driveway testing, log analysis, and protocol debugging are what determine whether the result is actually good.

## License

TBD
