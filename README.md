<p align="center">
  <img src="assets/play_store_512.png" alt="OpenAutoLink" width="128">
</p>

# OpenAutoLink

[![CI](https://github.com/mossyhub/openautolink/actions/workflows/ci.yml/badge.svg)](https://github.com/mossyhub/openautolink/actions/workflows/ci.yml)

**An open-source wireless Android Auto and CarPlay bridge for AAOS head units.** An SBC handles the phone's Android Auto or CarPlay session over WiFi, then streams video, audio, and touch to an app on your car's display over Ethernet — no janky, closed-source and hacky USB adapter hardware required.

- Wireless Android Auto and CarPlay — phone connects via Bluetooth + WiFi, no cables
- Up to 1080p60 video with H.264, H.265, or VP9 codec support
- Full audio: media, navigation prompts, phone calls, voice assistant
- Touch, steering wheel controls, and microphone input forwarded to the phone
- Vehicle data (speed, gear, fuel/EV range, GPS, etc.) sent to Android Auto / CarPlay
- Navigation turn-by-turn displayed on the instrument cluster *(experimental — GM may kill third-party cluster services)*
- Auto mode: bridge accepts whichever phone connects first — Android or iPhone
- One-command SBC install, auto-reconnect on car startup, app updates via Play Store
- Fully open-source — app, bridge, protocol, and deployment scripts

> **Fair warning:** This is a free, hobby project that is very much under active development. It might work great, it might not work at all. Stated features may or may not actually work. My goal is to eventually make it stable and production-quality, but it's not there yet — and honestly, it may never be. I'm building this because it's fun and because I want Android Auto and CarPlay back in my car. If that sounds like your kind of adventure, give it a try.

Starting with the 2024 model year, GM dropped Apple CarPlay and Android Auto from their electric vehicles (Blazer EV, Equinox EV, Silverado EV, Lyriq, etc.) in favor of Google built-in infotainment. GM has indicated this will expand to all GM vehicles in the 2025-2026+ timeframe. **OpenAutoLink brings Android Auto and CarPlay back** to these vehicles by bridging a phone's AA or CarPlay session to the car's AAOS head unit over the network — no USB adapter hardware needed.

An SBC (Raspberry Pi 4/5, Khadas VIM4, etc.) bridges your phone's Android Auto or CarPlay session to your car's display over WiFi + Ethernet. The car runs the OpenAutoLink app, the SBC runs the bridge.

```
Android Phone ──WiFi TCP:5277──▶                    ┌── Control :5288 (JSON lines)
                  BT pairing    ▶ SBC Bridge ──Eth──▶├── Video   :5290 (binary frames)
iPhone ──WiFi RTSP:5000────────▶   (AA or CarPlay)  └── Audio   :5289 (binary frames)
                  BT pairing                              ▼
                                                  Car Head Unit App (AAOS)
                                                    Renders video/audio
                                                    Forwards touch/GNSS
```

The bridge supports three session modes:
- **`android-auto`** (default) — Android Auto only
- **`carplay`** — CarPlay only
- **`auto`** — both listeners active, first phone to connect wins

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
- **Raspberry Pi 5** — primary development board. Compact, reliable, good WiFi
- **Khadas VIM4** — also works, overkill for this use case

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
4. **Phone** pairs with the SBC over Bluetooth, joins the SBC's 5 GHz WiFi AP, and streams Android Auto or CarPlay wirelessly

> **Blazer EV note:** Use the USB-C port inside the **center console armrest compartment** (the one behind the lid), not the two USB ports on the front of the center console. The armrest port is the one that enumerates USB network devices to the head unit. Other GM EVs may have a similar arrangement — check which USB port your AAOS head unit can see network devices on.

### Where to Put It

The SBC, adapter, and cable are small enough to live entirely inside the center console compartment. Tuck the SBC in, run power from a nearby outlet, and close the lid. Nothing is visible when the console is shut. The phone stays in your pocket — Android or iPhone, it connects wirelessly. If you SBC needs to breath more, just use a thin, longer ethernet cable and easily move it out to the front USB ports for power.

## Components

| Component | Language | Location |
|-----------|----------|----------|
| **Car App** | Kotlin/Compose (AAOS) | `app/` |
| **Bridge** | C++20 (headless binary) | `bridge/openautolink/headless/` |
| **BT/WiFi Services** | Python | `bridge/openautolink/scripts/` |
| **SBC Deployment** | Bash/systemd | `bridge/sbc/` |
| **aasdk** | C++ (submodule) | `external/opencardev-aasdk/` |

## Quick Start

> **Play Store goal**: The long-term goal is to get the app certified and published on the Google Play Store so users can simply install it. Whether this will be possible (AAOS certification requirements, Google approval, etc.) is unknown at this point. Until then, you must self-publish as described below.

### Bridge (SBC)

No building required — the install script downloads the latest pre-built binary from GitHub Releases. Follow the [Bridge Setup Guide](bridge/sbc/BUILD.md) which walks you through flashing an OS, getting SSH access, and running:

```bash
curl -fsSL https://raw.githubusercontent.com/mossyhub/openautolink/main/bridge/sbc/install.sh | sudo bash
```

### App (AAOS Head Unit)

Because this is an AAOS app (not a standard phone app), getting it onto your car requires publishing through the Google Play Console with an AAOS-specific release track. GM's AAOS head units have no known way to sideload APKs — ADB is locked down and there's no accessible install mechanism outside the Play Store. So the only path is publishing through a testing track on your own Play Console account. This is more involved than a typical Android app:

1. **Create a [Google Play Console](https://play.google.com/console/) developer account** ($25 one-time fee)

2. **Create a new app** in the Play Console and set up an **AAOS release track** (car-specific distribution)

3. **Change the package name** — Google Play requires a unique application ID. In [app/build.gradle.kts](app/build.gradle.kts), change `com.openautolink.app` to your own unique package name (e.g. `com.yourname.openautolink`)

4. **Generate a signing key**:
   ```powershell
   .\scripts\create-upload-keystore.ps1
   ```
   This creates a keystore at `secrets/upload-key.jks`. Keep this file safe — you need it for every update.

5. **Build and sign the release AAB**:
   ```powershell
   .\scripts\bundle-release.ps1
   ```
   The signed `.aab` will be in `app/build/outputs/bundle/release/`.

6. **Add testers** — In the Play Console, go to your AAOS release track's **Testers** tab. Create an email list and add the Google accounts that are signed in on your car's head unit (even your own account)

7. **Upload the AAB** to your Play Console AAOS release track, fill in the required store listing details, and publish the release

8. **Accept the test invite** — After publishing, each tester (including yourself) must open the opt-in link from the Play Console testers page and accept the invitation. Without this step, the app will not appear on the car

9. **Install** — Once the invite is accepted, the app will auto-install on your car's head unit if it has a WiFi or cellular data connection. You can also find it in the Play Store on the head unit

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
| [Work Plan](docs/work-plan.md) | Milestones and task tracking |
| [Bridge Build Guide](bridge/sbc/BUILD.md) | SBC build and deployment || [CarPlay Implementation](docs/future/carplay-bridge-implementation.md) | CarPlay bridge architecture and phased plan |
## Status

Active development. See the [work plan](docs/work-plan.md) for current milestones.

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

### CarPlay Protocol References

- **[postlund/pyatv](https://github.com/postlund/pyatv)** — Apple TV protocol implementation with HomeKit pair-setup/verify. Referenced for SRP-6a and X25519 key exchange state machines.
- **[openairplay/airplay2-receiver](https://github.com/openairplay/airplay2-receiver)** — AirPlay 2 receiver. Referenced for Bonjour TXT records, feature bitmaps, and RTSP control flow.
- **[homebridge/HAP-nodejs](https://github.com/homebridge/HAP-nodejs)** — HomeKit Accessory Protocol. Referenced for HomeKit pairing v2 cryptography (the same protocol CarPlay uses for wireless authentication).

### On the Topic of Vibe-Coding

Yes, this project is almost entirely AI-assisted. Every line of Kotlin, C++, Python, and systemd config was written with GitHub Copilot as co-pilot (pun intended). Literally billions of tokens (so far) have given their lives for this codebase and many more brave ones will as I keep going on this project. If that makes you mass-close your browser tabs in disgust — fair enough, this is all free to you anyways.

But here's the thing: no amount of token-burning replaces sitting in a driveway at 12 AM with a laptop balanced on the center console, watching `logcat` scroll while tapping the screen and muttering "why is the audio crackling." The AI writes code fast. The car tells you if it actually works. Many, many hours have been spent doing exactly that — real hardware, real protocols, real debugging.

The AI got me from zero to working prototype at a pace that would've been impossible solo. The car kept me honest.

## License

TBD
