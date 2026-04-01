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
- [ ] Cluster `CarAppService` + `ClusterMainSession` (GM path: `NavigationManager.updateTrip()` relay)
- [ ] `CarlinkClusterSession` fallback (standard AAOS path: direct `NavigationTemplate` rendering)
- [ ] Cluster navigation: `Maneuver.TYPE_*` enums + distance + road name via `Trip` builder
- [ ] `MediaBrowserService` + `MediaSession` for cluster media: album artwork, track info
- [ ] Bridge → app: `album_art_base64` field in `media_metadata` control message
- [ ] `ClusterIconShimProvider` for Templates Host icon caching (GM-specific workaround)
- [ ] Handle GM restrictions (third-party cluster services may be killed — detect and recover)
- [ ] Fallback rendering if cluster service is blocked
- [ ] `ClusterBindingState` tracking + auto-relaunch after teardown

**Deferred — M8b: Full-Featured Nav Icons**
- [ ] Bridge: configure aasdk `NavigationStatusService` with `InstrumentClusterType::IMAGE` (currently uses `ENUM`) + set `image_options` (width, height, colour_depth_bits)
- [ ] Bridge: handle `NavigationState` proto (newer API) in addition to legacy `NavigationNextTurnEvent`/`NavigationNextTurnDistanceEvent` — the `NavigationState` proto carries `NavigationManeuver` with richer type enum (42 types vs legacy's 20)
- [ ] Bridge: forward maneuver icon PNG bytes from aasdk to app via new `nav_image_base64` field in `nav_state` control message (or dedicated binary message on control channel)
- [ ] App: when `nav_image_base64` present, use `Maneuver.TYPE_UNKNOWN` + `CarIcon.Builder(IconCompat.createWithBitmap(...))` — AA icon is source of truth (pre-rendered by Google Maps with exact angle/lane data)
- [ ] App: fall back to `Maneuver.TYPE_*` enum mapping when no image available (CarPlay path, or older bridge)
- [ ] Consider bundling VectorDrawable icon set (54 drawables, `cp_maneuver_00` through `cp_maneuver_53`) as CarPlay/offline fallback — reference: `carlink_native` `ManeuverIconRenderer.kt`
- [ ] Reference commits: `carlink_native` `7e20b7a` (AA Nav Icon Use), `9ccd3ff` (AA Cluster Test), `1a17840` (AA Album Art Fix)

### M9: Steering Wheel Controls
- [x] Media button mapping: skip forward, skip back, play/pause via `KeyEvent` interception
- [x] Volume controls via `AudioManager` or `KeyEvent`
- [x] Voice button interception: intercept the AAOS voice/assistant `KeyEvent` (currently launches Google Assistant) and forward as AA voice trigger to activate Gemini on the phone
- [ ] Investigate `KEYCODE_VOICE_ASSIST` / `KEYCODE_SEARCH` interception feasibility on GM AAOS (may require accessibility service or input method)

### M10: Polish
- [x] Diagnostics screen
- [x] Error recovery (reconnect, codec reset)
- [x] Display modes (fullscreen, system bars) — pulled forward, implemented with M2
- [x] Overlay buttons (settings, stats) — pulled forward, draggable floating buttons
- [x] App icon and logo — adaptive icon from brand asset
- [x] Stats for nerds overlay — monospace panel with session/video stats

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
- [ ] Validate with AAOS emulator end-to-end
- [ ] Add media metadata simulation (album art, track info) for M8 cluster testing
- [ ] Add nav state simulation (maneuvers, distance) for M7/M8 testing

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

## 💡 Future Ideas

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

## Car Hardware Reference
- **SoC:** Qualcomm Snapdragon (2024 Chevrolet Blazer EV)
- **Display:** 2914×1134 physical, ~2628×800 usable (nav bar hidden)
- **HW Decoders:** H.264 (`c2.qti.avc.decoder`), H.265, VP9 — all 8K@480fps max
- **Network:** USB Ethernet NIC (car USB port), 100Mbps (validate this as it might be gigabit). iIt is always assigned 192.168.222.108 by GM's AAOS.
