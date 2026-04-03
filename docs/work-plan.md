# OpenAutoLink — Work Plan

---

## 🔄 Carry-Forward Issues (Bridge-Side)

These exist in the current bridge code and will need fixing regardless of the app rewrite.

### 1. Video Startup Delay (High Priority)
- 65s gap between phone connect and car app connect creates stale frame backlog
- 2666 frames dropped at startup (MAX_PENDING=120 cap)
- **Fix:** Don't queue video frames until car app is connected (`client_fd_ < 0` → skip). Clear pending on new connection

### 2. Video FPS Below Target
- Stats show 28-52fps (target 60fps)
- May be bridge sending at 30fps despite `OAL_AA_FPS=60`
- **Verified:** SDR correctly requests `VIDEO_FPS_60` when `config_.video_fps >= 60` (default env = 60). If phone still sends 30fps, it's a phone-side limitation

### 3. Phone AA Session Drops (Error 33)
- Phone occasionally drops TCP with EOF
- Bridge cert files not deployed → search fails on restart
- **Fixed:** `install.sh` now generates and deploys `headunit.crt/key` to `/etc/aasdk/`

### 4. Black Screen After Reconnect
- Bridge rate-limits keyframe replay to 5s
- After app reconnect, no fresh IDR available
- **Fix:** Bridge should bypass rate limit on first keyframe request after new app connection

### 5. Bluetooth HFP Not Working
- BT pairing works, BLE works, RFCOMM ch8 works, WiFi credential exchange works
- HFP (Hands-Free Profile) NOT connected → no BT audio routing for calls
- **Architecture:** Phone pairs via BT to the SBC/bridge, NOT to the car. Call/voice audio must flow: Phone → BT HFP → SBC → bridge captures SCO audio → forwards over TCP to app
- **Needed for:** phone calls, voice assistant, proper AA auto-connect

---

## 🔧 Bridge Milestones

### B1: OAL Protocol Migration

**Blocks:** M3 (HFP audio), M5 (mic control), M6 (config sync), end-to-end testing

Replace CPC200 framing (16-byte magic headers, inverted checksums, heartbeat-gated writes) with OAL protocol on all three TCP channels. The app already speaks OAL — once this lands, end-to-end streaming works.

**Control Channel (Port 5288) → JSON Lines**
- [x] JSON line messages for all control communication
- [x] Hello handshake with capabilities exchange
- [x] Phone connected/disconnected events
- [x] Audio start/stop per purpose
- [x] Nav state forwarding
- [x] Media metadata forwarding
- [x] Config echo on settings change
- [x] Mic start/stop signals

**Video Channel (Port 5290) → 16-byte Header**
- [x] OAL 16-byte header: payload_length, width, height, pts_ms, flags
- [x] Flags: keyframe bit, codec config bit, EOS bit
- [x] First frame must be codec config (SPS/PPS)
- [x] Fix carry-forward #1: don't queue video until app is connected
- [x] Fix carry-forward #4: bypass IDR rate limit on first keyframe after new app connection

**Audio Channel (Port 5289) → 8-byte Header**
- [x] OAL 8-byte header: direction, purpose, sample_rate, channels, length
- [x] Direction field (0=playback, 1=mic capture)
- [x] Purpose field for routing (media/nav/assistant/call/alert)
- [x] Bidirectional: bridge→app playback, app→bridge mic

**Touch/Input Channel (via Control 5288)**
- [x] JSON touch events with action, coordinates, pointer array
- [x] GNSS NMEA forwarding
- [x] Vehicle data JSON

**Also resolves carry-forward issues:** #1 (video startup delay — fixed), #2 (fps — verified SDR requests 60fps correctly), #4 (black screen after reconnect — fixed).

### B2: Bluetooth HFP + Auto-Connect

**Blocks:** M3 (call audio), M5 (mic routing), M9 (voice button)

Establish HFP profile so phone calls and voice assistant audio flow through the bridge.

- [x] Connect HFP profile after BT pairing (currently only BLE + RFCOMM ch8)
- [x] Capture SCO audio from HFP → forward as OAL PCM with call/assistant purpose
- [x] Forward mic PCM from app → BT SCO for phone call uplink
- [x] Fix carry-forward #3: deploy headunit.crt/key to `/etc/aasdk/`
- [x] AA auto-connect via BT (phone discovers bridge, starts WiFi TCP automatically)

### B3: CPC200 Legacy Cleanup

**Depends on:** B1, B2 (all OAL migration and HFP work complete)

Full audit of the bridge codebase to remove any remaining CPC200/carlink_native artifacts. The bridge was carried forward from carlink_native — while major framing was replaced in B1, residual code patterns, unused helpers, dead config paths, or commented-out CPC200 logic may remain.

- [x] Audit all `.cpp`/`.h` files for CPC200 references: magic bytes, inverted checksums, heartbeat logic, CPC200 header structs
- [x] Remove dead code paths gated on CPC200 framing (unused branches, legacy frame builders/parsers)
- [x] Remove any unused CPC200 helper functions, constants, or type definitions
- [x] Clean up commented-out CPC200 code blocks — if it's not OAL, delete it
- [x] Verify no CPC200 framing leaks into OAL protocol paths (regression check)
- [x] Review CMakeLists.txt for unused source files or compile flags related to CPC200
- [x] Update any remaining comments or documentation referencing CPC200 wire format
- [x] Full build + test pass to confirm nothing broke

### B4: CarPlay Bridge Support

**Depends on:** B1 (OAL protocol), B2 (BT/HFP infrastructure)

Add wireless CarPlay as an alternative phone-side session. The bridge accepts either an Android phone (AA via aasdk) or an iPhone (CarPlay via HomeKit + AirPlay), runtime-selectable via `OAL_PHONE_PROTOCOL` env var. The car app is unchanged — it receives the same OAL frames regardless of source.

See [CarPlay Implementation Plan](../docs/future/carplay-bridge-implementation.md) for full architecture and phased breakdown.

**Phase 1: Discovery**
- [ ] Bonjour advertisement (`_carplay._tcp` + `_airplay._tcp`) via avahi-client
- [ ] Basic RTSP listener on TCP:5000 (OPTIONS/DESCRIBE)
- [ ] `CarPlaySession` skeleton + `--session-mode=carplay-live` CLI
- [ ] CarPlay BT profile in `aa_bt_all.py` (SDP UUID + BLE advertisement)
- [ ] `OAL_PHONE_PROTOCOL` env var in `openautolink.env`

**Phase 2: HomeKit Pairing**
- [ ] SRP-6a pair-setup (first-time PIN-based pairing)
- [ ] Pair-verify (X25519 + HKDF for subsequent reconnections)
- [ ] Encrypted control channel (ChaCha20-Poly1305)
- [ ] `carplay_pin` control message → app displays PIN screen
- [ ] Long-term key storage on bridge

