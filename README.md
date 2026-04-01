<p align="center">
  <img src="assets/play_store_512.png" alt="OpenAutoLink" width="128">
</p>

# OpenAutoLink

Wireless Android Auto for GM AAOS vehicles that lack native Android Auto support.

Starting with the 2024 model year, GM dropped Apple CarPlay and Android Auto from their electric vehicles (Blazer EV, Equinox EV, Silverado EV, Lyriq, etc.) in favor of Google built-in infotainment. GM has indicated this will expand to all GM vehicles in the 2025-2026+ timeframe. **OpenAutoLink brings Android Auto back** to these vehicles by bridging a phone's AA session to the car's AAOS head unit over the network — no USB adapter hardware needed.

An SBC (Raspberry Pi CM5, Khadas VIM4, etc.) bridges your phone's Android Auto session to your car's display over WiFi + Ethernet. The car runs the OpenAutoLink app, the SBC runs the bridge.

```
Phone ──WiFi TCP:5277──▶ SBC Bridge ──Ethernet──▶ Car Head Unit App (AAOS)
         BT pairing          │                      Renders video/audio
         WiFi creds           │                      Forwards touch/GNSS
                              ├── Control :5288 (JSON lines)
                              ├── Video   :5290 (binary frames)
                              └── Audio   :5289 (binary frames)
```

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

Because this is an AAOS app (not a standard phone app), getting it onto your car requires publishing through the Google Play Console with an AAOS-specific release track. This is more involved than a typical Android app:

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
   .\scripts\bundle-release-interactive.ps1
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
| [Bridge Build Guide](bridge/sbc/BUILD.md) | SBC build and deployment |

## Status

Active development. See the [work plan](docs/work-plan.md) for current milestones.

## Compatibility

**Not known to be universally compatible with all AAOS vehicles.** Currently tested only on a **2024 Chevrolet Blazer EV**, which enumerates a USB NIC, assigns it an IP, and allows network traffic to reach apps. Other GM vehicles on the same AAOS head unit platform likely work, but this has not been verified. Non-GM AAOS vehicles may have different USB networking behavior or restrictions that prevent this approach from working.

## License

TBD
