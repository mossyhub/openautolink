# Direct Mode — Bridgeless Android Auto for AAOS

## Overview

Direct Mode eliminates the SBC bridge entirely. The AAOS app speaks the AA wire protocol
directly to the phone over WiFi. No hardware purchase, no SBC setup, no SSH.

```
Android Phone (WiFi Hotspot)
    │
    ├── WiFi ──▶ Car AAOS head unit (connects to phone hotspot)
    │               │
    │               └── OpenAutoLink app
    │                     ├── TCP:5288 AA wire protocol (video, audio, control)
    │                     ├── MediaCodec decode → SurfaceView
    │                     ├── AudioTrack → CarAudioManager
    │                     ├── Touch → AA input channel
    │                     ├── VHAL sensors → AA sensor channel
    │                     └── Nav state → ClusterService
    │
    └── BT (optional) ──▶ Car native BT (HFP calls, contacts)
```

**Companion app** on the phone auto-discovers the car via NSD and triggers AA wireless
projection — no manual IP entry after initial setup.

## Why This Works

Validated April 2026 on 2024 Chevrolet Blazer EV:

1. Phone hotspot on → car WiFi connects to it → **bidirectional TCP works** (verified via
   diagnostics Network Probe: ping + TCP listener on port 5288)
2. `ServerSocket(5288)` binds successfully on AAOS — **no SELinux blocking**
3. Headunit Revived (HURev) demonstrates the full AA protocol in pure Kotlin — **no aasdk needed**
4. HURev's "Helper Mode" + companion app proves **AA wireless projection can be triggered
   without Bluetooth discovery**

## Architecture

### Connection Modes

| | Bridge Mode (current) | Direct Mode (new) |
|---|---|---|
| **Hardware** | SBC + USB NIC + power cable | Nothing |
| **Phone network** | SBC WiFi AP (192.168.43.x) | Phone hotspot |
| **Car network** | USB Ethernet (192.168.222.x) | Phone hotspot WiFi |
| **AA protocol** | aasdk C++ on SBC | Pure Kotlin in AAOS app |
| **BT role** | SBC handles pairing, HFP, RFCOMM | Not needed (companion app triggers) |
| **HFP calls** | SCO audio via SBC BT | Phone BT → car native BT (HFP) |
| **Latency** | Phone → SBC WiFi → Ethernet → Car | Phone → Car WiFi (one fewer hop) |
| **Reliability** | SBC is single point of failure | Phone hotspot is the dependency |

### App Architecture

Both modes share the same AAOS app. Only the transport layer differs:

```
┌─────────────────────────────────────────────────────┐
│                   OpenAutoLink App                    │
│                                                       │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌────────┐ │
│  │  Video   │  │  Audio  │  │  Input  │  │  Nav   │ │
│  │ Decoder  │  │ Player  │  │ Forward │  │Cluster │ │
│  └────▲─────┘  └────▲────┘  └────┬────┘  └───▲────┘ │
│       │             │            │            │       │
│  ┌────┴─────────────┴────────────┴────────────┴────┐ │
│  │              Session Manager                     │ │
│  │  ┌─────────────────┐  ┌────────────────────┐    │ │
│  │  │  Bridge Transport│  │  Direct Transport  │    │ │
│  │  │  (OAL protocol)  │  │  (AA wire protocol)│    │ │
│  │  │  TCP to SBC      │  │  TCP from phone    │    │ │
│  │  └─────────────────┘  └────────────────────┘    │ │
│  └──────────────────────────────────────────────────┘ │
│                                                       │
│  ┌──────────────────────────────────────────────────┐ │
│  │  VHAL Sensors / GNSS / IMU / VEM                 │ │
│  └──────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────┘
```

### Direct Transport Components

Ported from HURev's pure-Kotlin AA implementation, adapted to our architecture:

| Component | HURev Source | Our Integration |
|-----------|-------------|----------------|
| Wire framing | `AapMessage` | New `AaWireCodec` in `transport/direct/` |
| Message dispatch | `AapRead`, `AapMessageHandler` | New `AaMessageRouter` |
| SSL handshake | `AapSslContext` (Java SSL) | New `AaSslEngine` |
| Service Discovery | `ServiceDiscoveryResponse.kt` | Merge with our VHAL sensor list + VEM |
| Version exchange | `AapTransport.startTransport()` | Part of `DirectAaSession` |
| TCP server | `WirelessServer` | Part of `DirectAaSession` |
| NSD advertisement | `_aawireless._tcp` on port 5288 | In `DirectAaSession` |
| Video receive | `AapVideo` (fragment reassembly) | Feed into existing `VideoDecoder` |
| Audio receive | `AapAudio` (channel routing) | Feed into existing `AudioManager` |
| Touch send | `TouchEvent` protobuf | Reuse existing touch forwarding |
| Sensor send | `SensorBatch` protobuf | Reuse existing VHAL/GNSS/IMU code |

### Protobuf Schema

Our aasdk fork added custom sensor types that HURev doesn't have. These must be ported
to the app's proto definitions:

