# Headunit Revived vs OpenAutoLink — Feature Comparison Report

**Date**: 2026-04-23 (updated)  
**Scope**: Features in HUR (headunit-revived v2.2.2-beta1) vs OpenAutoLink, ranked by value  
**Architecture**: OAL direct mode — app speaks AA protocol directly to the phone over WiFi/Nearby (no bridge/SBC)  
**Source**: `external/headunit-revived/` codebase analysis  
**Status**: Bridge code fully removed. Direct mode is the only mode.

---

## Executive Summary

HUR is a mature, community-driven AA receiver app targeting generic Android tablets/headunits. OpenAutoLink targets AAOS head units, now in **direct mode** (the app itself runs the AA protocol — no SBC bridge). Both apps speak the same AA wire protocol, making HUR an excellent reference implementation.

The most valuable gaps fall into three buckets:

1. **AA session/protocol features** — things we don't announce or handle in `DirectServiceDiscovery`/`DirectAaSession`
2. **Audio/mic processing** — quality enhancements to the audio pipeline
3. **Connection resilience & multi-device** — relevant now that the app owns the entire connection lifecycle

---

## Tier 1 — High Value (AA Session/Protocol)

### ~~1. VP9 Video Codec in ServiceDiscovery~~ ✅ DONE

> **Completed.** `DirectServiceDiscovery.buildVideoService()` now announces H.264, H.265, and VP9 across all resolution tiers. Auto mode offers all codecs; manual mode offers the selected codec + H.264 fallback. Settings UI has codec picker (Video tab → Video Negotiation off → Video Codec).

---

### ~~2. Multiple Resolution Tiers in ServiceDiscovery~~ ✅ DONE

> **Completed.** All 5 resolution tiers (480p, 720p, 1080p, 1440p, 4K) announced in `DirectServiceDiscovery`. Auto mode announces all tiers for all codecs. Manual mode announces the selected resolution. Settings UI: Video tab → AA Resolution picker with recommended DPI per tier.

---

### ~~3. Microphone Audio Enhancement (NoiseSuppressor + AGC + AcousticEchoCanceler)~~ ✅ DONE

> **Completed.** `MicCaptureManager.attachAudioEffects()` attaches `NoiseSuppressor`, `AutomaticGainControl`, and `AcousticEchoCanceler` to the `AudioRecord` session after `startRecording()`. Graceful fallback if effects aren't available on the device. Released in `finally` block.

---

### ~~4. AAC-LC Audio Codec Support~~ ✅ DONE

> **Completed.** `DirectServiceDiscovery.buildAudioService()` now accepts a `MediaCodecType` parameter. When `useAacAudio=true`, announces `MEDIA_CODEC_AUDIO_AAC_LC` on all 3 audio channels. `AacDecoder.kt` provides synchronous MediaCodec AAC-LC decoding with auto-generated CSD-0 (AudioSpecificConfig). `AudioPurposeSlot.feedAac()` lazily initializes the decoder on first AAC frame. Also fixed: 8-byte timestamp prefix was not being stripped from audio MEDIA_DATA payloads (was always a bug, masked in PCM mode because AudioTrack tolerates garbage prefix bytes). Requires phone AA developer setting "Audio codec" set to "auto negotiate" (not "PCM only").

---

### ~~5. Bluetooth Service in ServiceDiscovery~~ ✅ DONE

> **Completed.** `DirectServiceDiscovery.build()` now accepts `btMacAddress` and adds a `BluetoothService` with A2DP + HFP pairing methods when a valid MAC is provided. `SessionManager` reads the car's BT MAC via `BluetoothAdapter.getAddress()`. On AAOS system installs this returns the real MAC; on non-system apps (Android 12+) it returns `02:00:00:00:00:00` which is filtered out. No channel 8 data handler needed — the service announcement is sufficient for the phone to discover the car's BT and pair independently.

---

### ~~6. Phone Status Service (Signal + Calls)~~ ✅ DONE

> **Completed.** `DirectAaSession.handlePhoneStatus()` now correctly parses channel 12 data using `Control.Service.PhoneStatusService.parseFrom()` (was incorrectly using `ServiceDiscoveryResponse`). Extracts signal strength and call state (state, duration, caller number/ID), emits as `ControlMessage.PhoneStatus`. `SessionManager` updates `phoneSignalStrength` StateFlow which is already wired to the UI.

---

### NEW: USB Host Connection (AOA) — Wired Android Auto

