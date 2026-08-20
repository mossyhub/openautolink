<p align="center">
  <img src="assets/play_store_512.png" alt="OpenAutoLink" width="128">
</p>

# OpenAutoLink

> Wireless Android Auto for AAOS head units. No extra hardware.
>

<a id="aa174"></a>

> # Android Auto 17.4 broke wireless — OpenAutoLink has it working again
>
> **Wireless projection is restored, with sustained video and touch.** It needs a few setup changes.
>
> 17.4 ships `WirelessStartupReceiver` with `android:enabled="false"`, and the activity it forwards to is not exported. The broadcast every third-party wireless implementation relied on is now silently swallowed — `result=0`, no log, no error. Even `adb pm enable` is refused.
>
> That receiver was never the only way in. Factory head units start projection over Bluetooth using Google's own WiFi Projection Protocol, and 17.4 leaves that path untouched. OpenAutoLink now speaks it.
>
> ### What you need to change
>
> **Update both apps**, then:
>
> 1. **Pair the car over Bluetooth.** The Bluetooth handshake is now the only way to tell Android Auto to start and where to connect. No Bluetooth, no wireless.
> 2. **Forget the car and pair again** after updating. Your phone caches which services a paired device offers, and will not see the new one until it re-reads that list.
> 3. **Settings → Transport → Wireless (WPP).** The older Wi-Fi mode uses the startup path Google disabled.
> 4. **Leave Phone Calls enabled** in the car's Bluetooth settings (Media Audio can stay off). The hands-free profile holds the link up; without it the connection drops before the handshake finishes.
> 5. **Clear the companion app's car Wi-Fi setup.** Android Auto joins the car's network itself now — having the companion do it too means both fight over the same radio.
> 6. A manually saved profile for the car's Wi-Fi network is optional. Keep it if your car has an active data plan and you want the phone to use the car for internet. If the car has no internet, or duplicate entries cause rough handoffs, forget the manually saved profile and let Android Auto create its local-only WPP connection. See [Saved car Wi-Fi profile and internet](#saved-car-wi-fi-profile-and-internet).
>
> The companion app is required in this mode — it holds the network path open on the phone side. A walkthrough video is coming shortly.
>
> Background: [#66](https://github.com/mossyhub/openautolink/issues/66)

[![CI](https://github.com/mossyhub/openautolink/actions/workflows/ci.yml/badge.svg)](https://github.com/mossyhub/openautolink/actions/workflows/ci.yml)
[![Release](https://github.com/mossyhub/openautolink/actions/workflows/release-apk.yml/badge.svg)](https://github.com/mossyhub/openautolink/releases/latest)
[![Buy Me A Coffee](https://img.shields.io/badge/Buy%20Me%20A%20Coffee-support-yellow?logo=buymeacoffee&logoColor=white)](https://buymeacoffee.com/mossyhub)

OpenAutoLink runs the full Android Auto protocol stack natively on an AAOS head unit using the [aasdk](https://github.com/opencardev/aasdk) C++ library via JNI. No SBC, no USB adapter, no extra hardware — the car and phone talk directly over WiFi, set up over Bluetooth or by cable.


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

[![OpenAutoLink Walkthrough](https://img.youtube.com/vi/2KcsTZalXcc/0.jpg)](https://youtu.be/2KcsTZalXcc)

> **Discuss on XDA:** [OpenAutoLink — Wireless Android Auto for AAOS (GM EVs)](https://xdaforums.com/t/open-source-openautolink-wireless-android-auto-bridge-for-aaos-gm-evs.4785192/)

## Contents

- [Why This Exists](#why-this-exists)
- [How It Works](#how-it-works)
- [Features](#features)
- [EV Range Estimates](#ev-range-estimates)
- [What You Need](#what-you-need)
- [Quick Start](#quick-start)
- [Video and Display](#video-and-display)
- [Documentation](#documentation)
- [Known Issues](#known-issues)
- [Compatibility](#compatibility)
- [Acknowledgments](#acknowledgments)
- [License](#license)

## Why This Exists

Starting with the 2024 model year, GM dropped Apple CarPlay and Android Auto from its electric vehicles in favor of Google built-in infotainment. OpenAutoLink brings Android Auto back — the car app runs the AA protocol directly, connecting to the phone over WiFi or USB with no intermediate hardware.

## How It Works

OpenAutoLink embeds the [aasdk](https://github.com/opencardev/aasdk) v1.6 C++ library directly into the AAOS app via JNI. The native layer handles the full AA protocol pipeline — SSL handshake, encryption, message framing, and channel multiplexing — while the Kotlin layer manages transport, video rendering, audio playback, and UI.

### Connection Modes

Pick the transport in Settings on the car app.

**Wireless (WPP) — required on Android Auto 17.4 and newer:**
The car publishes a Bluetooth service advertisement; the phone dials back and the two exchange the network details over Bluetooth, the same way factory head units do. The companion app holds the network path open on the phone side. This is the only wireless mode that works on 17.4+ — see the [notice above](#aa174).

**Car Hotspot mode (Android Auto 17.3 and older):**
The car's built-in WiFi hotspot is the network. One or more phones join it as clients. The companion app on each phone advertises itself via mDNS and a tiny identity probe; the car app discovers all connected phones, picks the preferred one (or shows a picker), and dials it directly over TCP.

- ✅ **Multi-phone**: two drivers' phones can be connected to the car at once. Switch active phone with one tap. *(On 17.4+ you switch phones in the car's own Bluetooth settings instead — the privileged APIs an app would need are closed.)*
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

- **Wireless (WPP)** — Bluetooth handshake plus Google's WiFi Projection Protocol, the path factory head units use. Required on Android Auto 17.4+
- **Car Hotspot mode** — phones join the car's built-in WiFi like home WiFi. Multi-phone support, no hotspot toggling (Android Auto 17.3 and older)
- **Phone Hotspot mode** — phone is the AP, car is the client. Simpler single-phone fallback for cars without a built-in hotspot
- **USB cable support** — AOA v2 direct connection for wired setups
- **aasdk v1.6 native protocol** — battle-tested C++ AA library via JNI, not a reimplementation
- **EV battery data in Android Auto** — battery %, range, fuel type, charge port forwarded from VHAL into AA. Google Maps shows battery level alongside navigation
- **H.264, H.265, and VP9** video with auto-negotiation. Up to 4K with AA Developer Mode
- **PCM and AAC-LC audio** — PCM for compatibility, AAC-LC for ~10× WiFi bandwidth reduction
- **Display adaptation** *(work in progress)* — auto-computed AA scaling so wide and ultra-wide AAOS screens use the full panel without stretching the UI
- **Per-purpose audio volume** — separate sliders for media, navigation, and assistant
- **Custom key remapping** — map any physical button to any AA action
- **Microphone enhancement** — NoiseSuppressor, AGC, AcousticEchoCanceler
- **Instrument cluster** — turn-by-turn navigation and media metadata on supported vehicles
- **Full sensor suite** — GPS, accelerometer, gyroscope, compass, EV energy model (21 sensor types)
- **Steering wheel controls** — media, voice, and DPAD forwarded to AA
- **Configurable display** — fullscreen/windowed, safe area insets, DPI, margins, scaling mode
- **Stats overlay** — codec, resolution, FPS, bitrate, WiFi band, decoder info
- **Automatic reconnect** — car sleep → wake → projection resumes on its own
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

Download it from [Releases](https://github.com/mossyhub/openautolink/releases/latest) — grab `openautolink-companion-vX.Y.Z.apk` — or build it yourself from the `companion/` directory.

## Quick Start

### 1. Install the Companion App (Phone)

**Option A — Download the release APK:**
1. Open [Releases](https://github.com/mossyhub/openautolink/releases/latest).
2. Download `openautolink-companion-vX.Y.Z.apk`.
3. Install it on your phone (enable "Install from unknown sources" if prompted).

Release APKs are signed with the project's key, so updates install over each other. A debug build from source uses a different signature and needs the release build uninstalled first.

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

Debug builds are signed with the Android debug key, which is fine for sideloading but will not install over a release build.

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

**On Android Auto 17.4 or newer, use Wireless (WPP)** — Settings → Transport → Wireless (WPP) on the car app. The steps below cover it; the [notice at the top](#aa174) is the short version.

#### One-Time Setup

1. **Enable the car's WiFi hotspot.** On the head unit: Settings → Network & Internet → Hotspot (or your manufacturer's equivalent). Note the SSID and password. **No data plan is required** — OpenAutoLink only uses the WiFi network for local communication between the phone and car. The hotspot does not need an active OnStar or cellular data subscription.

2. **Connect your phone to the car's WiFi.** On your phone, go to Settings → WiFi and join the car's hotspot like you would any regular WiFi network. This saves the network so Android can auto-reconnect later. You only need to do this once per car.
   > **Tip:** If your car doesn't have a data plan (e.g. no OnStar subscription), the car's hotspot has no internet. Phones will detect this and pop a notification asking if you want to disconnect or switch networks — tell it to **stay connected**. Modern Android is smart enough to keep the car WiFi for the projection link while routing the phone's own internet traffic over cellular.

3. **Open the Companion app** on your phone and configure it:
   - **On Android Auto 17.4 or newer, leave Car WiFi empty.** Android Auto joins the car's network itself as part of the Bluetooth handshake. Adding an entry here makes the companion app compete with it for the same radio and the connection drops repeatedly. See the [17.4 notice](#aa174).
   - On older Android Auto versions only: under **Car WiFi**, tap **Add Car WiFi**, enter the car's SSID and password, then tap **Connect Now** while near the car. Your password is stored locally on the phone only — it is never sent anywhere.
   - Under **Auto-Start**, the default is **Bluetooth + WiFi Scan (recommended)**. Tap **Select Devices** and check your car's Bluetooth name. This way, the companion service starts automatically when your phone connects to the car's Bluetooth.

4. **Configure Bluetooth for AA (important).** Keep your phone paired to the car's Bluetooth. On Android Auto 17.4+ this is mandatory — the Bluetooth handshake is the only remaining way to start wireless projection.

   Go into your **phone's Bluetooth settings → tap the car's name → turn off Media Audio.** When Android Auto is running, media flows through the AA session natively; leaving the car's Bluetooth media enabled makes GM's built-in apps compete with AA for audio (doubled audio, steering wheel buttons not working correctly).

   > **On 17.4+, leave Phone Calls enabled.** The hands-free profile is what keeps the Bluetooth link up between the phone and the car. Turning it off lets the link drop, and the handshake that starts projection never runs. On older Android Auto versions you could disable both.

5. **On 17.4+: forget the car in your phone's Bluetooth settings and pair again.** Your phone caches which services a paired device offers. Until it re-reads that list it will not see the new Android Auto service and the handshake never starts. Do this *after* updating both apps.

6. **Choose whether to keep a manually saved profile for the car's Wi-Fi network.** Keep it if the car has an active internet plan and you want Android to use that WiFi for general internet. If the car has no internet, or duplicate entries cause unreliable handoffs, forget the manually saved copy and let Android Auto request its own local-only WPP connection. See [Saved car Wi-Fi profile and internet](#saved-car-wi-fi-profile-and-internet).

7. **Open OpenAutoLink on the car.** On 17.4+ set Settings → Transport → **Wireless (WPP)**; projection starts once the Bluetooth handshake completes. On older versions the phone chooser appears — tap your phone, and it is saved as your default.

#### Day-to-Day

Once setup is complete, the daily experience is fully automatic:

1. Get in the car and start it.
2. Bluetooth connects → the companion service starts.
3. Android Auto joins the car's WiFi as part of the Bluetooth handshake (17.4+), or the companion app does it (older versions).
4. The car finds the phone → projection appears.

No interaction needed.

#### Saved Car Wi-Fi Profile and Internet

The normal saved WiFi profile and Android Auto's WPP request are two ways to use
the same car access point:

- **Matching saved profile already connected:** Android's WiFi framework can give
  Gearhead the existing primary connection instead of creating another local-only
  connection. Gearhead validates the advertised SSID and BSSID and binds only its
  projection socket to the returned `Network`. Field logs prove this path works:
  the phone was already reachable on the car's Wi-Fi before the Bluetooth handshake, and
  WPP projection then started through that association.
- **No usable saved profile:** Gearhead requests the car's Wi-Fi network with a
  `WifiNetworkSpecifier` that does not require internet. On Android 14+ it asks to
  prefer a secondary STA when the phone supports concurrent local-only WiFi;
  otherwise Android may switch the single WiFi STA. Projection remains bound to
  the requested network, while general internet normally stays on cellular or a
  separate primary WiFi connection.
- **Both associations present:** dual-STA phones can keep an internet-capable
  primary WiFi connection and a secondary local-only WPP connection at the same
  time. Android decides whether to reuse the existing association or create the
  secondary one; OpenAutoLink and the companion do not choose.

Therefore:

- **Car has an active data plan:** keeping the car's Wi-Fi as a normal saved network is
  reasonable. If Android validates it, the car WiFi may remain the phone's normal
  internet path while Android Auto uses that network for projection.
- **Car has no internet:** forgetting the old manually saved profile is usually
  cleaner, especially if duplicate entries or unreliable handoffs appear. WPP
  still supplies the credentials and requests a local-only connection; internet
  stays on cellular or another primary network.

MAC privacy is separate. A normal saved profile exposes Android's
Device/Randomised MAC setting. The WPP app-scoped request does not; Android uses
its automatic MAC policy.

**Multiple drivers?** On 17.4+ the car projects to whichever phone it is currently connected to over Bluetooth, so switching phones means switching the connection in the **car's own Bluetooth settings** — the APIs an app would need to do that are privileged and closed to us. The phone list on the projection screen still shows every phone it can see, and tapping one retries the connection to it.

> **Multi-phone identity:** Give every phone a unique Bluetooth device name. In each phone's OAL companion, set **Phone identity → Friendly name** to exactly match that phone's Bluetooth name. The car uses this first match to bind the Bluetooth phone to the companion's stable `phone_id`, preventing one reachable companion from being selected for another phone's WPP handshake.

On Android Auto 17.3 and older, both phones can be on the car's WiFi at once and the floating phone icon switches between them directly. Settings → Connection → Known Phones → "Set Default" changes the preferred phone permanently.

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

> **GM AAOS USB permission note:** GM head units re-ask for USB permission on every connect, even with **"Always allow" / "Use by default" checked.** This is a bug in GM's AAOS build — the grant is never persisted — and there is no workaround from the app side.
>
> Two dialogs can appear for a single plug-in: one raised by the system (the one with the checkbox, triggered when the phone re-enumerates into accessory mode) and one raised by OpenAutoLink. As of v0.1.372 the app collapses its own duplicate requests so you should see at most one OpenAutoLink prompt per connect. The system dialog is outside our control.

### 4. Recommended Settings

- **Uninstall or disable music apps on the head unit.** If Spotify, YouTube Music, or another music app is installed on both the AAOS head unit and the phone, media controls (steering wheel buttons, play/pause, skip) can get confused — the car may try to control the AAOS app and the AA app simultaneously. Uninstall or disable the AAOS versions (Settings → Apps) so media controls go exclusively to the phone's AA session.
- **Disable the car's "Hey Google" detection.** The AAOS built-in Google Assistant and Android Auto's assistant will both try to respond to "Hey Google," causing conflicts. Turn off "Hey Google" detection in the car's Settings → Google → Google Assistant. The steering wheel voice button will still trigger the car's built-in assistant (this can't be changed), but "Hey Google" will go exclusively to the AA session on the phone.

#### Keep phone calls inside Android Auto (GM vehicles)

By default, GM's native Phone app takes over the center display when a call arrives or is answered. Change these two settings once to keep Android Auto on screen and use Android Auto's Answer, Decline, and in-call controls:

1. Open the car's native **Phone** app.
2. Tap the **Settings** gear.
3. Turn **Active Call** **OFF**. Its description is **“Show active call view when answering call.”**
4. Turn **Privacy** **ON**. Its description is **“Only show call alerts in cluster.”**

The result is the intended Android Auto call experience: Android Auto displays and controls the call, while the car's native Bluetooth hands-free system carries the microphone and speaker audio.

> **Leave the Bluetooth profile named Phone Calls enabled** for your phone. It carries the call audio and, on Android Auto 17.4+, keeps the Bluetooth link needed to start wireless projection. Do not confuse it with the two Phone-app display settings above.

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

OpenAutoLink auto-computes a scale for the Android Auto UI, but you will want to tune it for your car's screen using the **DPI** setting.

There is no way to tell Android Auto which layout to use directly. What you can do is change the DPI — at certain scales AA switches layout. Aim for a scale that both looks right on your panel and gets AA into the wide side-by-side layout rather than the portrait one (in the wide layout, Maps is a single banner across the top).

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

- **Android Auto 17.4+ needs the new setup** — wireless works, but only over Wireless (WPP) with Bluetooth paired and the car re-paired after updating. See the [notice at the top](#aa174).
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
- **[headunit-revived](https://github.com/andrerinas/headunit-revived)** — AA receiver app reference for protocol implementation and feature ideas.

### On AI Assistance

This project is heavily AI-assisted, but grounded in extensive real hardware testing. The code moves faster with Copilot; the driveway testing, log analysis, and protocol debugging are what determine whether the result is actually good.

## License

TBD