| Proto | Fields | Source |
|-------|--------|--------|
| `SensorType.proto` | 23-26 (VEM, trailer, raw VEM, raw EV trip) | Our aasdk fork |
| `SensorBatch.proto` | Fields 23-26 repeated messages | Our aasdk fork |
| `VehicleEnergyModel.proto` | Full EV energy model (battery, consumption, charging curves) | Reverse-engineered from Maps APK |
| `NavigationStatus.proto` | Turn events, nav state with lane guidance | Our aasdk fork |

These use the same protobuf field numbers and wire format — language doesn't matter.
The phone sees identical bytes whether they come from C++ protobuf (bridge) or Java
protobuf (direct mode).

## Implementation Plan

### Phase 0: Validate with HURev (0 code — 1 day)

**Status: DONE** ✓

- [x] Network probe tool in diagnostics (ping, TCP listener)
- [x] Verified: phone hotspot → car WiFi → bidirectional TCP works
- [x] Verified: `ServerSocket(5288)` binds on AAOS
- [x] Cloned HURev to `external/headunit-revived/`
- [x] Built HURev AAB with our package name (`com.openautolink.headunit`)
- [ ] Install HURev on car, test AA projection end-to-end via Wireless Helper

### Phase 1: Direct Transport in Our App (~10 days)

**Status: IMPLEMENTATION COMPLETE** — 9 commits on `feature/direct-mode`, pushed to GitHub

New package: `app/src/main/java/com/openautolink/app/transport/direct/`