**Phase 3: AirPlay Streams**
- [ ] AirPlay video stream (H.264) decrypt + deframe → `oal.write_video_frame()`
- [ ] AirPlay audio stream (ALAC/AAC→PCM decode) → `oal.write_audio_frame()`
- [ ] libavcodec dependency for ALAC/AAC decoding

**Phase 4: Input (Touch + HID)**
- [ ] OAL touch → CarPlay HID touch reports
- [ ] Siri button, home button via HID key events

**Phase 5: Auto Mode**
- [ ] Both AA + CarPlay listeners active simultaneously
- [ ] `std::atomic<PhoneProtocol>` — first phone to connect wins
- [ ] `OAL_PHONE_PROTOCOL=auto` support

---

## 📱 App Milestones (New Build)

See [docs/architecture.md](architecture.md) for full component island breakdown and public APIs.

### M1: Connection Foundation
- [x] Gradle project scaffold (min SDK 32, Compose, DataStore)
- [x] Transport island: TCP connect, JSON control parsing, reconnect
- [x] Session state machine (IDLE → CONNECTING → BRIDGE_CONNECTED → PHONE_CONNECTED → STREAMING)
- [x] ProjectionScreen with SurfaceView + connection status HUD

### M2: Video
- [x] MediaCodec decoder with codec selection (H.264/H.265/VP9)
- [x] OAL video frame parsing (16-byte header)
- [x] NAL parsing for SPS/PPS extraction
- [x] Stats overlay (FPS, codec, drops)

### M3: Audio
- [x] 5-purpose AudioTrack slots with ring buffers
- [x] OAL audio frame parsing (8-byte header)
- [x] Audio focus management (request/release/duck)
- [x] Purpose routing (media/nav/assistant/call/alert)
- [x] Dual audio path support — all audio flows through the bridge via TCP: *(unblocked by B1 + B2)*
  - **AA session audio** (aasdk channels): media, navigation, alerts — decoded by aasdk, sent as PCM over OAL *(B1 done — bridge sends OAL audio frames)*
  - **BT HFP audio** (phone → SBC Bluetooth): phone calls, voice assistant — bridge captures SCO audio from HFP and forwards as PCM over OAL with call/assistant purpose *(B2 done — bridge has SCO↔OAL bridge)*
- [x] Detect active audio purpose and manage focus (e.g., duck media during call)
- [x] Handle call audio transitions: ring, in-call, call end

### M4: Touch + Input
- [x] Touch forwarding with coordinate scaling
- [x] Multi-touch (POINTER_DOWN/UP for pinch zoom)
- [x] JSON touch serialization to control channel

### M5: Microphone + Voice
- [x] Timer-based mic capture from car's mic (via AAOS AudioRecord)
- [x] Send on audio channel (direction=1)
- [x] Mic source preference: car mic (default) or phone mic, toggled in Settings
- [x] Bridge mic_start/mic_stop control messages *(unblocked by B1)*
- [x] Coordinate mic routing: bridge forwards mic PCM to aasdk for AA voice, and to BT SCO for phone calls *(unblocked by B2 — bridge routes by purpose)*

### M6: Settings + Config
- [x] DataStore preferences (codec, resolution, fps, display mode) — basic prefs done in M1, display mode added with M2
- [x] Settings Compose UI — bridge host/port, display mode selector
- [x] Config sync: app → bridge → echo *(unblocked by B1)*
- [x] Bridge discovery (mDNS + manual IP)

### M6b: Self-Update via GitHub Pages

Enable OTA-style self-updating so new builds can be deployed without AAB/Play Store round-trips. Critical for fast iteration on M8/M9 (no ADB access on the car).

- [x] `REQUEST_INSTALL_PACKAGES` permission + `FileProvider` for APK sharing
- [x] `update/` island: `UpdateManifest`, `UpdateChecker`, `AppInstaller`
- [x] GitHub Pages manifest check (`update.json` with versionCode, APK URL, changelog)
- [x] Download APK to app-internal cache, trigger `PackageInstaller` session
- [x] DataStore preference: self-update enabled (default: off)
- [x] DataStore preference: update manifest URL
- [x] Settings UPDATES tab: toggle, URL field, Check Now button, download progress, changelog display
- [x] Graceful failure if AAOS blocks `REQUEST_INSTALL_PACKAGES` (show user-friendly error)
- [x] ProGuard keep rules for serialization of `UpdateManifest` — existing wildcard rules already cover all `@Serializable` classes
- [ ] Verify on car: can AAOS install APKs from non-system sources?

### M7: Vehicle Integration
- [x] GNSS forwarding (LocationManager → NMEA → bridge)
- [x] VHAL properties (37 properties via Car API reflection)
- [x] Navigation state display + maneuver icons

### M8: Cluster Display
- [x] Cluster `CarAppService` + `ClusterMainSession` (GM path: `NavigationManager.updateTrip()` relay)
- [x] `OalClusterSession` fallback (standard AAOS path: direct `NavigationTemplate` rendering with `RoutingInfo`)
- [x] Cluster navigation: `Maneuver.TYPE_*` enums + distance + road name via `Trip` builder
- [x] `MediaBrowserService` + `MediaSession` for cluster media: album artwork, track info
- [x] Bridge → app: `album_art_base64` field in `media_metadata` control message
- [x] `ClusterIconShimProvider` for Templates Host icon caching (GM-specific workaround)
- [x] Handle GM restrictions: `ClusterManager` detects session death via `ClusterBindingState.sessionAlive`, re-launches `CarAppActivity` binding chain with backoff
- [x] Fallback rendering if cluster service is blocked: `launchClusterBinding()` catches and suppresses failures, cluster degrades silently while `MediaSession` continues
- [x] `ClusterBindingState` tracking + auto-relaunch after teardown
- [x] Bridge: aasdk `NavigationStatusService` configured with `InstrumentClusterType::IMAGE` + `image_options` (256×256, 32-bit)
- [x] Bridge: aasdk `NavigationStatusService` extended with `onNavigationState()` + `onCurrentPosition()` handlers for `NavigationState` proto (msg 32774) and `NavigationCurrentPosition` (msg 32775) — 42 maneuver types
- [x] Bridge: forward maneuver icon PNG bytes from `NavigationNextTurnEvent.image` (IMAGE mode) → base64-encode → `nav_image_base64` field in `nav_state` control message
- [x] App: when `nav_image_base64` present, use `Maneuver.TYPE_UNKNOWN` + `CarIcon.Builder(IconCompat.createWithBitmap(...))` — AA icon is source of truth
- [x] App: fall back to `Maneuver.TYPE_*` enum mapping + bundled VectorDrawable icon when no image available
- [x] Bundled VectorDrawable icon set (44 drawables from CarPlay `cp_maneuver_*` set) + `ManeuverIconRenderer` mapping `ManeuverType` → drawable resource
- [x] `ClusterManager` utility: enables/disables CarAppService component, launches/restarts cluster binding, handles teardown recovery

