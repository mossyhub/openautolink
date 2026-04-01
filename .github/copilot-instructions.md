# OpenAutoLink — Project Guidelines

## What This Is

A wireless Android Auto bridge for AAOS head units. Purpose-built from scratch.

```
Phone ──WiFi TCP:5277──▶ SBC (openautolink-headless, aasdk v1.6)
                              ↓ OAL protocol (simple framing)
                         SBC TCP:5288 (control) ──Ethernet──▶ Car App (AAOS)
                         SBC TCP:5290 (video)   ──Ethernet──▶ Car App (AAOS)
                         SBC TCP:5289 (audio)   ──Ethernet──▶ Car App (AAOS)
```

Two components:
1. **App** (`app/`) — Kotlin/Compose AAOS app. Connects to bridge via TCP, renders video/audio, forwards touch/GNSS/vehicle data
2. **Bridge** (`bridge/`) — C++ headless binary + Python BT/WiFi services on an SBC

## Performance Priorities

**Video, audio, and touch performance are the #1 priority.** Every design decision must optimize for:

1. **Fast initial render**: Connection → first video frame → audio playing must be as fast as possible. No unnecessary handshake delays, no lazy initialization on the hot path
2. **Stable streaming**: Zero dropped audio, minimal dropped video frames, immediate touch response. The car experience must feel native, not remote
3. **Seamless reconnection**: This is critical due to how cars work:
   - When the car "turns off", the SBC/bridge loses power immediately (hard power cut)
   - The AAOS head unit enters a sleep/suspend state (like an Android phone) — the app remains in memory at its last state
   - When the car turns back on, the app wakes up having abruptly lost its TCP connections
   - The bridge takes time to boot (Linux boot + service start) — the app must wait patiently with a clean UI state (no error spam, no crash)
   - Once the bridge is reachable, reconnect automatically with no user interaction
   - First frame after reconnect must be clean — no black frames, no decoder artifacts, no partial/grainy frames. Wait for IDR before rendering
   - Audio must resume without pops, clicks, or stale buffer playback
   - The user experience should be: car on → brief "Connecting..." → projection appears. Indistinguishable from a fresh start

> **Design test**: If a feature adds latency to connection, first-frame, or reconnection — it needs exceptional justification.

## Cross-Component Rule: Always Reference the Other Side

When modifying **app** code that talks to the bridge, **read the bridge source code first** (`bridge/openautolink/headless/`). When modifying **bridge** code, read the app code first. Don't trust protocol docs alone — they may describe the target design while the code implements the current (possibly legacy) format. It is acceptable to modify either side to improve the protocol, but check `docs/work-plan.md` for planned migration paths before making ad-hoc changes.

## Architecture

### App (`app/`) — Component Islands

| Island | Responsibility | Test Anchor |
|--------|---------------|-------------|
| `transport/` | TCP connection manager (3 channels: control, video, audio), reconnect, discovery | Unit: message serialization, reconnect logic. Integration: mock TCP server |
| `video/` | MediaCodec lifecycle, Surface rendering, codec detection | Unit: frame header parsing. Integration: decode test streams |
| `audio/` | Multi-purpose AudioTrack (5 slots), mic capture, ring buffer | Unit: purpose routing, ring buffer. Integration: PCM playback |
| `input/` | Touch forwarding, GNSS, vehicle data (VHAL) | Unit: coordinate scaling, NMEA formatting. Integration: VHAL mock |
| `ui/` | Compose screens — projection surface, settings, diagnostics | Unit: ViewModel state. Integration: Compose test rules |
| `navigation/` | Nav state from bridge, maneuver icons, cluster service | Unit: maneuver mapping. Integration: cluster IPC |
| `session/` | Session orchestrator — connects islands, manages lifecycle | Integration: full session with mock bridge |

- **Min SDK 32**, target SDK 36, Kotlin, Jetpack Compose, DataStore preferences
- **MVVM** with `StateFlow` — ViewModels own UI state, repositories own data
- Uses OAL protocol exclusively (see [docs/protocol.md](docs/protocol.md))
- **No USB adapter support** — TCP-only, bridge-only

### Bridge (`bridge/`)

The bridge binary speaks OAL protocol over TCP to the car app.

