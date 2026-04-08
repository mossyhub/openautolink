<p align="center">
  <img src="assets/play_store_512.png" alt="OpenAutoLink" width="128">
</p>

# OpenAutoLink

[![CI](https://github.com/mossyhub/openautolink/actions/workflows/ci.yml/badge.svg)](https://github.com/mossyhub/openautolink/actions/workflows/ci.yml)

**An open-source wireless Android Auto bridge for AAOS head units.** An SBC handles the phone's Android Auto session over WiFi, then streams video, audio, and touch to an app on your car's display over Ethernet — no janky, closed-source and hacky USB adapter hardware required.

> **Heads up — this is a hobby project under active development.** It might work great, it might not work at all. Features listed below are implemented but not all are battle-tested. The goal is production quality, but it's not there yet. I'm building this because it's fun and because I want Android Auto back in my car — if that sounds like your kind of adventure, give it a try.

## Why Not a CPC200 / USB Adapter?

The CPC200-CCPA and similar USB adapters are closed-source hardware dongles that relay Android Auto over a proprietary USB protocol. They got the community started (and credit to them for proving the concept), but they have fundamental limitations that an open, software-defined bridge doesn't:

| | CPC200 / USB Adapter | OpenAutoLink (SBC Bridge) |
|---|---|---|
| **USB permission prompt** | Every startup — GM's AAOS prompts "Allow access to USB device?" each time, even if you check "Don't ask again" (known GM bug) | Never — connects over Ethernet/TCP, no USB device permission involved |
| **Resolution** | Fixed 800×480 (some 720p) | Up to 1080p60 by default, 1440p/4K with AA Developer Mode + H.265 or VP9 |
| **Codec** | H.264 only, hardcoded | H.264, H.265, VP9 — auto-negotiated by default, phone picks best match. Manual override available |
| **Display adaptation** | None — fixed output, black bars or stretched | Auto-reads AAOS display dimensions + cutout insets, computes pixel aspect ratio and crop margins. Maps render edge-to-edge on any screen shape |
| **Vehicle data to AA** | None or minimal | Full sensor pipeline: speed, gear, parking brake, night mode, EV battery %, range, temperature, GPS, accelerometer, gyroscope, compass, tire pressure, HVAC — all from real VHAL, forwarded to phone |
| **EV support** | None | Declares fuel type + connector type from VHAL to AA. Google Maps can use battery % and range for EV routing |
| **Navigation cluster** | Not possible | Turn-by-turn on instrument cluster — maneuver icons, lane guidance, distance, road names, ETA |
| **Media cluster** | Not possible | Album art + track info on cluster display |
| **Multi-phone** | Not possible | Pair multiple phones, set default, switch with one tap |
| **Microphone** | Phone only (BT HFP) | Car mic or phone mic — configurable |
| **Steering wheel buttons** | Limited or none | Full media + voice button forwarding (with GM F-key mapping) |
| **Updates** | Buy new hardware | Bridge auto-updates from GitHub. App updates via Play Store |
| **Wide display / ultra-wide** | Bad — letterboxed or distorted | Native — pixel aspect ratio auto-computed from display AR. Tested on 2914×1134 (2.57:1) GM screen |
| **Customization** | None | Safe area insets, content insets, display mode, video scaling, DPI — all configurable in app settings |
| **Source code** | Closed, encrypted firmware | Fully open-source — app, bridge, protocol, deployment |
| **Cost** | $50–$150 for adapter hardware | ~$35 for a Pi 4 + a $10 USB Ethernet adapter |

The short version: CPC200 adapters are dumb relays with fixed resolution and no car integration. OpenAutoLink is a full Android Auto head unit implementation that adapts to your car's display, reads your car's sensors, talks to your instrument cluster, and updates itself over the air — built from open-source software running on commodity hardware.

### Features

- Wireless Android Auto — phone connects via Bluetooth + WiFi, no cables
- Up to 1080p60 video with H.264, H.265, or VP9 codec support. 1440p and 4K with AA Developer Mode enabled on the phone
- Auto video negotiation — bridge offers all supported codecs and resolutions, phone picks the best match automatically
- Auto wide-display adaptation — reads AAOS display dimensions and cutout insets, computes safe areas so AA fills your screen correctly on any head unit
- Display safe zone / insets — auto-computed from AAOS display cutout (curved bezels, sloped edges), keeps interactive AA UI in the safe area while maps render edge-to-edge. Manually tweakable via visual drag editors
- Full audio: media, navigation prompts, phone calls, voice assistant
- Touch, steering wheel controls, and microphone input forwarded to the phone
- Vehicle data (speed, gear, fuel/EV range, GPS, etc.) sent to Android Auto
- Navigation turn-by-turn displayed on the instrument cluster (lane guidance, cue text, maneuver icons)
- Album art and track info on the cluster display (Spotify, etc.)
- Multi-phone support: pair multiple phones, set a default, switch between them with one tap
- Auto-reconnect on car startup — car on → brief "Connecting..." → projection appears
- One-command SBC install
- Remote diagnostics — structured logs and telemetry streamed to bridge over SSH (no ADB needed on GM)
- Bridge auto-update — app checks GitHub Releases on connect, downloads and pushes new bridge binary over TCP. No user action needed for bridge-only releases
- Fully open-source — app, bridge, protocol, and deployment scripts

### Video Codecs & Higher Resolutions

By default, OpenAutoLink uses **auto-negotiation** — the bridge offers multiple codecs and resolution tiers in the Service Discovery Response, and the phone picks the best combination it supports. No configuration needed.

| Resolution | Codec | Notes |
|-----------|-------|-------|
| 480p (800×480) | H.264 | Always available |
| 720p (1280×720) | H.264 | Always available |
| 1080p (1920×1080) | H.264, H.265, VP9 | Default tier. H.264 is universally supported |
| 1440p (2560×1440) | H.265, VP9 | Requires AA Developer Mode on phone |
| 4K (3840×2160) | H.265, VP9 | Requires AA Developer Mode on phone |

**H.264** maxes out at 1080p — phone encoders generally don't support H.264 at higher resolutions.
**H.265 (HEVC)** and **VP9** support encoding up to 4K and are required for 1440p/4K tiers.

#### Enabling Higher Resolutions

1. **On your phone**, enable [AA Developer Mode](https://developer.android.com/training/cars/testing#developer-mode):
   - Open Android Auto settings → About → tap "Version" 10 times → OK
   - Open the overflow menu (⋮) → Developer settings → set **Video resolution** to the highest available
2. **In the OpenAutoLink app**, go to Settings → Video:
   - Leave **Auto** on (recommended) — the phone will pick 1080p or higher based on what it supports
   - Or turn Auto off and manually select a codec + resolution (H.265 + 4K, etc.)

The app's video decoder auto-detects the codec from the stream — if the bridge negotiates H.265 but the app was configured for H.264, it seamlessly switches to the correct decoder.

Starting with the 2024 model year, GM dropped Apple CarPlay and Android Auto from their electric vehicles (Blazer EV, Equinox EV, Silverado EV, Lyriq, etc.) in favor of Google built-in infotainment. GM has indicated this will expand to all GM vehicles in the 2025-2026+ timeframe. **OpenAutoLink brings Android Auto back** to these vehicles by bridging a phone's Android Auto session to the car's AAOS head unit over the network — no USB adapter hardware needed.

An SBC (Raspberry Pi 5, Khadas VIM4, etc.) bridges your phone's Android Auto session to your car's display over WiFi + Ethernet. The car runs the OpenAutoLink app, the SBC runs the bridge.

```
Android Phone ──WiFi TCP:5277──▶                    ┌── Control :5288 (JSON lines)
                  BT pairing    ▶ SBC Bridge ──Eth──▶├── Video   :5290 (binary frames)
                                                    └── Audio   :5289 (binary frames)
                                                          ▼
                                                  Car Head Unit App (AAOS)
                                                    Renders video/audio
                                                    Forwards touch/GNSS
```

## What You Need

### Hardware

| Item | Notes |
|------|-------|
| **Single-board computer (SBC)** | ARM64 with onboard Ethernet, 5 GHz WiFi, and Bluetooth 4.0+. See [SBC guidance](#choosing-an-sbc) below |
| **USB Ethernet adapter** | USB-C strongly recommended so it plugs directly into the car's USB-C port. Any chipset that works with Linux (ASIX, Realtek, etc.) is fine |
| **Short Ethernet cable** | Connects the SBC's onboard Ethernet port to the USB adapter. 1–2 ft / 30–60 cm is plenty |
| **Power for the SBC** | USB-C power supply. In the car, a 12 V cigarette lighter USB-C adapter works, or use a spare USB port if one is available |
| **microSD card (16 GB+)** | Or eMMC, depending on your SBC. Holds the Linux OS and bridge software |

### Choosing an SBC

The bridge is lightweight — it relays an already-encoded video/audio stream, so raw CPU and RAM matter much less than you'd think. What matters most:

| Priority | Why |
|----------|-----|
| **Onboard Ethernet NIC** | Required. The SBC's built-in Ethernet connects to the car via the USB adapter + cable. USB Ethernet-to-USB Ethernet is a headache — avoid SBCs that only have WiFi |
| **5 GHz WiFi (802.11ac or better)** | Required. The phone connects to the SBC's WiFi AP. 5 GHz gives the throughput and low latency needed for smooth 1080p60 video. 2.4 GHz alone is not sufficient |
| **Bluetooth 4.0+** | Required for phone pairing and WiFi credential exchange |
| **CPU / RAM** | Minimal impact on streaming — mostly affects boot time. A quad-core ARM64 with 1–2 GB RAM is more than enough |
| **Size** | Smaller is better — it lives hidden in your center console |

**Tested SBCs:**
- **Raspberry Pi 5 / CM5** — primary development board. Compact, reliable, good WiFi
- **Khadas VIM4** — also works, overkill for this use case

**Budget-friendly SBCs that should work:**

Any ARM64 board with onboard Ethernet, 5 GHz WiFi, and Bluetooth 4.0+ should work. Here are some popular, lower-cost options:

| Board | Approx. Price | Notes |
|-------|--------------|-------|
| **Raspberry Pi 4 Model B (2 GB)** | ~$35 | The classic. Gigabit Ethernet, dual-band WiFi, BT 5.0. Widely available with excellent Linux support. The 2 GB model is plenty for the bridge |
| **Orange Pi 5** | ~$55 | RK3588S, Gigabit Ethernet, WiFi 6 + BT 5.0 (via onboard module). Fast boot, compact form factor |
| **Orange Pi 3B** | ~$30 | RK3566, Gigabit Ethernet, dual-band WiFi + BT 5.0. Good budget pick with solid mainline Linux support |
| **ROCK Pi 4 Model B** | ~$50 | RK3399, Gigabit Ethernet, dual-band WiFi + BT 5.0. Well-supported by Armbian |
| **Radxa ROCK 3C** | ~$35 | RK3566, Gigabit Ethernet, dual-band WiFi + BT 5.0. Compact and well-priced |

Most ARM64 SBCs with the above specs should work. The bridge binary is a generic aarch64 Linux executable.

### How It Connects

```
┌─────────────┐    Ethernet     ┌─────────────────┐         ┌──────────────┐
│     SBC     │───── cable ────▶│  USB Ethernet    │──USB-C─▶│  Car USB     │
│  (bridge)   │                 │    adapter       │         │    port      │
│             │                 └─────────────────┘         │ (head unit)  │
│  WiFi AP ◀──── phone connects via BT + WiFi               └──────────────┘
│  Power ◀────── USB-C from 12V adapter or spare USB port
└─────────────┘
```

1. **Ethernet cable** goes from the SBC's onboard Ethernet port to the USB Ethernet adapter
2. **USB Ethernet adapter** plugs into the car's USB port — the head unit sees it as a network device and assigns it an IP on the `192.168.222.x` subnet. In my 24 Blazer, it is always assigning 192.168.222.108 no matter what USB NIC I have tested with.
3. **SBC gets power** from a 12 V USB-C adapter (cigarette lighter outlet) or a spare USB port in the car
4. **Phone** pairs with the SBC over Bluetooth, joins the SBC's 5 GHz WiFi AP, and streams Android Auto wirelessly

> **Blazer EV note:** Use the USB-C port inside the **center console armrest compartment** (the one behind the lid), not the two USB ports on the front of the center console. The armrest port is the one that enumerates USB network devices to the head unit. Other GM EVs may have a similar arrangement — check which USB port your AAOS head unit can see network devices on.

> **Display safe area:** The 2024 Blazer EV has a curved/tapered right bezel that clips content near the right edge of the display. The bridge is pre-configured with display insets (`OAL_AA_INIT_STABLE_INSETS=0,0,0,110` in `/etc/openautolink.env`) that tell Android Auto to keep interactive UI (buttons, cards, text) away from the curved edge while still rendering maps and backgrounds edge-to-edge. If you're using a different vehicle, adjust or clear this value — see [bridge/sbc/openautolink.env](bridge/sbc/openautolink.env) for details.

### Where to Put It

The SBC, adapter, and cable are small enough to live entirely inside the center console compartment. Tuck the SBC in, run power from a nearby outlet, and close the lid. Nothing is visible when the console is shut. The phone stays in your pocket — it connects wirelessly. If you SBC needs to breath more, just use a thin, longer ethernet cable and easily move it out to the front USB ports for power.

## Components

| Component | Language | Location |
|-----------|----------|----------|
| **Car App** | Kotlin/Compose (AAOS) | `app/` |
| **Bridge** | C++20 (headless binary) | `bridge/openautolink/headless/` |
| **BT/WiFi Services** | Python | `bridge/openautolink/scripts/` |
| **SBC Deployment** | Bash/systemd | `bridge/sbc/` |
| **aasdk** | C++ (submodule) | `external/opencardev-aasdk/` |

## Quick Start

> **No public Play Store listing.** Each user must publish the app through their own Google Play Console account (see below). The long-term goal is to get the app certified and published publicly, but whether that's possible (AAOS certification requirements, Google approval, etc.) is unknown.

### Bridge (SBC)

No building required — the install script downloads the latest pre-built binary from GitHub Releases. Follow the [Bridge Setup Guide](bridge/sbc/BUILD.md) which walks you through flashing an OS, getting SSH access, and running:

```bash
curl -fsSL https://raw.githubusercontent.com/mossyhub/openautolink/main/bridge/sbc/install.sh | sudo bash
```

### App (AAOS Head Unit)

Because this is an AAOS app (not a standard phone app), getting it onto your car requires publishing through your own Google Play Console account with an AAOS-specific release track. GM's AAOS head units have no known way to sideload APKs — ADB is locked down and there's no accessible install mechanism outside the Play Store. This is more involved than a typical Android app:

1. **Create a [Google Play Console](https://play.google.com/console/) developer account** ($25 one-time fee)

2. **Create a new app** in the Play Console and set up an **AAOS release track** (car-specific distribution)

3. **Change the package name** — Google Play requires a unique application ID. In [app/build.gradle.kts](app/build.gradle.kts), change `com.openautolink.app` to your own unique package name (e.g. `com.yourname.openautolink`)

4. **Generate a signing key**:
   ```powershell
   .\scripts\create-upload-keystore.ps1
   ```
   This creates a keystore at `secrets/upload-key.jks`. Keep this file safe — you need it for every release.

5. **Build and sign the release AAB**:
   ```powershell
   .\scripts\bundle-release.ps1
   ```
   The signed `.aab` will be in `app/build/outputs/bundle/release/`.

6. **Add testers** — In the Play Console, go to your AAOS release track's **Testers** tab. Create an email list and add the Google accounts that are signed in on your car's head unit (even your own account)

7. **Upload the AAB** to your Play Console AAOS release track, fill in the required store listing details, and publish the release

8. **Accept the test invite** — After publishing, each tester (including yourself) must open the opt-in link from the Play Console testers page and accept the invitation. Without this step, the app will not appear on the car

9. **Install** — Once the invite is accepted, the app should appear on the Play Store on your car's head unit (may take a few hours to propagate). You can also trigger it by searching for your app name in the Play Store on the head unit

10. **Grant the "Car Information" permission** — After installing, go to **Settings → Apps → OpenAutoLink → Permissions** on the head unit and manually enable the **"Car Information"** permission. This permission (used for vehicle speed, gear, fuel/EV range, etc.) cannot be granted automatically and must be enabled by the user through the system settings

### Run Tests
```powershell
.\gradlew :app:testDebugUnitTest
```

## Documentation

| Doc | Purpose |
|-----|---------|
| [Architecture](docs/architecture.md) | Component islands, milestone plan |
| [Wire Protocol](docs/protocol.md) | OAL protocol spec (control + video + audio) |
| [Embedded Knowledge](docs/embedded-knowledge.md) | Hardware lessons from real-car testing |
| [Networking](docs/networking.md) | Three-network architecture (phone, car, SSH) |
| [Local Testing](docs/testing.md) | Emulator + SBC setup, in-car testing workflow |
| [Bridge OTA Updates](docs/bridge-update.md) | Auto-update system — design, flow, security, developer workflow |
| [Work Plan](docs/work-plan.md) | Remaining items, car testing unknowns, future features |
| [Bridge Build Guide](bridge/sbc/BUILD.md) | SBC build and deployment |
## Status

Active development. All core features are implemented and working on real hardware (2024 Blazer EV): video, audio, touch, vehicle data, cluster navigation, media metadata, microphone, steering wheel controls, and auto-reconnect.

## Known Issues

- **Steering wheel controls (GM EVs):** The left-side steering wheel rocker works with Android Auto — **up = skip forward**, **down = play/pause**. There is currently no way to skip backward (previous track) from the steering wheel, as GM only exposes two media key events for this rocker. The **voice button** on the steering wheel cannot be intercepted — GM's AAOS consumes it at the system level and launches the built-in Google Assistant before the app ever sees it. As a workaround, tap the microphone icon in the Android Auto UI on screen to trigger the phone's voice assistant (Gemini, Google Assistant, etc.).

- **Occasional audio jitter/cutout:** Audio may briefly cut out or jitter during playback. This is intermittent and under investigation.

- **H.265 and VP9 codecs not yet working:** Only H.264 is currently functional and is the default. H.265 and VP9 are selectable in Settings → Video but will not produce a working stream yet. This is a bridge-side issue and will be fixed soon. In practice, H.264 at 1080p60 is already excellent — the alternative codecs offer marginally better compression but won't noticeably improve the experience for most users.

- **"Unsupported device" popup on GM EVs:** When the USB Ethernet adapter is plugged into the car's USB-C port, GM's AAOS briefly shows an "Unsupported device" notification. This is cosmetic — the head unit doesn't recognize the adapter as a known accessory (phone, USB drive, etc.), but it still enumerates it as a network interface and assigns it an IP. The popup dismisses itself after a few seconds and has no effect on functionality.

- **USB gadget networking does not work:** I attempted to use Linux USB gadget mode (RNDIS/ECM/NCM) so the SBC itself would present as a USB network device to the car, eliminating the need for a separate USB Ethernet adapter. After extensive testing, GM's head unit never enumerated any gadget configuration. The external USB Ethernet adapter approach is the only method that works.

## Compatibility

**Not known to be universally compatible with all AAOS vehicles.** Currently tested only on a **2024 Chevrolet Blazer EV**, which enumerates a USB NIC, assigns it an IP, and allows network traffic to reach apps. Other GM vehicles on the same AAOS head unit platform likely work, but this has not been verified. Non-GM AAOS vehicles may have different USB networking behavior or restrictions that prevent this approach from working.

## Acknowledgments

OpenAutoLink is built from scratch, but it wouldn't exist without the open-source Android Auto and AirPlay/CarPlay communities. I want to recognize the projects I learned from and built upon.

### Where It Started

- **[metheos/carlink_native](https://github.com/metheos/carlink_native)** / **[lvalen91/carlink_native](https://github.com/lvalen91/carlink_native)** and the **[XDA CarLink thread](https://xdaforums.com/t/carlink.4774308)** — This is where the whole journey began. The CPC200-CCPA USB adapter was a clever piece of hardware that bridged Android Auto to AAOS head units over USB. I started by trying to replace the CPC200 hardware with an SBC running the same protocol so I could use their app. The TCP approach came from discovering that the car assigns an IP to any USB-C NIC you plug in — I sat in the car with a laptop, sniffed ARP traffic to find the assigned address, and realized I could skip USB gadget protocol emulation entirely and just talk TCP. From there it was a lot of quick proof-of-concept apps pushed through my own Google Play Console account to figure out what AAOS would and wouldn't let me do. A billion tokens later, I threw out all the CPC200 protocol code and started from scratch with a purpose-built TCP bridge and app — but the CPC200 reverse-engineering and the XDA community's work is what proved the concept was even possible.

### Core Dependency

- **[opencardev/aasdk](https://github.com/opencardev/aasdk)** — The Android Auto protocol library by [Michal Szwaj (f1x)](https://github.com/nickel110). OpenAutoLink maintains a [fork](https://github.com/mossyhub/aasdk) with NavigationStatus extensions. aasdk is the foundation that makes the entire bridge possible — it handles version exchange, TLS, service discovery, and all AA channel communication.

### Projects I Learned From

- **[opencardev/openauto](https://github.com/opencardev/openauto)** — The original Android Auto headunit emulator, also by Michal Szwaj. OpenAuto demonstrated that a full AA headunit could be built on commodity hardware. I studied its session lifecycle, service handler architecture, and codec negotiation patterns extensively. Included as a reference submodule.

- **[Crankshaft](https://github.com/nickel110/crankshaft-ng)** — The Raspberry Pi Android Auto head unit distro built on OpenAuto. Crankshaft proved the concept of a turnkey embedded AA experience and influenced my approach to SBC deployment and systemd service design.

- **[nickel110/WirelessAndroidAutoDongle](https://github.com/nickel110/WirelessAndroidAutoDongle)** — Wireless AA dongle firmware. I referenced its Bluetooth pairing flow and WiFi credential exchange over RFCOMM, which informed the `aa_bt_all.py` implementation.

None of the app or bridge code is derived from these projects, but the knowledge and patterns they established in the open-source AA ecosystem were invaluable.

### On the Topic of Vibe-Coding

Yes, this project is almost entirely AI-assisted. Every line of Kotlin, C++, Python, and systemd config was written with GitHub Copilot as co-pilot (pun intended). Literally billions of tokens (so far) have given their lives for this codebase and many more brave ones will as I keep going on this project. If that makes you mass-close your browser tabs in disgust — fair enough, this is all free to you anyways.

But here's the thing: no amount of token-burning replaces sitting in a driveway at 12 AM with a laptop balanced on the center console, watching `logcat` scroll while tapping the screen and muttering "why is the audio crackling." The AI writes code fast. The car tells you if it actually works. Many, many hours have been spent doing exactly that — real hardware, real protocols, real debugging.

The AI got me from zero to working prototype at a pace that would've been impossible solo. The car kept me honest.

## License

TBD
