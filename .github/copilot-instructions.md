# OpenAutoLink — Project Guidelines

## What This Is

Wireless Android Auto for AAOS head units. No external hardware required.

```
Android Phone ──WiFi (car or phone hotspot)──▶ Car AAOS Head Unit
                                          ├── Companion App (TCP :5277) → stream pipe
                                          ├── aasdk v1.6 (C++ JNI) → AA wire protocol
                                          ├── MediaCodec → video rendering
                                          ├── AudioTrack → 5-purpose audio
                                          └── VHAL/GNSS/IMU → sensor forwarding
```

Two components:
1. **App** (`app/`) — Kotlin/Compose AAOS app. Embeds aasdk C++ via JNI for AA protocol. Renders video/audio, forwards touch/GNSS/vehicle data
2. **Companion** (`companion/`) — Phone-side app. Opens a TCP listener on port 5277 over the shared WiFi (Car Hotspot or Phone Hotspot) and pipes the AA session to the car app

> **Historical**: Bridge mode (SBC hardware) is preserved on the `bridge-mode` branch. Bridge code in `bridge/` is reference only on this branch.

## Performance Priorities

**Video, audio, and touch performance are the #1 priority.** Every design decision must optimize for:

1. **Fast initial render**: Connection → first video frame → audio playing must be as fast as possible. No unnecessary handshake delays, no lazy initialization on the hot path
2. **Stable streaming**: Zero dropped audio, minimal dropped video frames, immediate touch response. The car experience must feel native, not remote
3. **Seamless reconnection**: This is critical due to how cars work:
   - When the car "turns off", the AAOS head unit enters sleep/suspend — the app remains in memory at its last state
   - When the car turns back on, the app wakes up having lost its WiFi/TCP connections
   - The app must retry patiently with a clean UI state (no error spam, no crash)
   - Once the phone is reachable (car/phone hotspot reconnects), reconnect automatically with no user interaction
   - First frame after reconnect must be clean — no black frames, no decoder artifacts, no partial/grainy frames. Wait for IDR before rendering
   - Audio must resume without pops, clicks, or stale buffer playback
   - The user experience should be: car on → brief "Connecting..." → projection appears. Indistinguishable from a fresh start

> **Design test**: If a feature adds latency to connection, first-frame, or reconnection — it needs exceptional justification.

## Cross-Component Rule: Always Reference the Other Side

When modifying **app Kotlin** code that interacts with the JNI layer, **read the C++ JNI code first** (`app/src/main/cpp/`). When modifying **JNI C++** code, read the Kotlin side first. When touching aasdk channel handling, **read the corresponding aasdk header** (`external/opencardev-aasdk/include/aasdk/Channel/`). Don't trust assumptions — verify what the code actually sends/receives.

## Architecture

### App (`app/`) — Component Islands

| Island | Responsibility | Test Anchor |
|--------|---------------|-------------|
| `transport/aasdk/` | aasdk JNI session (C++ pipeline), TCP transport, AA protocol | Integration: companion app + phone |
| `transport/hotspot/` | TCP connector + phone discovery (mDNS / UDP sweep) over car or phone hotspot | Unit: discovery mocks |
| `video/` | MediaCodec lifecycle, Surface rendering, codec detection | Unit: frame header parsing. Integration: decode test streams |
| `audio/` | Multi-purpose AudioTrack (5 slots), mic capture, ring buffer | Unit: purpose routing, ring buffer. Integration: PCM playback |
| `input/` | Touch forwarding, GNSS, vehicle data (VHAL), IMU sensors (accel/gyro/compass) | Unit: coordinate scaling, NMEA formatting. Integration: VHAL mock |
| `ui/` | Compose screens — projection surface, settings, diagnostics | Unit: ViewModel state. Integration: Compose test rules |
| `navigation/` | Nav state from aasdk, maneuver icons, cluster service | Unit: maneuver mapping. Integration: cluster IPC |
| `session/` | Session orchestrator — connects islands, manages lifecycle | Integration: full session with mock bridge |

- **Min SDK 32**, target SDK 36, Kotlin, Jetpack Compose, DataStore preferences
- **MVVM** with `StateFlow` — ViewModels own UI state, repositories own data
- Uses aasdk v1.6 AA protocol via JNI (C++ native library)
- **No USB adapter support** — WiFi only (Car Hotspot or Phone Hotspot)

### C++ JNI Layer (`app/src/main/cpp/`)