### M9: Steering Wheel Controls
- [x] Media button mapping: skip forward, skip back, play/pause via `KeyEvent` interception
- [x] Volume controls via `AudioManager` or `KeyEvent`
- [x] Voice button interception: intercept the AAOS voice/assistant `KeyEvent` (currently launches Google Assistant) and forward as AA voice trigger to activate Gemini on the phone
- [ ] Investigate `KEYCODE_VOICE_ASSIST` / `KEYCODE_SEARCH` interception feasibility on GM AAOS (may require accessibility service or input method)

### M10: Polish
- [x] Diagnostics screen
- [x] Error recovery (reconnect, codec reset)
- [x] Display modes (fullscreen, system bars, custom viewport) — pulled forward, implemented with M2; custom viewport added later
- [x] Custom viewport editor — draggable edge bars, aspect ratio lock, presets, manual pixel input
- [x] Overlay buttons (settings, stats) — pulled forward, draggable floating buttons
- [x] App icon and logo — adaptive icon from brand asset
- [x] Stats for nerds overlay — monospace panel with session/video stats

### M10b: Modern Navigation Data (Lane Guidance + Enriched Nav)

**Purpose:** Upgrade from AA's deprecated NavigationNextTurnEvent/DistanceEvent (msg 32772/32773) to the modern NavigationState (msg 32774) and NavigationCurrentPosition (msg 32775) protobuf messages. This unlocks lane guidance, richer maneuver types, cue text, roundabout details, pre-formatted distances, destination addresses, and formatted ETA.

