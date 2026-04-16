# Direct AA Mode — Implementation Plan

**Branch:** `feature/direct-aa`
**Scope:** This branch converts the app to direct-mode-only. No toggle, no bridge mode fallback.

**Status:** All 8 phases complete. Live-tested on Orange Pi Zero2 + OnePlus 13 phone + AAOS emulator.

| Phase | Status | Notes |
|-------|--------|-------|
| 1. Remove Bridge Code | ✅ Done | All OAL code removed, clean compile, tests pass |
| 2. NDK/JNI Build | ✅ Done | arm64-v8a + x86_64 full aasdk. `startSessionWithFd` added |
| 3. Direct AA Transport | ✅ Done | `DirectAaTransport.kt` with relay + control + reconnect |
| 4. Wire Up SessionManager | ✅ Done | All forwarders routed through aasdk JNI |
| 5. Settings UI Cleanup | ✅ Done | Phone mgmt wired via relay control channel |
| 6. Bridge Relay Binary | ✅ Done | `openautolink-relay` -- 67KB stripped, zero deps |
| 7. Bridge Deployment | ✅ Done | Scripts, services, env, CI all updated for relay |
| 8. Remove Old Bridge Code | ✅ Done | ~14,800 lines deleted, docs rewritten |
| **9. AA Session Parity** | **⬜ Next** | **Port all features from old bridge `live_session.cpp` to in-app `aa_session.cpp`** |

## Goal

Move aasdk into the Android app via NDK/JNI. The bridge becomes a thin BT/WiFi broker + TCP
relay. This eliminates:
- The OAL protocol (encode/decode/3-TCP-channel overhead)
- Bridge auto-update system (bridge binary is now trivial — rarely changes)
- Config-update-to-bridge flow (settings are used directly by in-app aasdk)
- Bridge restart logic
- All "save to env file" patterns

AA protocol logic lives in the app and is updatable via Play Store.