**What HUR has**: USB Android Open Accessory (AOA) connection. When a phone is plugged into the head unit's USB-A port, HUR claims the AOA interface and starts an AA session over USB. This is the same protocol that stock AA head units use for wired connections.

**What OAL has**: Wireless-only (Nearby Connections, WiFi Direct, phone hotspot). No USB transport.

**Why port**: The 2024 Chevrolet Blazer EV has a USB-A port in the center console that is confirmed working for USB data. USB AOA provides:
- **Zero-latency connection** — no WiFi setup, BT handshake, or Nearby discovery delay. Plug in and go
- **Maximum bandwidth** — USB 2.0 = 480 Mbps, far exceeding any WiFi link. Enables highest quality 4K video with zero bandwidth concerns
- **Lowest possible latency** — no WiFi jitter, no encryption overhead (USB AOA is unencrypted)
- **Phone charging** — phone charges while connected, solving the "wireless AA drains battery" problem
- **Reliability** — wired connections don't drop from WiFi interference, sleep state changes, or P2P group failures
- **Fallback** — when wireless methods fail (WiFi Direct unsupported, Nearby flaky), USB just works

The AA protocol is identical over USB and WiFi — same version exchange, SSL handshake, service discovery, channel open, and media streaming. The only difference is the transport layer (USB bulk transfers vs TCP).

**Implementation approach**:
1. Register a USB accessory intent filter in AndroidManifest for the AA accessory IDs
2. When a phone is plugged in, claim the AOA interface via `UsbManager`
3. Open bulk IN/OUT endpoints for bidirectional communication
4. Run the same `handleConnection()` flow as WiFi — version exchange → SSL → service discovery → read loop
5. Add "USB" as a transport option in settings (or auto-detect when phone is plugged in)

**HUR reference**: `UsbConnection.kt` — opens `UsbDeviceConnection`, finds bulk endpoints, wraps in `InputStream`/`OutputStream`. Same AA wire protocol as WiFi.

**Effort**: Medium. ~200 lines for USB transport class + manifest intent filter + auto-detection.

**Priority**: **High** — instant, reliable, zero-config wired connection. Best user experience for daily driving.

---

### 7. Rotary Controller / Touchpad Input

**What HUR has**: Optional `TOUCHPAD` announcement in `InputSourceService`. Supports rotary keycodes (268-271, 65536-65538) — scroll, click, back, long-press. Also supports `RelativeEvent` and `AbsoluteEvent` for scroll/jog input.

**What OAL has**: Touchscreen only. No rotary, no scroll wheel, no touchpad.

**Why port**: Some AAOS head units have rotary knobs or jog dials (BMW iDrive style). In AA, rotary lets you scroll lists, zoom maps, and navigate menus without touching the screen. The Blazer EV doesn't have one, but other AAOS targets might.

**Effort**: Medium. Detect `InputDevice.SOURCE_ROTARY_ENCODER` on AAOS, announce `TOUCHPAD` in `DirectServiceDiscovery.buildInputService()`, convert `MotionEvent.AXIS_SCROLL` to AA `RelativeEvent`, send on channel 3.

**Priority**: **Medium** — important for multi-platform AAOS support.

---

### ~~8. User-Configurable Key Remapping~~ ✅ DONE

> **Completed.** New **Input** tab in Settings with 14 mappable AA actions (play/pause, next/prev, stop, voice, DPAD, call/endcall). Tap an action → capture dialog opens → press any physical button → assigns it. Custom map stored as JSON in DataStore and continuously applied to `SteeringWheelController.customKeyMap`; remap changes take effect live without reconnect. Custom mappings take highest priority for each assigned key; unrelated custom keys do not disable built-in fallbacks. "Reset All Mappings" clears overrides and restores defaults. Equinox owner-confirmed Previous is F8 (138), so #143 changes its fallback to Previous (88); F7 (137) remains Next (87). F6 (136) and F9 (139) Previous assignments remain suspected. These defaults retain the existing global unmapped-key behavior, not a new OEM gate. The changed F8 fallback still needs post-change vehicle validation.

---

### ~~9. Per-Purpose Volume Offsets~~ ✅ DONE

> **Completed.** Three sliders in the **Audio** tab: Media, Navigation, Assistant/Speech. Range -100% to +100% in 10% steps (0 = default, +50 = 1.5× gain, -50 = 0.5× gain). Applied in `AudioPurposeCoordinator.computeActions()` on top of the existing ducking logic via `volumeOffsetMedia/Navigation/Assistant` fields. Stored as `intPreferencesKey` in DataStore, passed through `SessionManager.start()`. Requires Save & Restart.