**Bridge Side:**
- [x] Override `onNavigationState()` — parse `NavigationStep.lanes`, `NavigationManeuver`, `NavigationCue`, `NavigationRoad`, `NavigationDestination`
- [x] Override `onCurrentPosition()` — parse `NavigationStepDistance`, `NavigationDestinationDistance`, `NavigationDistance.display_value/display_units`, current road, formatted ETA
- [x] Map 43 modern `NavigationType` enums to wire strings (vs legacy's 19 sparse enums)
- [x] Map `LaneDirection.Shape` enums (0-9) to wire strings for lane guidance
- [x] Send enriched `nav_state` JSON with new fields: `lanes`, `cue`, `roundabout_exit_number`, `roundabout_exit_angle`, `display_distance`, `display_distance_unit`, `current_road`, `destination`, `eta_formatted`, `time_to_arrival_seconds`
- [x] Suppress legacy turn/distance events when modern nav is active (`has_modern_nav_` flag)
- [x] Continue extracting maneuver icon PNG from legacy turn events (IMAGE mode) — cached for inclusion in modern nav_state

**App Side:**
- [x] Extend `ControlMessage.NavState` with new fields: `lanes`, `cue`, `roundaboutExitNumber`, `displayDistance`, `displayDistanceUnit`, `currentRoad`, `destination`, `etaFormatted`, `timeToArrivalSeconds`
- [x] Parse lane guidance JSON array in `ControlMessageSerializer`
- [x] Extend `ManeuverState` with `lanes: List<LaneInfo>`, `cue`, `roundaboutExitNumber`, `currentRoad`, `destination`, `etaFormatted`
- [x] Extend `ManeuverType` enum: added `KEEP_LEFT/RIGHT`, `MERGE_UNSPECIFIED`, `ON_RAMP_SLIGHT/SHARP/U_TURN` variants, `OFF_RAMP_SLIGHT` variants, `ROUNDABOUT_ENTER_AND_EXIT_CW/CCW`, `DESTINATION_STRAIGHT`, `FERRY_TRAIN`
- [x] Use pre-formatted distance from bridge when available (respects phone locale)
- [x] Build `Lane` + `LaneDirection` objects for cluster `Step.Builder.addLane()`
- [x] Set `Step.setRoad()` for road name (separate from cue text)
- [x] Use cue text for `Step.setCue()` when available (e.g. "Turn right onto Main St")
- [x] Updated `ManeuverMapper`, `ManeuverIconRenderer`, `mapManeuverTypeToCarApp` for all new types

### M11: Remote Diagnostics (Car Testing Enabler)

**Purpose:** Since GM AAOS has no ADB access, we need a way to observe app behavior in real-time on the car. The app sends structured logs and periodic telemetry back to the bridge over the existing control channel (port 5288). The bridge writes them to stderr, visible via `journalctl` over SSH.

This unblocks all car-specific validation items listed in the Car Testing Unknowns section below.

**App Side:**
- [x] `diagnostics/` island: `RemoteDiagnostics` interface + `RemoteDiagnosticsImpl`
- [x] `app_log` message type: structured log events with `ts`, `level`, `tag`, `msg`
- [x] `app_telemetry` message type: periodic (5s) snapshot of video/audio/session/cluster stats
- [x] Rate limiter: max 20 log messages/second (ring buffer, newest wins)
- [x] DataStore preference: remote diagnostics enabled (default: off)
- [x] DataStore preference: minimum log level to send (default: INFO)
- [x] Settings UI: toggle + log level selector in diagnostics tab
- [x] Instrument key subsystems with diagnostic log points:
  - `video`: codec selection, codec reset, first frame timing, decode errors
  - `audio`: AudioTrack creation, underruns, purpose routing, focus changes
  - `cluster`: bind/unbind events, GM kill detection, rebind attempts
  - `vhal`: property availability per-property, subscription errors
  - `update`: self-update permission check result, install session outcome
  - `input`: key event interception success/failure (voice button, media keys)
  - `transport`: connection timing, reconnect events, channel failures
  - `system`: Android version, SoC, display metrics (sent once on connect)
- [x] Add `app_log` and `app_telemetry` to `ControlMessageSerializer` (app→bridge direction)

**Bridge Side:**
- [x] Handle `app_log` in `on_app_json_line()`: write to stderr with `[CAR]` prefix + level + tag
- [x] Handle `app_telemetry` in `on_app_json_line()`: write to stderr with `[CAR] TELEM` prefix
- [x] Both types are fire-and-forget — no response, no parsing beyond prefix formatting

**Protocol:** See [docs/protocol.md](protocol.md) → Remote Diagnostics Channel section.

---

## 🚗 Car Testing Unknowns (GM AAOS)

These items can only be validated on the actual GM head unit. No emulator can answer them. Remote diagnostics (M11) is the primary tool for investigating each one — the app logs relevant events, and we observe via SSH on the bridge.

### Self-Update (M6b)
| Unknown | How to test | What to log |
|---------|------------|-------------|
| Can AAOS install APKs from non-system sources? | Trigger self-update on car, observe PackageInstaller result | `tag=update`: permission check, session create, install result, error code if rejected |
| Does GM restrict `REQUEST_INSTALL_PACKAGES`? | Check at runtime, log the permission state | `tag=update`: `checkSelfPermission` result, any GM-specific denial |
| Fallback plan if blocked | Sideload via USB OTG with file manager, or pre-signed system image | Log the specific error code so we can research GM's restriction |

### Cluster Service (M8)
| Unknown | How to test | What to log |
|---------|------------|-------------|
| Does GM kill third-party cluster services? | Deploy app, bind cluster, monitor lifetime | `tag=cluster`: bind time, alive duration, destroy event, reason if available |
| How long does it stay alive before kill? | Timestamp bind vs destroy, compute delta | `tag=cluster`: `ClusterMainSession created`, `destroyed after Nms` |
| Does GM's Templates Host work with our `NavigationTemplate`? | Send nav state, check cluster display | `tag=cluster`: `Trip.Builder` success/failure, any `RemoteException` |
| `ClusterIconShimProvider` — does GM query it? | Deploy, log `ContentProvider.query()` calls | `tag=cluster`: query events with URI and caller package |
| Is re-binding after kill effective? | Track rebind count over a drive session | `tag=cluster`: `rebind attempt #N`, success/failure |
| Fallback if cluster is fully blocked | No cluster display — degrade gracefully, hide cluster settings | Log final determination so we can document for users |

### Steering Wheel Controls (M9)
| Unknown | How to test | What to log |
|---------|------------|-------------|
| `KEYCODE_VOICE_ASSIST` interception feasibility | Register `KeyEvent` handler, attempt intercept | `tag=input`: keycode received, intercepted=true/false, forwarded to AA |
| Does GM route voice button to system assistant? | Press voice button, log what the app sees | `tag=input`: whether our app receives the event at all, or if system consumes it |
| Accessibility service approach viable? | If `KeyEvent` interception fails, try `AccessibilityService` | `tag=input`: accessibility service bind result, events received |
| Media button reliability | Press all steering wheel buttons, log each | `tag=input`: per-keycode: received, forwarded, acknowledged by bridge |

### Video/Audio on Real Hardware
| Unknown | How to test | What to log |
|---------|------------|-------------|
| Actual FPS sustained on car | Run projection, observe telemetry | `app_telemetry`: fps field (rolling average), dropped frame count |
| H.265 vs H.264 performance | Toggle codec in settings, compare fps/drops | `tag=video`: codec selected, first frame timing, steady-state fps |
| Audio latency perception | Drive with nav + media, subjective evaluation | `tag=audio`: first audio frame timing, underrun counts |
| First-frame time (cold start) | Kill app, relaunch, measure connection→first render | `tag=video`: timestamps at connect, codec config, first IDR, first render |
| Reconnect quality (car off/on) | Turn car off, back on, observe reconnect | `tag=transport`: disconnect detected, reconnect attempts, success time; `tag=video`: first IDR after reconnect |

### VHAL Properties
| Unknown | How to test | What to log |
|---------|------------|-------------|
| Which of the 37 properties are available on GM? | Subscribe all, log success/failure per property | `tag=vhal`: per-property availability result, any exceptions |
| Property value format/range on GM | Log actual values from car sensors | `tag=vhal`: sample values for speed, gear, turn signals, battery |
| Does GM restrict `CarPropertyManager` for third-party apps? | Attempt subscription, log any security exceptions | `tag=vhal`: `SecurityException` or `IllegalArgumentException` per property |

### Network
| Unknown | How to test | What to log |
|---------|------------|-------------|
| USB Ethernet speed (100Mbps or gigabit?) | Log link speed if available via `ConnectivityManager` | `tag=system`: link speed, interface name |
| Is 192.168.222.108 always assigned? | Check assigned IP on car | `tag=system`: IP address on USB NIC |
| Any firewall/iptables rules blocking our ports? | Connection success/failure timing | `tag=transport`: per-port connect latency, timeout vs refused vs success |

### Testing Workflow
1. Build APK with remote diagnostics enabled by default for car testing builds
2. Install on car (sideload or self-update if it works)
3. SSH to bridge: `journalctl -u openautolink.service -f | grep '\[CAR\]'`
4. Use the car normally — diagnostics stream in real time
5. After session: `journalctl -u openautolink.service --since "1 hour ago" | grep '\[CAR\]' > car-session.log`
6. Review logs, update this section with findings, check off unknowns as resolved

---

## 🛠️ Dev Tooling & CI/CD

### CI/CD (GitHub Actions)
- [x] **Release APK workflow** (`.github/workflows/release-apk.yml`) — triggers on GitHub Release, builds signed APK, attaches to release, updates `update.json` on gh-pages
- [x] **Release Bridge workflow** (`.github/workflows/release-bridge.yml`) — triggers on GitHub Release, cross-compiles ARM64 binary via QEMU Docker, attaches to release
- [x] **GitHub Pages** — serves `update.json` for app self-update at `https://mossyhub.github.io/openautolink/update.json`
- [x] **Branch protection** — PRs to main require 1 approving review (admin exempt)

### SBC Deployment
- [x] **User installer** (`bridge/sbc/install.sh`) — one-command setup: downloads binary + scripts from GitHub, installs services
- [x] **User setup guide** (`bridge/sbc/BUILD.md`) — flash OS → SSH → curl installer → configure → reboot
- [x] **Unified networking** (`bridge/sbc/setup-network.sh`) — single script for car NIC + SSH NIC + USB gadget

### WSL Cross-Compile (Private Dev)
- [x] **Setup** (`scripts/setup-wsl-cross-compile.sh`) — one-time ARM64 toolchain install in WSL
- [x] **Build** (`scripts/build-bridge-wsl.sh`) — rsyncs to WSL native fs, cross-compiles, copies binary back
- [x] **Deploy** (`scripts/deploy-bridge.ps1`) — build + SCP + restart service on SBC, single command
- [x] **Instructions** (`.github/instructions/bridge-dev-workflow.instructions.md`)

### Mock Bridge (Local Testing)
- [x] **Mock bridge** (`scripts/mock_bridge.py`) — Python OAL mock, ffmpeg test pattern video + sine audio
- [x] **Launcher** (`scripts/start-mock-bridge.ps1`) — PowerShell wrapper with resolution/fps/audio args
- [x] **Audio header fix** — fixed struct packing bug (signed vs unsigned channels byte), removed broken `build_audio_header` duplicate
- [x] **ffmpeg pre-warming** — pre-buffers initial H.264 output (SPS/PPS + first IDR) before accepting connections, eliminating startup reconnect loops. Also added `-pix_fmt yuv420p` for baseline profile compatibility
- [x] **Media metadata simulation** — cycles through 5 fake tracks with position updates every 5s
- [x] **Nav state simulation** — cycles through 5 maneuvers (turn_right, turn_left, straight, destination) every 10s
- [x] **Bridge stats simulation** — sends stats messages every 30s
- [x] `--no-simulate` flag to disable media/nav/stats simulation
- [x] **Phone battery simulation** — cycles battery level 85→5% every 30s, critical flag at ≤15%
- [x] **Voice session simulation** — start/end pair every 60s (5s burst)
- [x] **Album art** — base64-encoded 64×64 colored PNGs, one per track
- [x] **Nav images** — base64-encoded 48×48 colored PNGs per maneuver type
- [x] Validated with AAOS emulator end-to-end: video rendering, audio playback, session lifecycle, cluster relay, reconnection all confirmed working

### Emulator Testing Setup
- [x] **Two-AVD approach** documented in `docs/testing.md`:
  - **BlazerEV_AAOS** (Google APIs, no Play Store): `adb root`, VHAL injection, primary testing
  - **DD_AAOS_33** (Distant Display + Play Store): visual cluster rendering on secondary display
- [x] Both AVDs configured at 2400×960 @ 160dpi to match the real GM Blazer EV
- [x] **DD_AAOS_33 display fix**: secondary displays resized (400×240 + 800×240) to prevent oversized emulator window
- [x] **VHAL permissions** added to AndroidManifest.xml: `CAR_SPEED`, `CAR_ENERGY`, `CAR_POWERTRAIN`, `CAR_EXTERIOR_ENVIRONMENT`, `CAR_INFO` — only properties confirmed available on 2024 Blazer EV
- [x] **VHAL injection commands** documented for Blazer EV values: speed, gear, EV battery, temp, range, night mode, parking brake

### JVM Integration Tests
- [x] **MockOalBridgeServer** (`app/src/test/.../transport/MockOalBridgeServer.kt`) — in-process TCP server on ephemeral ports, speaks OAL protocol
- [x] **TransportIntegrationTest** (`app/src/test/.../transport/TransportIntegrationTest.kt`) — 20 tests covering:
  - Control channel: hello, phone_connected/disconnected, audio_start/stop, nav_state, media_metadata, error, touch, keyframe_request, full handshake sequence
  - Video channel: codec config, IDR keyframe, config→IDR→P-frame sequence, large (100KB) payload
  - Audio channel: media playback, all 5 purpose types, navigation mono 16kHz
  - **Mic send**: assistant mic (16kHz mono), phone call mic (8kHz mono) — app→bridge direction=1
  - **Reconnect**: ConnectionManager exponential backoff reconnection after control channel drop
- [x] `testOptions.unitTests.isReturnDefaultValues = true` in build.gradle.kts (allows android.util.Log in JVM tests)
- [x] `MockOalBridgeServer.startOnPorts()` — fixed-port restart for reconnect testing
- [x] `MockOalBridgeServer` mic frame reading — parses app→bridge audio frames (direction=1)

### CI / Automated Testing
- [x] **CI workflow** (`.github/workflows/ci.yml`) — runs on every PR and push to main
  - **unit-tests job**: JDK 21, Android SDK, `./gradlew :app:testDebugUnitTest`, uploads test reports
  - **emulator-smoke-test job** (main only): boots AAOS emulator, installs APK, starts mock bridge with `adb reverse`, verifies app process is alive
  - **bridge-build job**: C++ stub build verification on x86 Linux (no aasdk/ARM64 required)
- [x] **Test result badge** in README — links to CI workflow status

### C++ Bridge Mock Mode
- [x] **OalMockSession** (`bridge/.../include/openautolink/oal_mock_session.hpp`) — header-only mock session
  - Generates synthetic H.264 SPS/PPS + IDR/P-frames at configured FPS
  - Generates PCM sine wave audio at 48kHz stereo, 20ms chunks, pitch cycling
  - Simulates phone_connected, media metadata cycling, nav state cycling, phone battery drain, voice session bursts on control channel
- [x] **SessionMode::OalMock** added to session.hpp, parsed as `--session-mode=oal-mock`
- [x] **main.cpp integration** — `--session-mode=oal-mock --tcp-car-port=5288` launches full OAL mock
- [x] **Keyframe request handling** — re-sends SPS/PPS + IDR on `keyframe_request` from app
- [x] **Mic echo mode** — `set_mic_echo(true)` loops received mic audio back as media playback for testing

---

## 🧭 Development Workflow

### One Milestone Per Conversation
Each milestone should be completed in a **single Copilot conversation**. When a milestone's exit criteria are met, **stop and tell the user to start a new conversation** for the next milestone. This keeps context focused and avoids degraded output from overly long conversations.

### How to Start Each Conversation
Open a new Copilot chat and say:
> "Let's build M[N]: [milestone name]. Start with [first task]."

Copilot will read the instruction files, repo memory, and this work plan automatically — no need to re-explain the project.

### Within a Milestone
- Prompt by island or logical task (e.g., "Build the Transport island", "Add unit tests for JSON parsing")
- Let Copilot finish each piece, verify no compile errors, then move to the next
- Copilot should check off `[ ]` items in this plan as they're completed

### Milestone Boundaries
- **Do not start the next milestone in the same conversation** — context quality degrades
- Between milestones: build, deploy to device/emulator, test manually, note any issues
- Start the next conversation with any issues or adjustments discovered during testing

### Parallel Work
Parallel Copilot sessions are **not recommended** for this project:
- Sessions don't communicate or coordinate file writes
- Build state isn't shared — one session can't see another's compile errors
- Island architecture helps in theory, but merge conflicts aren't worth the risk
- Sequential milestones have hard dependencies (M2 needs M1's transport, M3 needs M1's transport, M4 needs M2's surface, etc.)

### If a Conversation Gets Too Long
If Copilot starts losing context or producing lower quality output mid-milestone, it's fine to start a new conversation and say:
> "Continuing M[N]. [Island X] is done, [Island Y] still needs [specific tasks]."

---

## � Proto Capabilities — Untapped aasdk Data

aasdk v1.6 defines ~260 protobuf messages across 12 service channels. The bridge/app currently use a subset. This plan covers forwarding additional data that the phone's AA session already sends or can consume but that we currently ignore.

**Excludes:** NavigationState (separate work plan, M10b).

### P1: Phone Battery + Voice Session (Bridge One-Liners)

**Effort:** Tiny. Bridge already receives both messages — just logs and drops them.

**Bridge Side:**
- [x] `onBatteryStatusNotification()`: extract `battery_level` (0-100), `time_remaining_s`, `critical_battery` → forward as OAL `phone_battery` JSON control message
- [x] `onVoiceSessionRequest()`: extract `VoiceSessionStatus` (START/END) → forward as OAL `voice_session` JSON control message
- [x] Add both new message types to mock bridge (`OalMockSession`) for testing

**App Side:**
- [x] Add `PhoneBattery` and `VoiceSession` to `ControlMessage` sealed class
- [x] Parse both in `ControlMessageSerializer`
- [x] `SessionManager`: handle `phone_battery` → update `SessionState` with phone battery level
- [x] `SessionManager`: handle `voice_session` → update `SessionState` with assistant active flag
- [x] `ProjectionViewModel`: expose phone battery + voice session state to UI
- [ ] Status bar indicator: phone battery icon (optional, small)
- [ ] Voice session indicator: visual cue when Google Assistant is listening on the phone (e.g., mic icon pulse, subtle overlay tint)
- [ ] Consider: duck or mute touch forwarding during active voice session (phone is listening, touches may interfere)

**OAL Protocol additions:**
```
Bridge → App:
{"type":"phone_battery","level":85,"time_remaining_s":14400,"critical":false}
{"type":"voice_session","status":"start"}
{"type":"voice_session","status":"end"}
```

**Mock bridge:** Add `phone_battery` to periodic simulation (random 20-100%, cycling). Add `voice_session` start/end pair every ~60s.

### P2: AA Theme Sync + Session Flags

**Effort:** Small. Bridge needs to send one new protobuf message to the phone and tweak existing ServiceDiscoveryResponse flags.

**Bridge Side — Theme Sync:**
- [x] When car app sends `vehicle_data` with `night_mode` change, send `UpdateUiConfigRequest` to phone via control channel with `UiTheme::UI_THEME_DARK` or `UI_THEME_LIGHT`
- [x] Track `last_night_mode_sent_` to avoid redundant sends (only send on change)
- [x] Include `#include <aap_protobuf/service/media/shared/message/UiConfig.pb.h>` and `UiTheme.pb.h`
- [x] Add `sendUiConfigUpdate()` method to `HeadlessAutoEntity` or control channel wrapper

**Bridge Side — Session Flags:**
- [x] Make `hide_clock` configurable via env var `OAL_AA_HIDE_CLOCK` (default: `true` — AAOS has its own clock, no need for duplicate)
- [x] Make `hide_phone_signal` configurable via env var `OAL_AA_HIDE_PHONE_SIGNAL` (default: `false`)
- [x] Make `hide_battery_level` configurable via env var `OAL_AA_HIDE_BATTERY` (default: `false`)
- [x] Set in ServiceDiscoveryResponse `set_hide_clock()` / session config flags from env

**App Side:**
- [x] Send night mode changes immediately (currently batched in 500ms vehicle_data — acceptable, night mode changes are infrequent)
- [x] Settings toggle: "Sync AA theme with car" (default: on)
- [x] Settings toggle: "Hide AA clock" (default: on) — sent to bridge via `config_update`
- [ ] Bridge reads config_update for clock/signal/battery hide preferences, applies on next phone connection

### P3: GNSS → Phone GPS Feed

**Effort:** Medium. The bridge receives `gnss` NMEA from the app. The `sendGpsLocation()` method on the sensor handler is fully implemented. NMEA parsing ($GPRMC + $GPGGA) is now complete with coordinate conversion, fix validation, and altitude tracking.

**Bridge Side:**
- [x] In `LiveAasdkSession::on_gnss()`: parse NMEA `$GPRMC` sentence for lat, lon, speed, bearing, date/time
- [x] In `LiveAasdkSession::on_gnss()`: parse NMEA `$GPGGA` sentence for altitude, fix quality, satellite count
- [x] Call `sensor_handler_->sendGpsLocation(lat, lon, alt, speed, bearing, timestamp_ms)` after successful parse
- [x] Handle coordinate format conversion: NMEA `ddmm.mmmm` → decimal degrees
- [x] Validate: only forward if fix quality > 0 (skip invalid/no-fix sentences)
- [x] Log first successful GPS forward for diagnostics

**Why this matters:** Currently the phone uses only its own GPS. The car's GPS receiver (via AAOS LocationManager) can be more accurate in urban canyons, tunnels (with dead reckoning), and areas with poor phone signal. Feeding car GPS to the phone improves AA navigation accuracy.

**No app changes needed** — the app already sends GNSS data. Only the bridge's parser is missing.

### P4: Phone Status Service (Signal + Call State)

**Effort:** Medium-high. Requires aasdk fork change — the `PhoneStatusService::messageHandler()` in aasdk currently receives `PHONE_STATUS` messages but drops them in the `default:` case without parsing.

**aasdk Fork (`external/opencardev-aasdk/`):**
- [x] `IPhoneStatusServiceEventHandler`: add `virtual void onPhoneStatusUpdate(const aap_protobuf::service::phonestatus::message::PhoneStatus& status) = 0;`
- [x] `PhoneStatusService::messageHandler()`: add case for `PhoneStatusMessageId::PHONE_STATUS` → parse payload, call `eventHandler->onPhoneStatusUpdate()`
- [ ] Commit inside submodule, push to `openautolink` branch

**Bridge Side:**
- [x] Add PhoneStatus channel to ServiceDiscoveryResponse: `svc->set_id(ChannelId::PHONE_STATUS); svc->mutable_phone_status_service();`
- [x] Create `HeadlessPhoneStatusHandler` (same pattern as `HeadlessMediaStatusHandler`): implements `IPhoneStatusServiceEventHandler`, opens channel, receives messages
- [x] `onPhoneStatusUpdate()`: extract `signal_strength`, `calls[]` (state, duration, caller_number, caller_id) → forward as OAL `phone_status` JSON
- [x] Start handler after ServiceDiscoveryResponse sent (alongside other handlers)

**App Side:**
- [x] Add `PhoneStatus` to `ControlMessage` sealed class: `signalStrength: Int?, calls: List<PhoneCall>?`
- [x] `PhoneCall` data class: `state: String, durationSeconds: Int, callerNumber: String?, callerId: String?`
- [x] Parse in `ControlMessageSerializer`
- [x] `SessionManager`: handle `phone_status` → update `SessionState`
- [ ] UI: phone signal bars in status area (0-4 bars)
- [ ] UI: incoming call notification overlay (caller name/number)

**OAL Protocol:**
```
{"type":"phone_status","signal_strength":3,"calls":[{"state":"incoming","duration_s":0,"caller_number":"+15550123","caller_id":"Mom"}]}
{"type":"phone_status","signal_strength":4,"calls":[]}
```

### P5: IMU Sensors → Phone (Accel, Gyro, Compass)

**Effort:** Medium. Bridge sensor handler already has the pattern for all sensor types. Main work is on the app side (reading AAOS `SensorManager`) and adding the sensor types to the SDR.

**Sensors to add:**

| Sensor | Proto | Source in App | GM Available? |
|--------|-------|---------------|---------------|
| Accelerometer | `SENSOR_ACCELEROMETER_DATA` | `SensorManager.TYPE_ACCELEROMETER` | Yes (standard Android) |
| Gyroscope | `SENSOR_GYROSCOPE_DATA` | `SensorManager.TYPE_GYROSCOPE` | Yes (standard Android) |
| Compass | `SENSOR_COMPASS` | `SensorManager.TYPE_MAGNETIC_FIELD` → compute bearing | Yes (standard Android) |
| Dead Reckoning | `SENSOR_DEAD_RECKONING_DATA` | `PERF_STEERING_ANGLE` + wheel speed | **No** — steering angle denied on GM |
| GPS Satellites | `SENSOR_GPS_SATELLITE_DATA` | `GnssStatus` callback | Yes (standard Android) |

**Bridge Side:**
- [x] Add `SENSOR_ACCELEROMETER_DATA`, `SENSOR_GYROSCOPE_DATA`, `SENSOR_COMPASS`, `SENSOR_GPS_SATELLITE_DATA` to ServiceDiscoveryResponse sensor list
- [x] Add `sendAccelerometer(int x_e3, int y_e3, int z_e3)` to sensor handler
- [x] Add `sendGyroscope(int rx_e3, int ry_e3, int rz_e3)` to sensor handler
- [x] Add `sendCompass(int bearing_e6, int pitch_e6, int roll_e6)` to sensor handler
- [x] Add `sendGpsSatellites(int in_use, int in_view, vector<SatInfo>)` to sensor handler
- [x] Parse new fields from `vehicle_data` JSON in `on_vehicle_data()`

**App Side:**
- [x] New `ImuForwarder` in `input/` island: registers `SensorEventListener` for `TYPE_ACCELEROMETER`, `TYPE_GYROSCOPE`, `TYPE_MAGNETIC_FIELD`
- [x] Rate-limit IMU samples: ~10 Hz max (AA doesn't need 100 Hz inertial data, and control channel bandwidth matters)
- [x] Convert magnetic field to compass bearing (requires `SensorManager.getRotationMatrix()` + `getOrientation()`)
- [x] Add IMU fields to `vehicle_data` message: `accel_x_e3`, `accel_y_e3`, `accel_z_e3`, `gyro_rx_e3`, `gyro_ry_e3`, `gyro_rz_e3`, `compass_bearing_e6`
- [x] New `GnssSatelliteForwarder` in `input/` island: registers `GnssStatus.Callback` via `LocationManager`
- [x] Add satellite data to `vehicle_data` message or as separate `gnss_satellites` message: `sat_in_use`, `sat_in_view`
- [ ] Settings toggle: "Send IMU sensors to phone" (default: on) — some users may want to save bandwidth
- [x] Graceful degradation: if `SensorManager` returns null for a sensor type, skip it

**Skip `SENSOR_DEAD_RECKONING_DATA`** — requires steering angle (`READ_CAR_STEERING_3P` denied on GM) and per-wheel speed (not exposed via VHAL).

**Why this matters:** Accelerometer + gyroscope + compass form the inertial measurement unit that AA uses for dead reckoning navigation. When the car enters a tunnel or parking garage and GPS is lost, these sensors keep the blue dot moving accurately on the map. Without them, the map freezes until GPS returns.

### P6: Additional Sensor Types (RPM, CallAvailability)

**Effort:** Small. Incremental additions to the existing sensor and control infrastructure.

**RPM (`SENSOR_RPM`):**
- [x] Bridge: add `SENSOR_RPM` to SDR sensor list
- [x] Bridge: add `sendRpm(int rpm_e3)` to sensor handler
- [x] Bridge: parse `rpm_e3` from `vehicle_data` JSON in `on_vehicle_data()`
- [x] App: read VHAL `PERF_ENGINE_RPM` (property 291504901) — likely unavailable on EV (no engine), but include for ICE vehicles
- [x] App: add `rpm_e3` field to `vehicle_data` message (null if unavailable)

**CallAvailability:**
- [x] Bridge: send `CallAvailabilityStatus { call_available = true }` after BT HFP is established
- [x] This tells the phone's AA that the head unit supports in-car calling — may enable the call button in AA UI
- [ ] Send `call_available = false` if HFP drops

**GPS Satellites (moved from P5 if preferred):**
- [ ] Could be done here as a smaller scope item if P5's IMU is too large

### P7: Radio Service (Phone Controls Car Radio)

**Effort:** Large. Requires aasdk fork changes, new bridge handler, new OAL messages, and new app island for AAOS `BroadcastRadio` integration.

**What this enables:** The phone's AA interface shows a radio UI where the user can tune FM/AM stations, seek, scan, and see station info — all controlling the car's actual radio hardware through the AAOS `BroadcastRadio` HAL.

**Architecture:**
```
Phone AA Radio UI → aasdk RadioService channel → Bridge
    → OAL control messages (radio_*) → App
    → AAOS BroadcastRadio API → Car's radio hardware
    → Station info back → App → Bridge → Phone
```

**aasdk Fork (`external/opencardev-aasdk/`):**
- [ ] `IRadioServiceEventHandler`: add virtual methods for each radio message:
  - `onSelectActiveRadioRequest(const SelectActiveRadioRequest&)`
  - `onTuneToStationRequest(const TuneToStationRequest&)`
  - `onSeekStationRequest(const SeekStationRequest&)`
  - `onScanStationsRequest(const ScanStationsRequest&)`
  - `onStepChannelRequest(const StepChannelRequest&)`
  - `onMuteRadioRequest(const MuteRadioRequest&)`
  - `onCancelOperationsRequest(const CancelOperationsRequest&)`
  - `onConfigureChannelSpacingRequest(const ConfigureChannelSpacingRequest&)`
  - `onGetProgramListRequest(const GetProgramListRequest&)`
  - `onGetTrafficUpdateRequest(const GetTrafficUpdateRequest&)`
  - `onRadioSourceRequest(const RadioSourceRequest&)`
- [ ] `RadioService::messageHandler()`: parse each `RadioMessageId` case, call corresponding event handler method
- [ ] Add send methods for responses/notifications:
  - `sendTuneToStationResponse()`, `sendSeekStationResponse()`, `sendScanStationsResponse()`
  - `sendStepChannelResponse()`, `sendMuteRadioResponse()`, `sendCancelOperationsResponse()`
  - `sendActiveRadioNotification()`, `sendRadioStationInfoNotification()`
  - `sendStationPresetsNotification()`, `sendRadioStateNotification()`
  - `sendGetProgramListResponse()`, `sendGetTrafficUpdateResponse()`
  - `sendRadioSourceResponse()`, `sendConfigureChannelSpacingResponse()`
- [ ] Commit inside submodule, push to `openautolink` branch

**Bridge Side:**
- [ ] Add RadioService to ServiceDiscoveryResponse with FM properties:
  ```cpp
  svc->set_id(ChannelId::RADIO);
  auto* rs = svc->mutable_radio_service();
  auto* prop = rs->add_radio_properties();
  prop->set_radio_type(RADIO_FM);
  prop->set_supports_rds(true);
  auto* range = prop->mutable_range();
  range->set_min(87500);  // 87.5 MHz in kHz
  range->set_max(108000); // 108.0 MHz in kHz
  range->set_step(200);   // 200 kHz steps (US)
  ```
- [ ] Create `HeadlessRadioHandler`: implements `IRadioServiceEventHandler`
- [ ] Forward phone radio commands → OAL control messages:
  - `radio_tune` → `{"type":"radio_tune","frequency_khz":101100}`
  - `radio_seek` → `{"type":"radio_seek","direction":"up"}`
  - `radio_scan` → `{"type":"radio_scan"}`
  - `radio_step` → `{"type":"radio_step","direction":"up"}`
  - `radio_mute` → `{"type":"radio_mute","muted":true}`
  - `radio_select` → `{"type":"radio_select","radio_type":"FM"}`
- [ ] Handle responses from app → send back to phone via aasdk:
  - `radio_station_info` → `sendRadioStationInfoNotification()`
  - `radio_state` → `sendRadioStateNotification()`
  - `radio_presets` → `sendStationPresetsNotification()`
- [ ] Start handler after ServiceDiscoveryResponse sent
- [ ] Make radio configurable: `OAL_AA_RADIO_ENABLED` env var (default: `false` until tested)

**App Side:**
- [ ] New `radio/` island: `RadioController` interface + `RadioControllerImpl`
- [ ] AAOS `BroadcastRadio` integration:
  - `RadioManager.openTuner()` — obtain radio tuner
  - Handle `TuneToStation`: `tuner.tune(ProgramSelector.createAmFmSelector(freq))`
  - Handle `SeekStation`: `tuner.seek(direction)`
  - Handle `ScanStation`: `tuner.scan(direction)`
  - Handle `StepChannel`: `tuner.step(direction)`
  - Handle `MuteRadio`: system audio mute for radio
- [ ] `RadioManager.Callback` for station info updates → send back to bridge:
  - `onCurrentProgramInfoChanged()` → `radio_station_info` with frequency, station name, RDS data
  - `onProgramListChanged()` → `radio_presets` with station list
- [ ] Parse radio OAL control messages in `ControlMessageSerializer`
- [ ] Add `RadioTune`, `RadioSeek`, `RadioScan`, `RadioStep`, `RadioMute`, `RadioSelect` to `ControlMessage`
- [ ] Add `RadioStationInfo`, `RadioState`, `RadioPresets` to `ControlMessage` (app→bridge responses)
- [ ] `SessionManager`: route radio messages to `RadioController`
- [ ] `RadioController`: send station info/state updates back via `ConnectionManager.sendControlMessage()`
- [ ] Permissions: `android.permission.ACCESS_BROADCAST_RADIO` in manifest
- [ ] Graceful degradation: if `RadioManager` is unavailable (some AAOS builds), log and don't advertise radio (tell bridge via hello capabilities)
- [ ] Settings: "Enable AA Radio Control" toggle (default: off until validated on car)

**Car Testing Unknowns:**
| Unknown | How to test | What to log |
|---------|------------|-------------|
| Is `RadioManager` accessible to third-party apps on GM? | Attempt `getSystemService(RADIO_SERVICE)`, log result | `tag=radio`: service available, tuner opened, or SecurityException |
| FM frequency range on GM (US = 87.5-108 MHz) | Read RadioManager properties | `tag=radio`: supported bands, frequency range, spacing |
| Does tuning work without audio routing? | Tune to known station, check if audio plays | `tag=radio`: tune result, program info callback received |
| RDS data availability | Tune to RDS-capable station, log metadata | `tag=radio`: RDS program name, PI code, PTY |

---

### Proto Capability Dependency Graph

```
P1 (Battery + Voice)  ← no deps, bridge-only changes + app UI
P2 (Theme + Flags)    ← needs P1's night_mode flow verified
P3 (GNSS → Phone)     ← no deps, bridge-only NMEA parser
P4 (Phone Status)     ← needs aasdk fork change
P5 (IMU Sensors)      ← no deps, bridge sensor handler + app SensorManager
P6 (RPM + CallAvail)  ← no deps, incremental
P7 (Radio)            ← needs aasdk fork change + AAOS BroadcastRadio
```

**Recommended order:** P1 → P3 → P2 → P5 → P6 → P4 → P7

P1 and P3 are the quickest wins (bridge-only or near bridge-only). P4 and P7 require aasdk fork changes and should be batched together to minimize submodule churn.

---

## �💡 Future Ideas

### Two-Way Config Sync
- Bridge sends config echo after settings update
- App populates settings dialog from bridge echo, showing actual running config

### Stats Overlay Enhancements
- Parse SPS/PPS for actual stream resolution (not just codec init dims)
- Bridge-side stats (frames queued/dropped/written) sent via control channel
- Audio: PCM frame count, ring buffer fill level

### mDNS Discovery
- Bridge advertises `_openautolink._tcp` via Avahi
- App discovers automatically — no manual IP entry needed
- Fallback to manual IP for networks without mDNS

---

## Car Hardware Reference (2024 Chevrolet Blazer EV)
- **SoC:** Qualcomm Snapdragon
- **Display:** 2914×1134 physical, ~2628×800 usable (nav bar hidden), 2400×960 @ 160dpi as reported by the OS
- **Screens:** 3 displays — main infotainment (17.7" touch), instrument cluster (digital gauges), HUD (windshield)
- **HW Decoders:** H.264 (`c2.qti.avc.decoder`), H.265, VP9 — all 8K@480fps max
- **Network:** USB Ethernet NIC (car USB port), 100Mbps (validate — might be gigabit). Always assigned 192.168.222.108 by GM's AAOS

### VHAL Properties Available on Blazer EV
Confirmed from live car data capture:

| Property | Status | Permission |
|----------|--------|------------|
| Vehicle speed | Available | `CAR_SPEED` |
| Gear selection | Available | `CAR_POWERTRAIN` |
| Parking brake | Available | `CAR_POWERTRAIN` |
| Night mode | Available | `CAR_EXTERIOR_ENVIRONMENT` |
| EV battery level (41550 Wh) | Available | `CAR_ENERGY` |
| EV battery capacity (83010 Wh) | Available | `CAR_INFO` |
| EV charge rate | Available | `CAR_ENERGY` |
| Range remaining (214984 m) | Available | `CAR_ENERGY` |
| Outside temperature (13°C) | Available | `CAR_EXTERIOR_ENVIRONMENT` |
| Ignition state | Available | `CAR_POWERTRAIN` |
| Charge port open/connected | Available | `CAR_ENERGY` |
| Make/Model/Year | Available | `CAR_INFO` |
| Steering angle | **Denied** | `READ_CAR_STEERING_3P` |
| Odometer | **Not exposed** | — |
| Headlights/turn signals | **Denied** | `READ_CAR_EXTERIOR_LIGHTS` |
| Door locks | **Denied** | `CONTROL_CAR_DOORS` |
| HVAC | **Denied** | `CONTROL_CAR_CLIMATE` |
| Tire pressure | **Denied** | `CAR_TIRES_3P` |