**Remote diagnostics are kept** — the bridge relay forwards `app_log` and `app_telemetry`
messages so a laptop on SSH can observe app logs (since we can't ADB into AAOS).

## The Networking Problem & Solution

### Problem

In the current bridge mode, the **app initiates** all TCP connections outbound to the bridge.
AAOS on the GM BlazerEV blocks all inbound connections. Moving aasdk into the app would
naively require the phone to connect *inbound* to the app (TCP:5277), which AAOS blocks.

### Solution: Outbound TCP Relay

The app continues making **outbound** connections. The bridge pairs them with the phone's
inbound connection using a raw byte splice:

```
1. App ──outbound TCP──▶ Bridge:5291 ("relay")     ✅ Outbound from AAOS, allowed
   App holds socket open, waiting for data

2. Phone ──BT pairing──▶ Bridge ──WiFi──▶ Phone joins AP

3. Phone ──TCP:5277──▶ Bridge accepts phone

4. Bridge splices the two sockets:
   Phone:5277 ←──raw bytes──→ App:5291

5. aasdk (JNI, inside app) sees a connected TCP stream
   → TLS handshake → protobuf → video/audio
   → JNI callbacks → MediaCodec/AudioTrack directly
```

The bridge keeps a lightweight control channel on `:5288` for signaling + diagnostics:
- `{"type":"hello",...}` — bridge capabilities, relay port
- `{"type":"relay_ready"}` — phone connected, relay socket paired, app can start aasdk
- `{"type":"phone_bt_connected","phone_name":"..."}` — BT paired, WiFi pending
- `{"type":"relay_disconnected"}` — phone TCP dropped
- `{"type":"app_log",...}` / `{"type":"app_telemetry",...}` — forwarded for SSH viewing

From aasdk's perspective, once it has a connected socket fd, the AA protocol works
identically — TLS doesn't care who initiated the TCP connection.

### Why This Works

- AA uses a **single** TCP connection for all channels (control, video, audio are multiplexed
  by aasdk over one TLS stream). So one relay socket is enough — simpler than the current
  3-connection OAL model.
- The relay adds one `memcpy` + one `write()` per direction per packet. At ~30Mbps video,
  this is negligible vs the OAL encode/decode overhead being eliminated.
- Reconnection: car sleeps → wakes → app reconnects outbound to bridge:5291 and waits.
  Same pattern as today.

## Existing Code to Port ✅ DONE

Copied from `D:\personal\openautolink-direct\app\`. AA-only, no CarPlay.

### Files to copy verbatim (AA-only, no CarPlay)

| Source (openautolink-direct) | Dest (this repo) | Notes |
|------------------------------|-------------------|-------|
| `app/src/main/cpp/jni_bridge.cpp` | Same path | JNI entry points — complete, no CarPlay refs |
| `app/src/main/cpp/aa_session.cpp` | Same path | Full aasdk entity — ~1350 lines, complete |
| `app/src/main/cpp/openssl_compat.h` | Same path | OpenSSL NDK compatibility header |
| `app/src/main/cpp/CMakeLists.txt` | Same path | NDK build — complete with stub/full mode toggle |
| `app/src/main/cpp/third_party/` | Same path | Boost headers, OpenSSL prebuilts |
| `transport/AasdkJni.kt` | Same path | Kotlin JNI wrapper — complete |
| `transport/DirectAaTransport.kt` | Same path | Transport impl — needs relay adaptation |

## What Gets Removed ✅ DONE

All items below were completed in Phase 1.

### Files to delete entirely

| File | Reason |
|------|--------|
| `transport/BridgeConnection.kt` | Replaced by direct aasdk transport — no OAL interface needed |
| `transport/ConnectionManager.kt` | 3-TCP-channel OAL bridge connection — replaced by relay + JNI |
| `transport/TcpControlChannel.kt` | OAL control JSON framing — not needed (aasdk handles everything) |
| `transport/TcpVideoChannel.kt` | OAL video binary framing — video comes directly from aasdk JNI |
| `transport/TcpAudioChannel.kt` | OAL audio binary framing — audio comes directly from aasdk JNI |
| `transport/BridgeUpdateManager.kt` | Auto-update bridge binary from GitHub — no longer needed |
| `transport/ConfigUpdateSender.kt` | Send config_update JSON to bridge — settings used directly now |
| `transport/BridgeDiscovery.kt` | UDP/mDNS discovery — bridge IP is configured, not discovered |
| `transport/ControlMessageSerializer.kt` | OAL JSON serialize/deserialize — replaced by JNI callbacks |

### Files to delete (tests for removed code)

| File | Reason |
|------|--------|
| `test/.../ControlMessageSerializerTest.kt` | Tests OAL JSON serialization — removed |
| `test/.../TransportIntegrationTest.kt` | Tests 3-TCP OAL connection — removed |
| `test/.../MockOalBridgeServer.kt` | Mock OAL bridge for tests — removed |

### Code to remove from remaining files

| File | What to remove |
|------|----------------|
| `session/SessionManager.kt` | `BridgeUpdateManager` creation/callbacks, `ConfigUpdateSender` collection loops, `config_update` sending, `bridgeUpdateManager` property, `connectionManager.sendControlMessage()` for config/restart. Replace `ConnectionManager` with `DirectAaTransport` |
| `session/BridgeInfo.kt` | `bridgeVersion`, `bridgeSha256`, `buildSource` fields (bridge binary no longer versioned) |
| `ui/settings/SettingsScreen.kt` | Entire `BridgeTab` composable (~500 lines: auto-update toggle, version display, update history). Also `BRIDGE` entry in `SettingsTab` enum |
| `ui/settings/SettingsViewModel.kt` | `BridgeDiscovery` usage, `bridgeUpdateState`/`bridgeVersion`/`latestVersion` flows, `bindUpdateManager()`, `triggerManualCheck()`, `ConfigUpdateSender.sendConfigUpdate()` calls, `ConfigUpdateSender.sendRestart()` calls |
| `ui/settings/SettingsScreen.kt` | In `ConnectionTab`: bridge host/port discovery UI (simplify to just "Bridge IP" for relay) |
| `data/AppPreferences.kt` | Remove bridge-config-specific prefs that were sent as `config_update` JSON (these become direct aasdk config). Remove `buildInitialConfigUpdate()` function |
| `transport/ControlMessage.kt` | Remove OAL-specific types: `Hello` (OAL hello with `bridgeVersion`), `ConfigUpdate`, `ConfigEcho`, `BridgeUpdateAccept`, `BridgeUpdateChunk`, `RestartBridge`, `ListPairedPhones`, `SwitchPhone`, `ForgetPhone` |

### What stays (still needed) ✅ Verified

All of these survived Phase 1 intact.

| Component | Why |
|-----------|-----|
| Remote diagnostics (`diagnostics/`) | SSH log viewing via bridge relay control channel |
| `ControlMessage.kt` (subset) | `Touch`, `Gnss`, `VehicleData`, `PhoneConnected`, `PhoneDisconnected`, `NavState`, `MediaMetadata`, `AudioStart/Stop`, `MicStart/Stop`, `PhoneBattery`, `VoiceSession`, `PhoneStatus` — these come from aasdk JNI callbacks now |
| `NetworkInterfaceScanner.kt` | Still need to find the USB Ethernet interface to the bridge |
| Video pipeline (`video/`) | Same H.264 NALUs, same MediaCodec — data comes from JNI instead of TCP |
| Audio pipeline (`audio/`) | Same PCM, same AudioTrack — data comes from JNI instead of TCP |
| Input pipeline (`input/`) | Touch, GNSS, VHAL, IMU — forwarded via JNI instead of OAL JSON |
| All UI except BridgeTab | Projection, display settings, video settings, audio settings, diagnostics |
| Cluster, navigation, media | Unchanged — consume same ControlMessage types |

## Implementation Phases

> **Documentation rule:** Each phase includes doc update steps at the end, tagged `[doc]`.
> Update docs while the context is fresh — not in a separate phase at the end.

### Phase 1: Remove Bridge-Specific Code ✅ DONE

Strip all OAL bridge code before adding direct mode, so we have a clean baseline.

| Step | Status | Description |
|------|--------|-------------|
| 1a | ✅ | Delete files: `BridgeConnection.kt`, `ConnectionManager.kt`, `TcpControlChannel.kt`, `TcpVideoChannel.kt`, `TcpAudioChannel.kt`, `BridgeUpdateManager.kt`, `ConfigUpdateSender.kt`, `BridgeDiscovery.kt`, `ControlMessageSerializer.kt` |
| 1b | ✅ | Delete test files: `ControlMessageSerializerTest.kt`, `TransportIntegrationTest.kt`, `MockOalBridgeServer.kt` + also deleted `DiagnosticsSerializationTest.kt`, `VehicleDataSerializationTest.kt` (depended on `ControlMessageSerializer`) |
| 1c | ✅ | Clean `ControlMessage.kt` — removed `Hello`, `AppHello`, `ConfigUpdate`, `ConfigEcho`, `RestartServices`, `KeyframeRequest`, `BridgeUpdate*` types. Removed unused serialization imports |
| 1d | ✅ | Rewrote `SessionManager.kt` — removed `ConnectionManager`, `BridgeUpdateManager`, `ConfigUpdateSender` flows. Stubbed transport with `MutableStateFlow<ConnectionState>` + `MutableSharedFlow<ControlMessage>`. Added `isReconnecting` and `forceReconnect()` stubs. Removed `sendAppHello()`, `startVideoChannel/startAudioChannel/stopVideoChannel/stopAudioChannel`. Removed `videoCollectJob`/`audioCollectJob`. Simplified `BridgeInfo` to just `name`/`version`/`capabilities` |
| 1e | ✅ | Clean `SettingsScreen.kt` — removed `BridgeTab` composable (~490 lines), `BRIDGE` from `SettingsTab` enum, bridge discovery section from `ConnectionTab`, `bindUpdateManager` `LaunchedEffect`. Removed unused imports (`SettingsRemote`, `Search`, `Usb`) |
| 1f | ✅ | Rewrote `SettingsViewModel.kt` — removed `BridgeDiscovery`, `BridgeUpdateState`, `ConfigUpdateSender`, `DiscoveredBridge`, `UpdateHistoryEntry`. Removed `bindUpdateManager()`, `checkForBridgeUpdate()`, `saveAndRestart()`, `startDiscovery()`/`stopDiscovery()`/`selectBridge()`, bridge update state flows. Stubbed `requestPairedPhones()`/`switchPhone()`/`forgetPhone()` with TODOs |
| 1g | ✅ | Clean `AppPreferences.kt` — removed `getBridgeConfigSnapshot()`, `applyConfigEcho()`, `BRIDGE_AUTO_UPDATE`/`BRIDGE_AUTO_APPLY`/`GITHUB_REPO_OWNER`/`GITHUB_REPO_NAME` prefs + defaults + flows + setters. Removed `first()` import |
| 1h | ✅ | Created standalone `ConnectionState.kt` with `DISCONNECTED`, `CONNECTING`, `LISTENING`, `PHONE_CONNECTED`, `STREAMING` + `NetworkResolver` interface |
| 1i | ✅ | Updated `SessionState.kt` — renamed `BRIDGE_CONNECTED` → `LISTENING`, updated `toSessionState()` mapping |
| 1j | ✅ | Fixed `BRIDGE_CONNECTED` → `LISTENING` in `ProjectionScreen.kt`, `ProjectionViewModel.kt`, `DiagnosticsScreen.kt`, `SettingsScreen.kt`. Fixed `ConfigUpdateSender` → `sessionManager.sendControlMessage()` in `ProjectionViewModel.kt`. Fixed `bridgeVersionStr` → `null` in `ProjectionViewModel`. Removed `ControlMessage.Hello` handler from `DiagnosticsViewModel`. Fixed `saveAndRestart()` → `projectionViewModel.reconnect()` in `AppNavHost.kt`. Changed `MicCaptureManager` constructor from `BridgeConnection` to `(AudioFrame) -> Unit` lambda. Removed `ConnectionManager` from `TelemetryCollector` |
| 1k | ✅ | Deleted `docs/bridge-update.md` |
| 1l | ✅ | Deleted `docs/protocol.md` |
| 1m | ✅ | Updated `docs/architecture.md` — rewrote Transport island section with direct AA architecture |
| 1n | ⏭️ | Skipped — `docs/work-plan.md` update deferred (low priority) |

**Build gate:** ✅ `assembleDebug` passes, all unit tests pass.

### Phase 2: NDK/JNI Build Setup ✅ DONE

Copy native code from openautolink-direct.

| Step | Status | Description |
|------|--------|-------------|
| 2a | ✅ | Copied `app/src/main/cpp/` directory via `robocopy` from `openautolink-direct` |
| 2b | ✅ | Added `externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }` to `app/build.gradle.kts` |
| 2c | ✅ | Added `ndkVersion = "28.2.13676358"`, `abiFilters += listOf("arm64-v8a", "x86_64")`, cmake arg `-DOAL_NO_USB=ON` |
| 2d | ✅ | Added `startSessionWithFd()` to `aa_session.cpp` — both full mode (wraps fd in `boost::asio::ip::tcp::socket`, creates `TCPEndpoint` → `TCPTransport` → `createEntity()`) and stub mode. Fixed missing `onPhoneStatusUpdate` override in `JniPhoneStatusHandler` |
| 2e | ✅ | Added `Java_com_openautolink_app_transport_AasdkJni_startSessionWithFd` to `jni_bridge.cpp` + `external fun startSessionWithFd()` in `AasdkJni.kt` |
| 2f | ✅ | Updated `.github/instructions/app-kotlin.instructions.md` — rewrote "Bridge Cross-Reference Rule" to reference in-process aasdk via JNI |

**Build gate:** ✅ `assembleDebug` passes. arm64-v8a builds full aasdk mode (OpenSSL prebuilts
present), x86_64 auto-detects stub mode (no OpenSSL → `OAL_STUB_ONLY=ON`). All unit tests pass.

**Deviations from plan:**
- `AasdkJni.kt` was created in Phase 2 (plan said Phase 3a) since the NDK build needs it for JNI_OnLoad class lookup
- Added `.cxx/` to `.gitignore` (CMake build cache)
- Added `.gitignore` exception for `app/src/main/cpp/third_party/openssl/**/*.a` (prebuilt NDK deps)
- Fixed `JniPhoneStatusHandler` — interface had `onPhoneStatusUpdate()` pure virtual not in original code

### Phase 3: Direct AA Transport ✅ DONE

Created `DirectAaTransport.kt` for outbound relay mode (commit `18c7c31c`).

Note: `AasdkJni.kt` was already created in Phase 2.

| Step | Status | Description |
|------|--------|-------------|
| 3b | ✅ | Created `DirectAaTransport.kt` -- connects to bridge control (5288) + relay (5291), waits for `relay_ready`, extracts socket fd via reflection, calls `AasdkJni.startSessionWithFd()` |
| 3c | ✅ | Relay connection with `createSocket()`, `Network.bindSocket()` for USB Ethernet |
| 3d | ✅ | Control channel reads JSON lines: `hello`, `phone_bt_connected`, `relay_ready`, `relay_disconnected` |
| 3e | ✅ | `getSocketFd()` via reflection on `Socket.impl.fd` |
| 3f | ✅ | Delegates to `AasdkJni` SharedFlows (no extra buffering layer) |
| 3g | ✅ | `Touch` -> `AasdkJni.sendTouch()`, `Button` -> `AasdkJni.sendButton()` |
| 3h | ✅ | `Gnss` -> `AasdkJni.sendSensorData(0x01, nmea.toByteArray())` |
| 3i | ✅ | `sendMicAudio(pcm)` -> `AasdkJni.sendMicAudio(pcm)` |
| 3j | ✅ | Exponential backoff reconnect (1s-30s), `forceReconnect()` cancels + restarts |
| 3k | Deferred | `docs/networking.md` update deferred |

State flow: `DISCONNECTED → CONNECTING → LISTENING → PHONE_CONNECTED → STREAMING`

- `CONNECTING` — outbound TCP to bridge in progress
- `LISTENING` — relay socket connected, waiting for phone via bridge
- `PHONE_CONNECTED` — aasdk JNI reports phone connected
- `STREAMING` — first video frame received

### Phase 4: Wire Up SessionManager ✅ DONE

All forwarders now route through `DirectAaTransport` -> aasdk JNI (commit `18c7c31c`).

| Step | Status | Description |
|------|--------|-------------|
| 4a | ✅ | `SessionManager` creates `DirectAaTransport`, calls `t.connect(host)`. Stub flows removed |
| 4b | ✅ | GNSS forwarder -> `transport.sendControlMessage(Gnss)` -> `AasdkJni.sendSensorData()` |
| 4c | ✅ | Vehicle data forwarder -> `transport.sendControlMessage(VehicleData)` (protobuf TODO) |
| 4d | ✅ | IMU forwarder -> `transport.sendControlMessage()` |
| 4e | ✅ | MicCaptureManager -> `transport.sendMicAudio()` -> `AasdkJni.sendMicAudio()` |
| 4f | ✅ | Remote diagnostics -> `transport.sendControlMessage()` -> relay control JSON |
| 4g | ✅ | Video: `transport.videoFrames` -> `videoDispatcher` -> decoder. Audio: same pattern |
| 4h | ✅ | Session params set on transport before connect. DataStore resolution mapping TODO |
| 4i | Deferred | `docs/embedded-knowledge.md` update deferred |

### Phase 5: Settings UI Cleanup

Most of this was already done in Phase 1. Remaining items focus on relay control channel integration.

| Step | Description |
|------|-------------|
| 5a | ✅ Done in Phase 1 — `ConnectionTab` simplified to bridge IP + port only |
| 5b | ✅ Done in Phase 1 — bridge discovery (mDNS/UDP) removed from connection settings |
| 5c | No change needed — Video/Audio/Display settings are local, read by aasdk JNI at session start |
| 5d | ✅ Done in Phase 1 — "Bridge" tab removed from settings |
| 5e | Phone management (list paired phones, switch phone, forget phone) — forward through relay control channel to bridge BT scripts. `SettingsViewModel` methods stubbed with TODOs |
| 5f | Diagnostics tab — no change needed (diagnostics still flow through relay control) |
| 5g | `[doc]` **Update** `docs/testing.md` — rewrite testing topology: app no longer connects 3 OAL TCP channels to bridge, now connects relay (5291) + control (5288). Update mock bridge instructions (relay mock replaces `mock_bridge.py`). Keep emulator setup unchanged |
| 5h | `[doc]` **Update** `.github/instructions/ui-requirements.instructions.md` — remove any references to BridgeTab, bridge auto-update UI, OAL config_update settings flow |
| 5h | `[doc]` **Update** `.github/instructions/ui-requirements.instructions.md` — remove any references to BridgeTab, bridge auto-update UI, OAL config_update settings flow |

### Phase 6: Bridge TCP Relay Binary

A much simpler bridge binary replaces `openautolink-headless`:

| Component | Description |
|-----------|-------------|
| Control server | TCP:5288 — lightweight signaling (hello, relay_ready, phone_bt_connected) + diagnostics forwarding |
| Relay server | TCP:5291 — waits for app's outbound connection |
| Phone listener | TCP:5277 — waits for phone's AA connection (after BT/WiFi) |
| Splice loop | Once both relay + phone connected, `poll()` + `splice()` raw bytes |
| BT/WiFi | Same `aa_bt_all.py` — no changes to pairing/WiFi flow |
| Phone management | Forward `list_paired_phones`, `switch_phone`, `forget_phone` from app to BT scripts |

The relay binary does **zero** AA protocol processing. It doesn't know or care about
aasdk, TLS, protobuf, or video frames. Just raw bytes between two sockets.

**Implementation:** New C++ binary `openautolink-relay` (~200-300 lines). Same cross-compile
toolchain, tiny binary size (no aasdk/OpenSSL/Protobuf deps).

#### Phase 6 Implementation ✅ DONE

| Step | Status | Description |
|------|--------|-------------|
| 6a | ✅ | Created `bridge/openautolink/relay/` directory with `CMakeLists.txt` — minimal C++20, no external deps |
| 6b | ✅ | Created `bridge/openautolink/relay/src/main.cpp` — single-file relay binary (~340 lines) |
| 6c | ✅ | Control server (TCP:5288) — JSON lines: hello, relay_ready, relay_disconnected, phone_bt_connected, paired_phones, error |
| 6d | ✅ | Relay server (TCP:5291) — accepts app outbound connection, holds socket until phone connects |
| 6e | ✅ | Phone listener (TCP:5277) — accepts phone AA connection after BT/WiFi pairing |
| 6f | ✅ | Splice loop — `poll()` + non-blocking read/write between relay and phone sockets, stats on close |
| 6g | ✅ | Phone management — `list_paired_phones`, `switch_phone`, `forget_phone` via bluetoothctl (MAC validation for injection prevention) |
| 6h | ✅ | Diagnostics forwarding — `app_log` and `app_telemetry` from app written to stderr (journalctl/SSH) |
| 6i | ✅ | Control reader thread — reads JSON lines from app, dispatches to handlers |
| 6j | ✅ | Verified native x86_64 build (GCC 13, WSL) — clean compile, no warnings |
| 6k | ✅ | Verified ARM64 cross-compile (aarch64-linux-gnu-g++) — 98KB unstripped, 67KB stripped |

**Build gate:** ✅ Compiles cleanly on both x86_64 (native) and ARM64 (cross-compile) with
`-Wall -Wextra -Wpedantic`. Zero warnings. Binary is 67KB stripped — vs ~5MB+ for old
`openautolink-headless` (no aasdk, OpenSSL, Protobuf, Boost dependencies).

**Architecture notes:**
- Main loop: accept control → accept relay → poll for phone (with relay disconnect detection) → splice → loop
- Splice uses non-blocking sockets with `poll()` — handles back-pressure via short-write retry
- Signal handling: SIGINT/SIGTERM for clean shutdown, SIGPIPE ignored
- Thread model: main thread does accept + splice, dedicated thread reads control channel JSON lines
- Phone management calls are identical to old `oal_session.cpp` — same bluetoothctl commands, same MAC validation

### Phase 7: Bridge Deployment

| Step | Description |
|------|-------------|
| 7a | New systemd service: `openautolink-relay.service` |
| 7b | Update `install.sh` to deploy `openautolink-relay` instead of `openautolink-headless` |
| 7c | Update `/etc/openautolink.env` — remove aasdk-specific config (resolution, codec, etc.) since app owns those now. Keep network config (car IP, phone WiFi) |
| 7d | Update `deploy-bridge.ps1` to build and deploy relay binary |
| 7e | Update `build-bridge-wsl.sh` — much simpler build (no aasdk, no external/ submodules needed for bridge) |
| 7f | `[doc]` **Update** `bridge/sbc/BUILD.md` — rewrite for relay binary build (no aasdk, no OpenSSL, no Protobuf, no submodules) |
| 7g | `[doc]` **Update** `.github/instructions/bridge-cpp.instructions.md` — rewrite entirely: relay binary conventions replace headless aasdk conventions. Remove aasdk submodule section, thread model, v1.6 requirements. Add relay splice loop patterns, control channel signaling |
| 7h | `[doc]` **Update** `.github/instructions/bridge-dev-workflow.instructions.md` — update build/deploy commands for relay. Remove WSL aasdk cross-compile steps. Simplify Mode diagrams (relay replaces headless) |

### Phase 8: Remove Old Bridge & Script Code

Delete all `openautolink-headless` source code, OAL protocol bridge infrastructure, and
scripts that only exist for the old bridge model.

#### Bridge C++ — delete entirely

The entire headless binary is replaced by `openautolink-relay`. Delete:

| Path | Reason |
|------|--------|
| `bridge/openautolink/headless/src/live_session.cpp` | aasdk session management — now in app JNI |
| `bridge/openautolink/headless/src/oal_session.cpp` | OAL protocol relay — eliminated |
| `bridge/openautolink/headless/src/session.cpp` | Stub session — no longer needed |
| `bridge/openautolink/headless/src/engine.cpp` | Backend engine — no longer needed |
| `bridge/openautolink/headless/src/service_catalog.cpp` | AA service catalog — now in app JNI |
| `bridge/openautolink/headless/src/aasdk_control.cpp` | AA control handler — now in app JNI |
| `bridge/openautolink/headless/src/sco_audio.cpp` | SCO/HFP BT audio — now in app JNI |
| `bridge/openautolink/headless/src/contract.cpp` | AA contract/config — now in app JNI |
| `bridge/openautolink/headless/src/bt_auth_stub.cpp` | BT auth stub — no longer needed |
| `bridge/openautolink/headless/src/main.cpp` | Headless binary entry point — replaced by relay |
| `bridge/openautolink/headless/headless_tcp_server.cpp` | TCP server test — obsolete |
| `bridge/openautolink/headless/ocd_headless_test.cpp` | OCD test binary — obsolete |
| `bridge/openautolink/headless/include/` (all headers) | Headers for above — all go |
| `bridge/openautolink/headless/CMakeLists.txt` | Build for headless binary — replaced |
| `bridge/openautolink/headless/CMakeLists_headless_tcp.txt` | Alternate build — obsolete |
| `bridge/openautolink/headless/CMakeLists_ocd_test.txt` | Test build — obsolete |
| `bridge/openautolink/headless/patches/` | aasdk patches for headless — no longer needed |
| `bridge/openautolink/headless/avahi/` | mDNS config for headless — relay has its own |

**Keep:** `bridge/openautolink/headless/README.md` — update to document relay architecture.

#### SBC deployment scripts — update or delete

| Path | Action | Reason |
|------|--------|--------|
| `bridge/sbc/openautolink.service` | **Replace** | Points to headless binary → point to relay |
| `bridge/sbc/run-openautolink.sh` | **Replace** | Launches headless with aasdk flags → launch relay (much simpler) |
| `bridge/sbc/apply-bridge-update.sh` | **Delete** | Auto-update mechanism removed — relay binary deployed manually or via simple scp |
| `bridge/sbc/openautolink.env` | **Simplify** | Remove all `OAL_VIDEO_*`, `OAL_AA_*`, `OAL_PHONE_PROTOCOL` vars. Keep network vars (`OAL_CAR_NET_*`, `OAL_PHONE_MODE`) |
| `bridge/sbc/install.sh` | **Update** | Deploy relay binary instead of headless. Remove aasdk/OpenSSL/Protobuf dep installation |
| `bridge/sbc/BUILD.md` | **Update** | Document relay build (trivially simple — no aasdk submodule needed) |
| `bridge/sbc/setup-network.sh` | **Keep** | Network setup is unchanged |
| `bridge/sbc/start-wireless.sh` | **Keep** | WiFi AP + BT unchanged |
| `bridge/sbc/stop-wireless.sh` | **Keep** | WiFi AP shutdown unchanged |
| `bridge/sbc/openautolink-bt.service` | **Keep** | BT pairing service unchanged |
| `bridge/sbc/openautolink-network.service` | **Keep** | Network service unchanged |
| `bridge/sbc/openautolink-wireless.service` | **Keep** | WiFi AP service unchanged |

#### Dev/deployment scripts — update or delete

| Path | Action | Reason |
|------|--------|--------|
| `scripts/deploy-bridge.ps1` | **Update** | Build + deploy relay instead of headless. Much simpler — no WSL aasdk cross-compile |
| `scripts/build-bridge-wsl.sh` | **Update** | Build relay binary only — no aasdk, no external/ submodule deps |
| `scripts/mock_bridge.py` | **Delete** | Mocks OAL protocol bridge — no longer applicable. New mock would be a relay mock (much simpler) |
| `scripts/start-mock-bridge.ps1` | **Delete** | Launches mock_bridge.py — goes with it |
| `scripts/setup-wsl-cross-compile.sh` | **Simplify** | Remove aasdk/OpenSSL/Protobuf/Boost dep installation — relay only needs basic toolchain |

#### CI workflow

| Path | Action | Reason |
|------|--------|--------|
| `.github/workflows/release-bridge.yml` | **Update** | Build relay binary instead of headless. Remove aasdk submodule checkout, cross-compile deps. Much faster CI |

#### Build artifacts

| Path | Action | Reason |
|------|--------|--------|
| `build-bridge-arm64/` | **Delete contents** | Old headless binary artifacts — rebuild produces relay |
| `build-stub/` | **Delete** | CMake stub build for headless — obsolete |

#### Documentation — final sweep

These are the top-level project docs that describe the overall architecture and must
be consistent with every phase's changes. Update after Phase 8 deletions are done.

| Doc | Action | What changes |
|-----|--------|--------------|
| `.github/copilot-instructions.md` | **Major rewrite** | Architecture diagram (remove 3-TCP OAL, show relay + JNI). Transport island description. "Cross-Component Rule" (no more "read bridge C++ first" — aasdk is in-app). Build commands (add NDK build). External Dependencies table (aasdk now used via NDK, not bridge binary). Conventions: OAL Wire Protocol section → delete or replace with relay signaling. Video Rules unchanged. Pitfalls: remove "aasdk v1.6 ServiceConfiguration format" (app owns this now). Add NDK/JNI pitfalls |
| `README.md` | **Major rewrite** | "How It Works" architecture diagram. "What You Need" (bridge SBC is simpler). "Repository Layout". "Quick Start" (no bridge auto-update) |
| `.github/instructions/aa-developer-mode.instructions.md` | **Review** | May reference bridge-side AA testing — update if needed |
| `.github/instructions/audio-pipeline.instructions.md` | **Review** | Audio data now comes from JNI not TCP — update source references if any |
| `.github/instructions/video-pipeline.instructions.md` | **Review** | Video data now comes from JNI not TCP — update source references if any |
| `.github/instructions/release-bundle.instructions.md` | **Update** | AAB now includes native .so — add NDK build notes to release workflow |
| `docs/custom-viewport.md` | **Review** | Viewport config sent via aasdk SDR now, not bridge config_update |

## Phase Order & Dependencies

```
Phase 1: Remove Bridge Code + delete OAL docs (protocol.md, bridge-update.md)
  └─▶ Phase 2: NDK/JNI Build + update app-kotlin.instructions.md
        └─▶ Phase 3: Direct AA Transport + update networking.md
              └─▶ Phase 4: Wire Up SessionManager + update embedded-knowledge.md
                    └─▶ Phase 5: Settings UI Cleanup + update testing.md, ui-requirements