---

### 10. Configurable Audio Latency (Buffer Size + Queue Capacity)

**What HUR has**: Two user-configurable audio settings:
- `audioLatencyMultiplier` — multiplied against `AudioTrack.getMinBufferSize()` (default 8×)
- `audioQueueCapacity` — bounded `LinkedBlockingQueue` with backpressure when playback falls behind

**What OAL has**: Fixed `RingBuffer` with hardcoded sizes. Per-purpose buffer sizing but no user tunability.

**Why port**: In direct mode, audio travels over WiFi (variable latency) instead of Ethernet (stable). Buffer tuning becomes more important. Different AAOS hardware also has different audio subsystem latencies. User-tunable buffers let people optimize for their specific setup.

**Effort**: Medium. Add preferences, wire into `AudioPurposeSlot` buffer sizing.

**Priority**: **Medium** — more important in direct mode than with the bridge.

---

### ~~11. Dynamic Vehicle Identity from VHAL~~ ✅ DONE

> **Completed.** `SessionManager.startSession()` reads make/model/year from `VehicleDataForwarder.latestVehicleData` and passes into `DirectServiceDiscovery.VehicleIdentity`. Drive side preference maps to `DriverPosition`. Falls back to `"OpenAutoLink"/"Direct"/"2024"` if VHAL data isn't available yet.

---

## Tier 2 — Medium Value

### 12. Auto-Optimization Wizard (Codec/Resolution Detection)

**What HUR has**: `SystemOptimizer` that:
- Checks H.265 hardware support via chipset allowlist (`qcom`, `samsung`, `google`, `mt68/69`)
- Enumerates `MediaCodecList` filtering out software decoders
- Reads `DisplayMetrics` for resolution/DPI
- Recommends optimal resolution, DPI, codec, and view mode

**What OAL has**: Manual settings for codec/resolution/DPI. No auto-detection.

**Why port**: In direct mode, the app is the only decision-maker for these settings. Auto-detection ensures the best experience on unknown AAOS hardware without user tinkering. The chipset allowlist for H.265 is especially valuable — some SoCs advertise HEVC support but can't handle high-bitrate real-time streams.

**Effort**: Medium. Port `isReliableHevcChipset()` and `checkH265HardwareSupport()` logic. Use `DisplayMetrics` for auto-resolution. Run on first launch or when settings are "auto".

**Priority**: **Medium** — reduces user friction, prevents bad codec selection.

---

### ~~13. WiFi Direct Frequency Detection (2.4 vs 5 GHz)~~ ✅ DONE

> **Completed.** `AaNearbyManager.detectWifiFrequency()` queries `WifiP2pGroup.getFrequency()` (API 30+) with reflection fallback for older OEMs, 2s after Nearby connection (allows BT→WiFi Direct transport upgrade). Exposed via `AaNearbyManager.wifiFrequencyMhz` StateFlow → `SessionManager` → `ProjectionUiState` → stats overlay. Shows band (5 GHz / 2.4 GHz) with color coding (green/yellow) and exact MHz value. Confirmed working: 5785 MHz (5 GHz) on Samsung S21 FE.

---

### 14. Automation Intents / Deep Links

**What HUR has**: External apps trigger actions via intents/deep links:
- `headunit://connect?ip=<IP>` — trigger connection
- `headunit://exit` — full app exit
- Intent actions: `CONNECT`, `DISCONNECT`, `SET_NIGHT_MODE`, `STOP_SERVICE`

**What OAL has**: No external intent support.

**Why port**: AAOS head units may have automation apps or custom launchers. Intent support enables integration (e.g., auto-connect when specific BT device pairs, or a home-screen widget that starts/stops AA).

**Effort**: Low. Add `IntentFilter`s, dispatch to `SessionManager`.

**Priority**: **Medium** — enables power-user automation.

---

### 15. Night Mode Strategies (6 modes)

**What HUR has**: 6 night mode strategies: Auto (location-based twilight), Day, Night, Manual Time, Light Sensor (lux threshold + hysteresis), Screen Brightness (threshold + hysteresis).

**What OAL has**: VHAL `NIGHT_MODE` property only — binary on/off from the car's sensor.

**Why port**: VHAL night mode works on real AAOS cars. But for:
- AAOS emulators (no light sensor)
- Aftermarket AAOS units (may lack VHAL integration)
- Testing (force day/night to verify UI behavior)