| File | Purpose |
|------|---------|
| `aasdk_jni.cpp` | JNI entry point — native method registration |
| `jni_session.{h,cpp}` | aasdk pipeline: SSLWrapper → Cryptor → Messenger → channels. Control + video handler |
| `jni_channel_handlers.{h,cpp}` | Separate handler classes for audio, sensor, input, nav, mic, media, phone, BT |
| `jni_transport.{h,cpp}` | ITransport backed by TCP socket streams via JNI readBytes/writeBytes |
| `stubs/` | libusb stub (USB not used on Android) |

### External Dependencies (`external/`)

| Submodule | Fork | Branch | Purpose |
|-----------|------|--------|---------|
| `external/opencardev-aasdk/` | [mossyhub/aasdk](https://github.com/mossyhub/aasdk) | `openautolink` | aasdk v1.6 with our NavigationStatus extensions |
| `external/opencardev-openauto/` | upstream `opencardev/openauto` | `main` | Reference only — not modified |

**aasdk is a forked submodule.** Changes to files in `external/opencardev-aasdk/` must be committed inside the submodule first (`cd external/opencardev-aasdk && git add/commit/push`), then the parent repo's submodule pointer updated (`cd <root> && git add external/opencardev-aasdk && git commit`). Do not leave aasdk changes as dirty working-tree edits.

### Bridge (`bridge/`) — HISTORICAL (bridge-mode branch only)

The bridge binary is not used on this branch. It is preserved on the `bridge-mode` branch for users with SBC hardware. The bridge speaks OAL protocol over TCP to the car app using aasdk.

| Directory | Purpose |
|-----------|--------|
| `bridge/openautolink/headless/` | C++20 binary — aasdk v1.6 AA session + OAL protocol relay |
| `bridge/openautolink/scripts/` | `aa_bt_all.py` — BLE, BT pairing (AA profiles), HSP, RFCOMM WiFi credential exchange |
| `bridge/sbc/` | Systemd services, env config, install script, build guide |

## Build & Test

### App
```powershell
.\gradlew :app:assembleDebug             # Debug APK
.\gradlew :app:bundleRelease             # AAB for Play Store
.\gradlew :app:testDebugUnitTest          # Unit tests
.\gradlew :app:connectedDebugAndroidTest  # Instrumentation tests
```

> **Linux contributors**: Use `scripts/linux/build-android.sh` and
> `scripts/linux/bundle-release.sh`. The `.ps1` scripts in `scripts/` are
> the **Windows-authoritative** path; do not duplicate logic — keep both
> in sync when changing release workflows. See [scripts/linux/README.md](scripts/linux/README.md).

### Native Dependencies (one-time setup in WSL)
```bash
scripts/build-openssl-android.sh   # OpenSSL static libs for ARM64
scripts/setup-ndk-deps.sh          # Boost headers
scripts/build-aasdk-android.sh     # aasdk + protobuf + abseil static libs
```
These produce prebuilt `.a` files in `app/src/main/cpp/third_party/`. Gradle's NDK build links them — no source compilation in Gradle.

### Bridge (WSL cross-compile + deploy) — bridge-mode branch only
```powershell
scripts\deploy-bridge.ps1          # Build in WSL + deploy to SBC
scripts\deploy-bridge.ps1 -Clean    # Clean rebuild + deploy
```
See [bridge/sbc/BUILD.md](bridge/sbc/BUILD.md) for SBC setup. CI builds via `.github/workflows/release-bridge.yml`.

## Conventions

### OAL Wire Protocol — bridge-mode only
- **Not used in direct/JNI mode.** The OAL protocol exists only for bridge-mode communication.
- In JNI mode, the app communicates with the phone via aasdk C++ channels directly — no TCP, no OAL framing.
- Full spec: [docs/protocol.md](docs/protocol.md)

### Video Rules
> Projection streams are live UI state, not video playback.
> Late frames must be dropped. Corruption must trigger reset.
> Video may drop. Audio may buffer. Neither may block the other.
> On reconnect: flush decoder, discard stale buffers, wait for IDR before rendering. No artifact frames.
> Keep codec and AudioTracks pre-warmed where possible — minimize time from TCP connect to first rendered frame.
> **Aspect ratio**: Use `width_margin` (wide displays) or `height_margin` (tall displays) in the SDR to match the display AR. Do NOT use `pixel_aspect_ratio_e4` — the phone ignores it. Do NOT rely on `MediaCodec.setVideoScalingMode()` — Qualcomm decoders ignore it. See `.github/instructions/pixel-aspect.instructions.md` for full details.

### Code Patterns
- **Island independence**: Each component island has its own package, public interface, and test suite. Islands communicate through the session orchestrator, not directly
- **ViewModel per screen**: Compose screens observe `StateFlow` from ViewModels. No business logic in composables
- **Repository pattern**: Data access (DataStore, TCP, VHAL) goes through repository interfaces. Implementations are injectable
- **Coroutines for async**: `viewModelScope` for UI, `Dispatchers.IO` for network/disk, dedicated threads only for real-time audio/video
- **Test-first islands**: Every island has a public interface defined before implementation. Unit tests mock dependencies at island boundaries

### Versioning & Logging
- **Single source of truth**: `secrets/version.properties` has `versionName=X.Y.Z`. Gradle, CMake, and deploy scripts all read from it
- **Version in every log line**: All components MUST include the version in log output so you never have to guess which code is running
  - **App Kotlin**: Use `OalLog.i(TAG, "message")` instead of `Log.i(TAG, ...)` — prepends `[app X.Y.Z]`. Import from `com.openautolink.app.diagnostics.OalLog`
  - **Bridge C++** (bridge-mode only): Use `BLOG << "message"` (stream) or `oal_log("format", ...)` (printf) — both prepend `[bridge X.Y.Z]` via `oal_log.hpp`. Never use raw `std::cerr <<` or `fprintf(stderr,`
  - **BT script** (bridge-mode only): Use `oal_print("message")` instead of `print()` — prepends `[bt X.Y.Z]` using `OAL_VERSION` env var
  - **Shell scripts**: Read `OAL_VERSION` from env and include in log lines
- **Version bump before commit**: When making changes to app or bridge code, bump `versionName` in `secrets/version.properties` before building/committing. The deploy script stamps `OAL_VERSION` into `/etc/openautolink.env` on the SBC

### Naming
- Project: **OpenAutoLink**
- App package: `com.openautolink.app`
- Bridge binary (bridge-mode only): `openautolink-headless`
- Systemd services (bridge-mode only): `openautolink-*.service`
- Env file (bridge-mode only): `/etc/openautolink.env`

## Key Documentation

| Doc | Purpose |
|-----|---------|
| [docs/architecture.md](docs/architecture.md) | Component island architecture, milestone plan |
| [docs/protocol.md](docs/protocol.md) | OAL wire protocol specification (bridge-mode only) |
| [docs/embedded-knowledge.md](docs/embedded-knowledge.md) | Hardware lessons (MUST READ before touching video/audio/VHAL) |
| [docs/networking.md](docs/networking.md) | Three-network architecture (bridge-mode only) |
| [bridge/sbc/BUILD.md](bridge/sbc/BUILD.md) | SBC build and deployment guide (bridge-mode only) |
| [docs/bridge-update.md](docs/bridge-update.md) | Bridge OTA update system — as-built design, flow, security |
| [docs/testing.md](docs/testing.md) | Local testing with AAOS emulator + SBC + remote diagnostics |
## Pitfalls

- **CRLF**: Shell scripts must be LF (enforced via `.gitattributes eol=lf`). Windows `scp` from PowerShell injects CRLF. **`sed`, `tr`, and `perl` over SSH from PowerShell cannot fix this** — PowerShell re-injects `\r` into escape sequences. Use the Python binary-I/O method in [bridge/sbc/BUILD.md](bridge/sbc/BUILD.md#manually-copying-files-from-windows). The `deploy-bridge.ps1` script handles this automatically.
- **aasdk v1.6**: Phone requires v1.6 ServiceConfiguration format. v1.1 format = silent ignore
- **BlueZ SAP plugin** (bridge-mode only): Steals RFCOMM channel 8. Disable with `--noplugin=sap`
- **MediaCodec lifecycle**: Must release codec on pause, recreate on resume. Surface changes require full codec reset
- **AudioTrack purpose routing**: See [docs/embedded-knowledge.md](docs/embedded-knowledge.md) — the 5-slot decode_type/audioType matching is non-obvious
- **No ADB on GM cars**: GM locks ADB on production head units. Use the app's built-in Remote Log Server (TCP port 6555) for live log streaming from a laptop on the same network. See [docs/testing.md](docs/testing.md#10-remote-diagnostics-no-adb-required)