Phase 6: Bridge Relay Binary (independent — can parallel with any app phase)
  └─▶ Phase 7: Bridge Deployment + update bridge-cpp, bridge-dev-workflow, BUILD.md
        └─▶ Phase 8: Remove Old Bridge Code + final doc sweep (copilot-instructions, README, etc.)
```

**Integration testing** requires Phase 5 + Phase 7 both complete.

## Settings Flow: Before vs After

### Before (bridge mode)
```
User changes resolution in app settings
  → AppPreferences saves to DataStore
  → ConfigUpdateSender emits config_update map
  → SessionManager collects, serializes to JSON
  → ConnectionManager sends over TcpControlChannel
  → Bridge receives JSON, updates env file
  → Bridge restarts aasdk with new config
  → Phone renegotiates AA session
```

### After (direct mode)
```
User changes resolution in app settings
  → AppPreferences saves to DataStore
  → DirectAaTransport reads from DataStore on next session start
  → AasdkJni.startSessionWithFd(fd, width, height, fps, dpi)
  → aasdk sends ServiceDiscoveryResponse with new resolution to phone
  → Done. No bridge involved.
```

For settings that take effect immediately (mid-session change), the JNI layer would need a
`reconfigureSession()` that tears down and rebuilds the aasdk entity. But most settings
(resolution, codec, FPS) already require a phone reconnect to take effect, so this is low
priority.

## Testing Strategy

### Phase 1: Compile check ✅
- `./gradlew :app:assembleDebug` succeeds after all removals
- No references to deleted classes remain
- All remaining unit tests pass

### Phase 2: NDK build ✅
- `./gradlew :app:assembleDebug` succeeds with NDK
- arm64-v8a: full aasdk build (OpenSSL prebuilts present)
- x86_64: auto-detects stub mode (`OAL_STUB_ONLY=ON`)
- `AasdkJni.isAvailable` returns true on emulator (pending Phase 3 verification)

### Phase 3-4: Mock relay testing
- Python mock relay script: accept app's outbound connection, accept phone connection, splice
- `DirectAaTransport` connects, shows `LISTENING` state
- Feed recorded AA session → verify video/audio renders

### Phase 5: UI verification
- Settings screens render without crashes
- No "Bridge" tab visible
- Connection tab shows only bridge relay IP

### Phase 6-7: On-device testing
- Deploy `openautolink-relay` to SBC
- Phone BT pairs → WiFi joins → TCP connects to bridge
- Bridge splices relay → aasdk in app runs → video/audio streaming
- Car sleep/wake → clean reconnect

## Risk Assessment

| Risk | Severity | Mitigation |
|------|----------|------------|
| Socket fd passing JNI → Boost.Asio | Low | `boost::asio::ip::tcp::socket(io, tcp::v4(), fd)` — well documented |
| NDK OpenSSL build for arm64 | Medium | Already solved in openautolink-direct with prebuilt .a files |
| Relay latency | Low | Raw splice adds <0.1ms per packet, negligible |
| Protobuf version conflicts | Medium | Pin v3.21.12 (no Abseil dep), last C++17-compatible release |
| aasdk thread safety in app process | Medium | Dedicated io_service thread — same model as bridge |
| AAOS blocking outbound too? | Very Low | Current bridge mode proves outbound TCP works fine |
| Boost.Asio on Android NDK | Low | Header-only, well-tested on Android. openautolink-direct already builds |
| Removing too much — breaking diag | Low | Remote diagnostics use a simple callback interface, decoupled from transport |
| Phone management (pair/forget) | Medium | Still needs bridge-side BT control — relay control channel handles this |

---

### Phase 9: AA Session Parity — Port Bridge Features to In-App aasdk

The in-app `aa_session.cpp` was copied from `openautolink-direct` which was an early proof-of-concept.
The old bridge `live_session.cpp` had ~3500 lines of battle-tested AA session code with many features
and bug fixes that never made it to the in-app version. This phase ports all missing features.

**Reference:** `git show main:bridge/openautolink/headless/src/live_session.cpp`

**All changes are in `app/src/main/cpp/aa_session.cpp` (C++) unless noted.**

#### 9a. SDR — Multi-Codec Auto-Negotiate (CRITICAL)

Currently offers H.264 only at a single resolution tier. The old bridge offered H.265 at all tiers
(5→1) plus H.264 at ≤1080p as fallback, letting the phone pick the best combo.

| Step | Description |
|------|-------------|
| 9a-1 | Add H.265 (codec type 7) video configs at tiers 5,4,3,2,1 |
| 9a-2 | Add H.264 (codec type 3) fallback at tiers 3,2,1 |
| 9a-3 | Per video_config: set `margin_width`, `margin_height`, `pixel_aspect_ratio_e4`, `density` |
| 9a-4 | Set `decoder_additional_depth(2)` on each config |
| 9a-5 | Add VP9 configs if supported (codec type 9, tier 3+) — optional |
| 9a-6 | Read `videoAutoNegotiate` pref from SessionConfig — if false, offer single codec/tier only |

**Old code pattern:**
```cpp
for (int t : {5, 4, 3, 2, 1}) add_video_config(t, 7); // H.265
for (int t : {3, 2, 1}) add_video_config(t, 3);        // H.264 fallback
```

#### 9b. SDR — Session Configuration (HIGH)

Missing: `session_configuration` bitmask that hides clock/signal/battery on the AA projection.

| Step | Description |
|------|-------------|
| 9b-1 | Add `hideClock`, `hidePhoneSignal`, `hideBatteryLevel` to `SessionConfig` |
| 9b-2 | Pass from DataStore prefs via JNI (extend `startSessionWithFd` params or use a config struct) |
| 9b-3 | Build bitmask: `bit0=clock, bit1=signal, bit2=battery`, set `resp.set_session_configuration(mask)` |

#### 9c. SDR — Connection Configuration (HIGH)

Missing: `connection_configuration` (ping timeout, interval, high-latency threshold).

| Step | Description |
|------|-------------|
| 9c-1 | Add `ConnectionConfiguration` to SDR: timeout=5000, interval=1500, high_latency=500, max_tracked=5 |

#### 9d. SDR — Full Sensor Declaration (CRITICAL)

Currently declares 3 sensor types. Old bridge declared 18. AA uses these to know what vehicle
data it can request and display.

| Step | Description |
|------|-------------|
| 9d-1 | Add all sensor types: SPEED, GEAR, PARKING_BRAKE, FUEL, ODOMETER, ENVIRONMENT_DATA, DOOR_DATA, LIGHT_DATA, TIRE_PRESSURE, HVAC, ACCELEROMETER, GYROSCOPE, COMPASS, GPS_SATELLITE, RPM |
| 9d-2 | Set `location_characterization` to `256\|4\|2\|8\|64` (GPS+accel+gyro+compass+speed) |
| 9d-3 | Add `supported_fuel_types` (ELECTRIC=10 default) |
| 9d-4 | Add `supported_ev_connector_types` (J1772=1, COMBO_1=5 default) |
| 9d-5 | Make fuel/EV types configurable from SessionConfig (vehicle identity from VHAL) |

#### 9e. SDR — Bluetooth Service (HIGH)

BT MAC is hardcoded to `00:00:00:00:00:00`. Old bridge auto-detected or read from config.

| Step | Description |
|------|-------------|
| 9e-1 | Add `btMac` to SessionConfig, pass from DataStore prefs |
| 9e-2 | Set `bs->set_car_address(config_.btMac)` |
| 9e-3 | Add `BLUETOOTH_PAIRING_NUMERIC_COMPARISON` to supported pairing methods |

#### 9f. SDR — Input Keycodes (MEDIUM)

Missing keycodes 89 (REWIND) and 90 (FAST_FORWARD).

| Step | Description |
|------|-------------|
| 9f-1 | Add keycodes 89, 90 to `keycodes_supported` list |

#### 9g. Navigation State Handler (HIGH)

The nav handler (`JniNavHandler`) receives all events but discards them (no-op methods).
Old bridge parsed turn events, distance, status, and icons and forwarded to the app.

| Step | Description |
|------|-------------|
| 9g-1 | Implement `onStatusUpdate` — parse nav active/inactive/rerouting, call `oal::jni::notifyNavState()` |
| 9g-2 | Implement `onTurnEvent` — parse maneuver type, side (left/right), road name, cue |
| 9g-3 | Implement `onDistanceEvent` — parse distance meters, ETA seconds |
| 9g-4 | Implement nav image forwarding — base64 encode PNG, include in nav state |
| 9g-5 | Add JNI callback `onNavState(maneuver, distance, road, eta, imageBase64, ...)` |
| 9g-6 | Handle nav clear (status=INACTIVE → notify clear) |

#### 9h. Voice Session Handler (HIGH)

`onVoiceSessionRequest` is empty. Phone voice assistant won't trigger properly.

| Step | Description |
|------|-------------|
| 9h-1 | Implement `onVoiceSessionRequest` — extract start/stop from `VoiceSessionNotification` |
| 9h-2 | Add JNI callback `onVoiceSession(started: Boolean)` |
| 9h-3 | Wire to `AasdkJni.emitControlMessage(ControlMessage.VoiceSession(...))` |

#### 9i. Battery Status Handler (MEDIUM)

`onBatteryStatusNotification` is empty. No phone battery level forwarding.

| Step | Description |
|------|-------------|
| 9i-1 | Extract battery level, time remaining, critical flag from notification |
| 9i-2 | Add JNI callback `onPhoneBattery(level, timeRemaining, critical)` |
| 9i-3 | Wire to `AasdkJni.emitControlMessage(ControlMessage.PhoneBattery(...))` |

#### 9j. Mic Silence Pump (HIGH)

When AA requests mic (voice assistant), the phone expects audio frames immediately.
If there's a gap before the car mic starts, the phone times out. Old bridge pumped
640-byte silence frames at 20ms intervals until real mic data arrived.

| Step | Description |
|------|-------------|
| 9j-1 | In `JniAudioInputHandler::onMediaSourceOpenRequest`, start a silence pump timer |
| 9j-2 | Pump 640-byte zero PCM frames at 20ms intervals |
| 9j-3 | Stop pump when `feedAudio()` receives real data or on `onMediaSourceCloseRequest` |

#### 9k. UI Theme Update (HIGH)

Old bridge sent `UpdateUiConfigRequest` with dark/light theme to sync AA's rendering
with the car's theme. Can be triggered at session start and on theme change.

| Step | Description |
|------|-------------|
| 9k-1 | Add `syncAaTheme` to SessionConfig |
| 9k-2 | On session start, send `UpdateUiConfigRequest` with `ui_theme = DARK` (car default) |
| 9k-3 | Expose JNI function `sendUiThemeUpdate(isDark)` for runtime theme changes |

#### 9l. Graceful Shutdown (HIGH)

Old bridge sent `ByeByeRequest` to the phone before closing, giving it time to clean up.

| Step | Description |
|------|-------------|
| 9l-1 | In `stopSession()`, if entity is active, send `ByeByeRequest` via control channel |
| 9l-2 | Wait up to 500ms for response before force-closing |

#### 9m. H.265/VP9 Keyframe Detection (CRITICAL)

Video handler only detects H.264 NAL types. H.265 and VP9 keyframes are not recognized,
breaking IDR-wait logic for those codecs.

| Step | Description |
|------|-------------|
| 9m-1 | Add H.265 NAL parsing: type = `(byte >> 1) & 0x3F`, IDR = 19/20, VPS/SPS/PPS = 32-34 |
| 9m-2 | Add VP9 keyframe detection: `!(frame[0] & 0x04)` in frame header |
| 9m-3 | Support 3-byte start codes (`0x00 0x00 0x01`) in addition to 4-byte |

#### Build gate

`./gradlew :app:assembleDebug` succeeds. All unit tests pass. Rebuild NDK for both ABIs.