Having fallback strategies is useful. The light sensor strategy with hysteresis and debouncing (2s) is particularly well-engineered in HUR.

**Effort**: Low-medium. Extend night mode settings with manual/time/sensor options.

**Priority**: **Low-Medium** — VHAL works on real cars. Useful for broader AAOS platform support.

---

### ~~16. `maxUnacked` Flow Control Tuning~~ ✅ DONE

> **Completed.** `DirectAaSession.MAX_UNACKED = 30` for wireless. ACKs sent every 15 frames (MAX_UNACKED / 2). Already correct for wireless-only direct mode.

---

## Tier 3 — Lower Value / Conditional

### 17. GL Desaturation Shader for Night Mode

**What HUR has**: OpenGL ES fragment shader that desaturates video to grayscale during night mode (ITU-R BT.601 luminance, smooth interpolation via `uDesaturation` uniform).

**What OAL has**: No video post-processing.

**Why port**: Subtle desaturation reduces eye strain at night. However, on AAOS the system dark mode + AA's own night mode theming may be sufficient.

**Effort**: Medium. Requires `GLSurfaceView` + OES external texture. Risk: adds GPU load and possibly a frame of latency.

**Priority**: **Low** — visual polish vs latency tradeoff.

---

### 18. PiP (Picture-in-Picture) Support

**What HUR has**: PiP mode — AA shrinks to a floating window.

**What OAL has**: Full-screen projection only.

**Why port**: On AAOS, PiP could keep nav visible while using native car apps. However, AAOS multi-display/split-screen is more native.

**Effort**: Medium. Manifest flag + lifecycle handling + surface resize.

**Priority**: **Low** — AAOS has its own multi-window; PiP may conflict.

---

### 19. Fake Speed / Driving Status Debug Tools

**What HUR has**: `fakeSpeed` setting; hardcoded `UNRESTRICTED` driving status.

**What OAL has**: Real VHAL values.

**Why port**: Only useful for bench testing (force restricted mode to test voice-only UI, etc.). Should NOT be user-facing defaults.

**Effort**: Trivial.

**Priority**: **Low** — dev/debug tool only.

---

### 20. Force Software Video Decoding

**What HUR has**: Fallback to software H.264 decoder.

**What OAL has**: Hardware decoder only.

**Why port**: AAOS guarantees HW codec availability. Software fallback adds no value on real AAOS hardware. Could be useful for AAOS emulator testing where HW codecs are emulated and sometimes buggy.

**Effort**: Low. Add a setting to skip HW decoder preference in `VideoCodecSelector`.

**Priority**: **Very Low** — debug aid only.

---

## Not Applicable in Direct Mode

### Features that don't apply to OAL's architecture:

| Feature | Why N/A |
|---------|---------|

| **Network Subnet Discovery (port scan)** | OAL uses BT handshake + WiFi Direct/Nearby — doesn't need to scan for phones |
| **Chinese HU Key Receivers (Microntek, FYT, etc.)** | AAOS uses standard `KeyEvent` system |
| **Auto-Boot on Screen On / ACC** | OAL on AAOS is a system app, always running |
| **Hotspot Management** | OAL uses WiFi Direct (P2P group) — doesn't need to control the phone's hotspot |
| **Dummy VPN for Offline Mode** | AAOS head unit has its own cellular/WiFi; not relevant |
| **SilentAudioPlayer (Audio Focus Hack)** | AAOS `MediaSession` + proper audio focus handles this correctly |
| **Setup Wizard** | Different UX paradigm on AAOS |
| **BT SCO Mic Option** | Edge case; car mic is the right default on AAOS |

---

## Summary: Recommended Porting Priorities