| Directory | Purpose |
|-----------|---------|
| `bridge/openautolink/headless/` | C++20 binary — aasdk v1.6 AA session + OAL protocol relay |
| `bridge/openautolink/scripts/` | `aa_bt_all.py` — BLE, BT pairing, HSP, RFCOMM WiFi credential exchange |
| `bridge/sbc/` | Systemd services, env config, install script, build guide |

The bridge speaks OAL protocol directly on all three TCP channels.

## Build & Test

### App
```powershell
.\gradlew :app:assembleDebug             # Debug APK
.\gradlew :app:bundleRelease             # AAB for Play Store
.\gradlew :app:testDebugUnitTest          # Unit tests
.\gradlew :app:connectedDebugAndroidTest  # Instrumentation tests
```

### Bridge (on SBC)
```bash
cd /opt/openautolink-src/build
cmake --build . --target openautolink-headless -j$(nproc)
sudo strip -o /opt/openautolink/bin/openautolink-headless build/openautolink-headless
sudo systemctl restart openautolink.service
```
See [bridge/sbc/BUILD.md](bridge/sbc/BUILD.md) for full details.

### Windows → SBC deploy
```powershell
scripts/deploy-to-sbc.ps1
```

## Conventions

### OAL Wire Protocol
- **3 TCP connections**: control (5288, JSON lines), video (5290, binary frames), audio (5289, binary frames)
- **Control**: newline-delimited JSON — no magic bytes, no checksums
- **Video**: 16-byte header (payload_length, width, height, pts_ms, flags) + raw codec payload
- **Audio**: 8-byte header (direction, purpose, sample_rate, channels, length) + raw PCM
- Full spec: [docs/protocol.md](docs/protocol.md)

### Video Rules
> Projection streams are live UI state, not video playback.
> Late frames must be dropped. Corruption must trigger reset.
> Video may drop. Audio may buffer. Neither may block the other.
> On reconnect: flush decoder, discard stale buffers, wait for IDR before rendering. No artifact frames.
> Keep codec and AudioTracks pre-warmed where possible — minimize time from TCP connect to first rendered frame.

### Code Patterns
- **Island independence**: Each component island has its own package, public interface, and test suite. Islands communicate through the session orchestrator, not directly
- **ViewModel per screen**: Compose screens observe `StateFlow` from ViewModels. No business logic in composables
- **Repository pattern**: Data access (DataStore, TCP, VHAL) goes through repository interfaces. Implementations are injectable
- **Coroutines for async**: `viewModelScope` for UI, `Dispatchers.IO` for network/disk, dedicated threads only for real-time audio/video
- **Test-first islands**: Every island has a public interface defined before implementation. Unit tests mock dependencies at island boundaries

### Naming
- Project: **OpenAutoLink**
- App package: `com.openautolink.app`
- Bridge binary: `openautolink-headless`
- Systemd services: `openautolink-*.service`
- Env file: `/etc/openautolink.env`

## Key Documentation

| Doc | Purpose |
|-----|---------|
| [docs/architecture.md](docs/architecture.md) | Component island architecture, milestone plan |
| [docs/protocol.md](docs/protocol.md) | OAL wire protocol specification |
| [docs/embedded-knowledge.md](docs/embedded-knowledge.md) | Hardware lessons (MUST READ before touching video/audio/VHAL) |
| [docs/networking.md](docs/networking.md) | Three-network architecture (phone, car, SSH) |
| [bridge/sbc/BUILD.md](bridge/sbc/BUILD.md) | SBC build and deployment guide |
| [docs/testing.md](docs/testing.md) | Local testing with AAOS emulator + SBC |

## Pitfalls

- **CRLF**: SBC scripts and env files must be LF. Windows scp creates CRLF — always convert
- **CMake timestamp**: After scp from Windows, `touch` the file on SBC so CMake detects the change
- **aasdk v1.6**: Phone requires v1.6 ServiceConfiguration format. v1.1 format = silent ignore
- **BlueZ SAP plugin**: Steals RFCOMM channel 8. Disable with `--noplugin=sap`
- **CM5 power**: Use `-j1` for builds. Parallel compile can crash the board
- **MediaCodec lifecycle**: Must release codec on pause, recreate on resume. Surface changes require full codec reset
- **AudioTrack purpose routing**: See [docs/embedded-knowledge.md](docs/embedded-knowledge.md) — the 5-slot decode_type/audioType matching is non-obvious