#### 1a. AA Wire Protocol (~3 days)
- [x] `AaWireCodec` — message framing (channel, flags, length, type, payload)
- [x] `AaConstants` — channel IDs and message type constants
- [x] `AaSslEngine` — Java SSLEngine wrapper with memory BIOs (from HURev's `AapSslContext`)
- [x] SSL cert generation — AndroidKeyStore self-signed cert
- [ ] Unit tests for framing, SSL handshake sequence

#### 1b. Session Management (~2 days)
- [x] `DirectAaSession` — TCP server on 5288, version exchange, SSL, service discovery
- [x] `DirectServiceDiscovery` — build `ServiceDiscoveryResponse` with all VHAL sensors + VEM
- [x] NSD registration (`_aawireless._tcp` on port 5288)
- [x] Session state machine (connecting → handshake → streaming → disconnected)

#### 1c. Channel Handlers (~3 days)
- [x] `AaVideoAssembler` — parse fragments → reassemble → VideoFrame
- [x] Video channel → codec config + media data → existing VideoDecoder
- [x] Audio channels → PCM data → AudioFrame with correct AudioPurpose
- [x] Input channel → touch events → AA InputReport protobuf (multi-touch, pointer IDs)
- [x] Sensor channel → VHAL speed/gear/brake/night/fuel/RPM/accel/gyro/compass → SensorBatch
- [x] Navigation channel → parse NextTurnDetail (0x8004) + NextTurnDistanceEvent (0x8005) → NavState
- [x] Control channel → ping/pong, audio focus, channel open/close, byebye
- [x] Mic channel → sendMicAudio() on channel 7

#### 1d. Integration (~2 days)
- [x] `SessionManager` — add `DirectAaSession` as alternative to `BridgeSession`
- [x] `AppPreferences` — `connectionMode` preference ("bridge" / "direct"), default "direct"
- [x] `ProjectionViewModel` — reads `connectionMode` and passes to `SessionManager.start()`
- [x] Settings UI — "Connection Mode" picker (Direct / Bridge) with FilterChip toggle
- [x] `AaMessageConverter` — Touch→InputReport, Button→KeyEvent, VehicleData→SensorBatch
- [x] VEM sensor builder — VehicleEnergyModel (field 23) for Maps battery-on-arrival
- [x] SSL post-handshake data path — header unencrypted, body encrypted (matches HURev)
- [x] Reconnection — server loop auto-restarts after phone disconnect
- [x] Media playback metadata parsing (song/artist logged, emission deferred)
- [ ] End-to-end test on car

### Phase 2: Companion App (~3 days)

New repo or module: `companion/`

Simple phone app (~500 lines):

- [ ] NSD scanner — discover `_aawireless._tcp` services on local network
- [ ] AA projection trigger — launch AA wireless projection to discovered IP
  - Method 1: AA developer settings intent
  - Method 2: HURev Wireless Helper approach (reverse-engineer)
- [ ] Auto-start — BroadcastReceiver for `WIFI_AP_STATE_CHANGED` (hotspot on → scan)
- [ ] Notification — show "Connected to OpenAutoLink" when streaming
- [ ] Settings — manual IP override, auto-start toggle
- [ ] Play Store listing

### Phase 3: Polish & Parity (~5 days)

**Status: COMPLETE** — implemented on `feature/direct-mode`

- [x] TLS session resumption (SSLContext singleton survives across connections)
- [x] Audio focus management (parse AudioFocusRequestNotification, always grant STATE_GAIN)
- [x] Phone status display (channel handler ready, proto parsing deferred)
- [x] Media metadata forwarding (song/artist/album → ControlMessage.MediaMetadata → MediaSession)
- [x] Video focus indication handling (only sent on ChannelOpenRequest for video channel)
- [x] Flow control (MediaAckIndication — ack every 15 video frames, ack every audio frame)
- [x] Voice session notification (emit ControlMessage.VoiceSession for UI indicator)
- [ ] Portrait mode support
- [ ] Stress testing — reconnect cycles, hotspot toggle, app switch

### Phase 4: Bridge Mode Deprecation Path

- [ ] Bridge mode remains supported (users with existing SBC setups)
- [ ] Direct mode becomes default for new installs
- [ ] Bridge-specific code stays in `transport/bridge/`
- [ ] Direct-specific code in `transport/direct/`
- [ ] Shared code (video, audio, input, VHAL, cluster, UI) unchanged

## Audio Routing in Direct Mode

| Audio Type | Path |
|-----------|------|
| Media (Spotify, etc.) | AA audio channel → app → `CarAudioManager` `USAGE_MEDIA` |
| Nav prompts | AA audio channel → app → `CarAudioManager` `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` |
| Phone calls | Phone BT HFP → car native BT → car speakers (not through AA) |
| Teams/Zoom | Phone BT HFP → car native BT → car speakers |
| Google Assistant | AA audio channel → app → `CarAudioManager` `USAGE_ASSISTANT` |
| Mic (voice input) | Car mic → `AudioRecord` → AA mic channel → phone |

**Recommended phone BT config:** Pair phone to car's native BT with HFP enabled,
A2DP disabled. Calls go through car BT natively; media goes through AA projection.

## Risk Assessment

| Risk | Severity | Mitigation |
|------|----------|-----------|
| Phone hotspot unreliable | Medium | Bridge mode as fallback; car remembers hotspot SSID |
| GM AAOS update blocks `ServerSocket` | Low | Bridge mode as fallback; no evidence of blocking so far |
| AA protocol changes break direct mode | Low | HURev community maintains protocol; our bridge also validates |
| Companion app rejected from Play Store | Low | Can sideload; or use AA developer settings manually |
| Car WiFi latency vs dedicated AP | Low | One fewer hop; phone hotspot is direct link |
| Phone battery drain from hotspot | Medium | Modern phones handle this well; car USB charges phone |

## File Structure

```
app/src/main/java/com/openautolink/app/
├── transport/
│   ├── bridge/          # Existing: OAL protocol to SBC
│   │   ├── BridgeSession.kt
│   │   ├── ControlChannel.kt
│   │   ├── VideoChannel.kt
│   │   └── AudioChannel.kt
│   ├── direct/          # New: AA wire protocol to phone
│   │   ├── DirectAaSession.kt
│   │   ├── AaWireCodec.kt
│   │   ├── AaMessageRouter.kt
│   │   ├── AaSslEngine.kt
│   │   ├── DirectServiceDiscovery.kt
│   │   ├── AaVideoReceiver.kt
│   │   ├── AaAudioReceiver.kt
│   │   ├── AaSensorSender.kt
│   │   ├── AaTouchSender.kt
│   │   └── AaMicSender.kt
│   ├── ConnectionMode.kt  # enum: BRIDGE, DIRECT
│   └── SessionManager.kt  # routes to correct transport
├── proto/                 # New: AA protocol protobufs
│   ├── sensors.proto      # Extended with fields 23-26
│   ├── VehicleEnergyModel.proto
│   ├── control.proto
│   ├── media.proto
│   ├── input.proto
│   ├── navigation.proto
│   └── wireless.proto
└── ... (existing packages unchanged)
```

## Reference Code

| Reference | Location | What to Port |
|-----------|----------|-------------|
| AA wire protocol | `external/headunit-revived/app/src/main/java/com/andrerinas/headunitrevived/aap/` | `AapTransport`, `AapRead`, `AapMessage`, `AapSsl*` |
| Service Discovery | `external/headunit-revived/.../aap/protocol/messages/ServiceDiscoveryResponse.kt` | SDR builder pattern |
| Protobuf schema | `external/headunit-revived/app/src/main/proto/` | 8 proto files (baseline) |
| VEM proto extensions | `external/opencardev-aasdk/protobuf/.../VehicleEnergyModel.proto` | Our custom sensor types |
| Nav extensions | `external/opencardev-aasdk/.../NavigationStatusService.*` | Our nav state callbacks |
| Video fragment assembly | `external/headunit-revived/.../aap/AapVideo.kt` | Fragment reassembly logic |
| SSL implementation | `external/headunit-revived/.../ssl/AapSslContext.kt` | Java SSLEngine approach |
| TCP server + NSD | `external/headunit-revived/.../aap/AapService.kt` (WirelessServer) | TCP listener pattern |

## Success Criteria

1. Phone hotspot on → companion app auto-discovers car → AA projection starts
2. Video renders at ≥30fps with <100ms latency
3. Touch response feels identical to bridge mode
4. Google Maps shows battery-on-arrival % (VEM sensor working)
5. Instrument cluster shows turn-by-turn navigation
6. Phone calls work through car's native BT (not AA)
7. Google Assistant works (mic → phone → response audio)
8. Reconnection: hotspot toggle → auto-reconnect within 5s
9. No user interaction required after initial phone/car setup