| # | Feature | Effort | Impact | Status |
|---|---------|--------|--------|--------|
| 1 | Multi-codec ServiceDiscovery (H.264+H.265+VP9) | Low | High | **✅ DONE** |
| 2 | Multiple resolution tiers in ServiceDiscovery | Low | High | **✅ DONE** |
| 3 | Mic audio enhancement (NS + AGC + AEC) | Low | High | **✅ DONE** |
| 4 | AAC-LC audio codec | Med-High | High | **✅ DONE** |
| 5 | Bluetooth service announcement + channel 8 | Medium | High | **✅ DONE** |
| 6 | Phone status parsing (channel 12) | Low | Med-High | **✅ DONE** |
| 7 | Dynamic vehicle identity from VHAL | Low | Medium | **✅ DONE** |
| 8 | `maxUnacked` flow control tuning | Trivial | Medium | **✅ DONE** |
| 9 | **USB Host Connection (AOA)** | **Medium** | **High** | **Port now** |
| 10 | User key remapping | Medium | Medium | **✅ DONE** |
| 11 | Per-purpose volume offsets | Low | Medium | **✅ DONE** |
| 12 | Rotary controller input | Medium | Medium | Port soon |
| 13 | Auto-optimization wizard (codec/res detection) | Medium | Medium | Port soon |
| 14 | WiFi Direct frequency detection | Trivial | Medium | **✅ DONE** |
| 15 | Configurable audio buffer/latency | Medium | Medium | Port soon |
| 16 | Automation intents / deep links | Low | Medium | Port soon |
| 17 | Night mode strategies | Low-Med | Low-Med | Consider |
| 18 | GL desaturation shader | Medium | Low | Maybe later |
| 19 | PiP support | Medium | Low | Maybe later |
| 20 | Fake speed / driving status debug | Trivial | Low | Dev tool |

---

## Already in OAL (No Action Needed)

These HUR features already exist in OpenAutoLink's direct mode:

- ✅ `availableWhileInCall = true` on video + all audio sinks
- ✅ `canPlayNativeMediaDuringVr = true`
- ✅ `hideProjectedClock` / `hideClock` / `hideSignal` / `hideBattery` (configurable from Settings, wired to session bitmask)
- ✅ TLS session resumption (`createSSLEngine("android-auto", 5277)` with singleton `SSLContext`)
- ✅ MediaSession with now-playing metadata (title, artist, album, art, position)
- ✅ MediaBrowserService for AAOS system UI integration
- ✅ Full sensor suite (21 types including VEM for Maps battery-on-arrival — HUR has ~6 working)
- ✅ H.264 + H.265 + VP9 video codec support (multi-codec auto-negotiation)
- ✅ Multiple resolution tiers (480p through 4K) in ServiceDiscovery
- ✅ Mic audio enhancement (NoiseSuppressor + AGC + AcousticEchoCanceler)
- ✅ Dynamic vehicle identity from VHAL (make/model/year + driver position)
- ✅ `maxUnacked = 30` flow control for wireless
- ✅ Navigation turn-by-turn with cluster integration (HUR has notification only)
- ✅ Multi-touch input
- ✅ Night mode via VHAL
- ✅ GPS/GNSS forwarding (via LocationListener → LocationData protobuf)
- ✅ Accelerometer + Gyroscope + Compass sensors
- ✅ 60 FPS option
- ✅ Resolution and DPI configuration (auto or manual, 480p–4K, 80–400 DPI)
- ✅ Video margins / safe area insets
- ✅ IDR gating (no rendering before keyframe)
- ✅ WiFi Direct P2P group management
- ✅ Google Nearby Connections transport
- ✅ BT RFCOMM handshake + WiFi credential exchange
- ✅ NSD service advertisement (`_aawireless._tcp`)
- ✅ Voice session notifications
- ✅ Audio focus handling (always grant GAIN)
- ✅ Ping/pong keepalive
- ✅ Graceful ByeBye disconnect
- ✅ Drive side configuration (left/right → DriverPosition)
- ✅ AAC-LC audio codec support (MediaCodec decode, auto CSD generation)
- ✅ Bluetooth service announcement (A2DP + HFP pairing methods)
- ✅ Phone status parsing (signal strength + call state from channel 12)
- ✅ WiFi Direct frequency detection (5 GHz / 2.4 GHz in stats overlay)
- ✅ User-configurable key remapping (14 AA actions, capture dialog, JSON DataStore)
- ✅ Per-purpose volume offsets (media/nav/assistant sliders, -100% to +100%)

---

## Critical Bug Found During Analysis

~~**Sensor data doesn't reach the phone in direct mode.**~~ **✅ FIXED.** `SessionManager` now routes all sensor data (GNSS, vehicle data, IMU) through `DirectAaSession.sendMessage()` using `AaMessageConverter` protobuf serialization. Bridge-mode `ConnectionManager` has been fully removed.

Additionally fixed during the bridge removal refactoring:
- `PhoneConnected` control message now transitions session to `STREAMING` state in direct mode
- `requestKeyframe()` now routes through direct session (was silently dropped)
- SSL `BUFFER_OVERFLOW` on encrypt/decrypt fixed with dynamic buffer growth
