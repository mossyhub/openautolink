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

### Build the App
```powershell
.\gradlew :app:assembleDebug
```

### Build a Signed AAB (for Play Console)
```powershell
.\scripts\bundle-release-interactive.ps1
```

### Build the Bridge (on SBC)
```bash
cd /opt/openautolink-src/build
cmake --build . --target openautolink-headless -j$(nproc)
```

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
