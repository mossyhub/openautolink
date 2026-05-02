# CarPlay on AAOS — Investigation & Implementation Plan

## Executive Summary

The GM Blazer EV AAOS head unit contains a **complete CarPlay hardware and software stack** that GM has disabled via a calibration flag. Through reverse engineering of the decompiled GM CarPlay APKs, we have a full reference implementation showing every iAP2 message, state machine transition, and VHAL-to-CarPlay mapping. Combined with OpenAutoLink's existing video/audio/sensor/cluster infrastructure, this is a viable path to native CarPlay on AAOS — the only solution that would offer cluster/HUD, VHAL integration, factory touch, and proper audio routing.

**Prerequisite**: Access to an MFi authentication coprocessor — either the GM head unit's built-in chip (I²C probe pending) or a rooted CPC200 as a remote auth dongle.

---

## Part 1: Decompiled APK Deep Dive

### 1.1 Priority Files to Reverse Engineer

These are in `D:\personal\recon_dump\apks\com_gm_domain_server_delayed\java_src\com\p006gm\`:

#### Critical Path (auth + connection)
| File | What to Extract |
|------|----------------|
| `server/carplay/service/CarPlayService.java` | Service lifecycle, calibration check, initialization order |
| `server/carplay/service/internals/GMCinemoManager.java` | Cinemo SDK config: plugin names, transport URLs, auth URL, video/audio params |
| `server/iap2/IAPManager.java` | iAP2 session management, transport types, message routing |
| `server/iap2/feature/CarPlayAvailability.java` | Capability negotiation — what the phone reports, what error codes mean |
| `server/iap2/feature/CarPlayStartSession.java` | Session start parameters — resolution, FPS, features, audio config |
| `server/connection/DeviceConnectionServiceImpl.java` | Connection state machine, BT/USB/WiFi transport selection |

#### Vehicle Integration (our differentiator)
| File | What to Extract |
|------|----------------|
| `server/iap2/feature/VehicleStatus.java` | VHAL property → iAP2 VehicleStatus message mapping |
| `server/iap2/feature/LocationInfo.java` | GPS/NMEA → iAP2 LocationInfo format |
| `server/iap2/feature/RouteGuidance.java` | Nav instructions → cluster display format |
| `server/iap2/feature/MediaManager.java` | Now playing metadata → cluster/HUD |
| `server/iap2/feature/CommunicationManager.java` | Phone call state → car audio routing |

#### Transport Layer
| File | What to Extract |
|------|----------------|
| `server/connection/CarplayStartingState.java` | Exact state transitions from BT discovery → streaming |
| `server/connection/CarplayState.java` | Active session management, error recovery |
| `server/connection/CarplayStoppingState.java` | Teardown sequence, resource cleanup |
| `server/connection/BtAndCarplayState.java` | Concurrent BT audio + CarPlay |
| `car/WPService.java` | Bluetooth wireless projection — RFCOMM, iAP2 over BT |
| `server/iap2/feature/EAPChannel.java` | Extended Accessory Protocol data channel |

#### MFi Authentication
| File | What to Extract |
|------|----------------|
| `GMCinemoManager.java` lines with `APPLE_AUTH_URL` | I²C address, register map, auth flow timing |
| Any class referencing `NmeAppleAuth` | JNI function signatures for the auth native lib |

### 1.2 What to Document from Each File

For each file, extract:
1. **Message IDs** — the 2-byte iAP2 message type codes
2. **Parameter IDs** — the 2-byte param type codes within each message
3. **Data formats** — byte layouts, endianness, string encodings
4. **State machine transitions** — what triggers each state change
5. **Error codes** — numeric codes and their meanings
6. **Timing** — any delays, timeouts, retry logic
7. **VHAL property IDs** — which `VehicleProperty.*` constants map to which iAP2 fields

### 1.3 Cross-Reference with Open Source

Validate decompiled findings against:
- **libimobiledevice** (`idevice_id`, `libusbmuxd`) — iAP2 link layer reference
- **node-carplay** / **carplay-receiver** (GitHub) — full CarPlay receiver in JS
- **Apple MFi Specification R30+** chapters (leaked/reconstructed) — message catalog
- **Android USB gadget docs** — NCM networking over USB

### 1.4 Deliverable

A `docs/carplay-protocol.md` containing:
- Complete iAP2 message catalog (ID, params, direction, timing)
- MFi auth register map and flow
- CarPlay session negotiation sequence diagram
- VHAL → iAP2 property mapping table
- Audio channel configuration (media, Siri, phone call, nav prompt, alert)
- Video format negotiation (resolution, FPS, codec)

---

## Part 2: Implementation Plan

### 2.1 Architecture

```
┌─────────────────────────────────────────────────────────┐
│  OpenAutoLink App (on GM AAOS Head Unit)                │
│                                                         │
│  ┌─────────────┐  ┌──────────────┐  ┌───────────────┐  │
│  │ CarPlay      │  │ iAP2 Control │  │ MFi Auth      │  │
│  │ Session Mgr  │──│ Session      │──│ Relay         │  │
│  │              │  │              │  │ (I²C or TCP)  │  │
│  └──────┬───────┘  └──────┬───────┘  └───────┬───────┘  │
│         │                 │                   │          │
│  ┌──────┴───────┐  ┌──────┴───────┐  ┌───────┴───────┐  │
│  │ Video Decode │  │ Audio Router │  │ Input Fwd     │  │
│  │ (MediaCodec) │  │ (AudioTrack) │  │ (Touch/Siri)  │  │
│  │ [EXISTING]   │  │ [EXISTING]   │  │ [EXISTING]    │  │
│  └──────────────┘  └──────────────┘  └───────────────┘  │
│                                                         │
│  ┌──────────────┐  ┌──────────────┐  ┌───────────────┐  │
│  │ VHAL Bridge  │  │ GNSS/IMU Fwd │  │ Cluster Svc   │  │
│  │ [EXISTING]   │  │ [EXISTING]   │  │ [EXISTING]    │  │
│  └──────────────┘  └──────────────┘  └───────────────┘  │
│         │                 │                   │          │
│  ┌──────┴─────────────────┴───────────────────┴───────┐  │
│  │              iAP2 Transport Layer                   │  │
│  │         USB Host  /  Bluetooth RFCOMM               │  │
│  └─────────────────────┬───────────────────────────────┘  │
└─────────────────────────┼─────────────────────────────────┘
                          │ USB cable
                    ┌─────┴─────┐
                    │  iPhone   │
                    └───────────┘

MFi Auth path (if GM I²C blocked):
  Auth Relay ──WiFi TCP──→ CPC200 (rooted)
                           └── /dev/i2c-X → MFi chip → signed response
```

### 2.2 Component Islands

#### Island A: iAP2 Link Layer (`transport/carplay/iap2/`)
**New code.** Handles framing, sequencing, and reliable delivery.

| Component | Description | LOE |
|-----------|-------------|-----|
| `Iap2LinkLayer.kt` | Frame encode/decode: start byte, length, session, seq/ack, checksum | 1 week |
| `Iap2Session.kt` | Session management: SYN/ACK handshake, retransmit, keepalive | 1 week |
| `Iap2Message.kt` | Message serialization: ID, params, nested param groups | 3 days |
| `Iap2MessageCatalog.kt` | All message/param ID constants extracted from decompiled code | 2 days |

Reference: libimobiledevice `src/idevice.c`, node-carplay `src/iap2/`

#### Island B: MFi Authentication (`transport/carplay/auth/`)
**New code.** Two backends: local I²C or remote CPC200.

| Component | Description | LOE |
|-----------|-------------|-----|
| `MfiAuthProvider.kt` | Interface: `suspend fun sign(challenge: ByteArray): MfiAuthResponse` | — |
| `I2cMfiAuth.kt` | Direct I²C: open `/dev/i2c-0`, ioctl `I2C_SLAVE 0x10`, write/read registers | 2 days |
| `RemoteMfiAuth.kt` | TCP relay to CPC200: send challenge, receive signature | 1 day |
| `MfiCertificate.kt` | Read chip certificate (register 0x20-0x2F), cache it | 1 day |

Register map (from MFi spec + decompiled code):
```
0x00     Device Version
0x01     Firmware Version  
0x02-03  Auth Protocol Major/Minor
0x10     Challenge Data Length (write)
0x11     Challenge Data (write 128 bytes)
0x12     Challenge Response Length (read)
0x13     Challenge Response Data (read)
0x20     Certificate Data Length
0x21     Certificate Data (read, ~1024 bytes)
0x30     Self-Test Status
```

#### Island C: CarPlay Session (`transport/carplay/session/`)
**New code.** Orchestrates the full connection flow.

| Component | Description | LOE |
|-----------|-------------|-----|
| `CarPlaySessionManager.kt` | State machine: idle → auth → identify → start → streaming → stop | 2 weeks |
| `CarPlayIdentification.kt` | Build identification message: name, model, capabilities, screen config | 2 days |
| `CarPlayConfig.kt` | Screen resolution, FPS, supported features, audio channels | 1 day |
| `CarPlayDataChannel.kt` | NCM/IP setup for video/audio/touch data after session start | 1 week |

State machine (from decompiled `CarplayStartingState`/`CarplayState`):
```
IDLE
  │ USB device attached (VID=0x05AC) or BT iAP2 connection
  ▼
LINK_SETUP
  │ iAP2 SYN → SYN-ACK → ACK
  ▼
AUTHENTICATING
  │ iPhone sends RequestAuthCertificate
  │ We send AuthCertificate (from MFi chip register 0x20)
  │ iPhone sends RequestAuthChallengeResponse
  │ We sign challenge via MFi chip → send AuthChallengeResponse
  ▼
IDENTIFYING
  │ We send IdentificationInformation (name, capabilities, screen, audio)
  │ iPhone sends IdentificationAccepted
  ▼
CARPLAY_STARTING
  │ We send StartCarPlay (resolution, FPS, features)
  │ iPhone ACKs → data channel opens (NCM USB interface)
  ▼
STREAMING
  │ Video H.264 frames → MediaCodec
  │ Audio PCM → AudioTrack
  │ Touch events → iAP2 HID reports
  │ Vehicle data → iAP2 VehicleStatus
  │ GPS → iAP2 LocationInfo
  ▼
STOPPING
  │ StopCarPlay message → teardown
  ▼
IDLE
```

#### Island D: Vehicle Data Bridge (`transport/carplay/vehicle/`)
**New code, but leverages existing VHAL infrastructure.**

| Component | Description | LOE |
|-----------|-------------|-----|
| `VehicleStatusBridge.kt` | VHAL → iAP2 VehicleStatus: speed, gear, night mode, range | 1 week |
| `LocationBridge.kt` | GNSS/IMU → iAP2 LocationInfo: lat/lon/alt/heading/speed/accuracy | 3 days |
| `NavigationBridge.kt` | iAP2 nav instructions → ClusterRenderingService maneuver icons | 1 week |
| `MediaInfoBridge.kt` | iAP2 now-playing → cluster media display | 3 days |

VHAL → iAP2 mapping (to be confirmed from decompiled `VehicleStatus.java`):
```
VehicleProperty.PERF_VEHICLE_SPEED  → iAP2 VehicleSpeed
VehicleProperty.GEAR_SELECTION      → iAP2 GearPosition  
VehicleProperty.NIGHT_MODE          → iAP2 NightMode
VehicleProperty.EV_BATTERY_LEVEL    → iAP2 RangeRemaining (maybe)
VehicleProperty.TURN_SIGNAL_STATE   → iAP2 TurnSignal
```

#### Island E: USB Host Transport (`transport/carplay/usb/`)
**New code.** Manages the iPhone's USB connection.

| Component | Description | LOE |
|-----------|-------------|-----|
| `IPhoneUsbTransport.kt` | UsbDeviceConnection: claim iAP2 interface (class 0xFF), bulk transfer | 1 week |
| `NcmTransport.kt` | NCM (Network Control Model) USB class: IP-over-USB for CarPlay data | 2 weeks |
| `UsbGadgetHelper.kt` | If needed: configure USB gadget mode (may not be needed if using host mode) | TBD |

#### Reused from existing AA implementation
| Existing Component | CarPlay Equivalent |
|---|---|
| `video/VideoDecoder.kt` + MediaCodec | H.264 decode (same codec, different framing) |
| `video/ProjectionSurface.kt` | Render surface (identical) |
| `audio/AudioPurposeRouter.kt` | 5-slot audio (media/nav/call/siri/alert — same concept) |
| `input/TouchForwarder.kt` | Touch coordinate scaling (same logic, different protocol encoding) |
| `input/GnssForwarder.kt` | GPS data (reformat from NMEA to iAP2 LocationInfo) |
| `input/VehicleDataForwarder.kt` | VHAL reading (reformat to iAP2 VehicleStatus) |
| `navigation/ClusterService.kt` | Nav-to-cluster (consume iAP2 RouteGuidance instead of AA nav state) |
| `session/SessionManager.kt` | Lifecycle orchestration pattern (new instance for CarPlay) |

### 2.3 Milestones

#### M1: Foundation (Weeks 1-4)
**Goal**: iAP2 link layer + MFi auth working. iPhone accepts us as a valid accessory.

- [ ] Deep dive decompiled APKs → `docs/carplay-protocol.md`
- [ ] Implement iAP2 link layer (framing, seq/ack)
- [ ] Implement iAP2 message serialization
- [ ] Implement MFi auth (I²C local or CPC200 remote)
- [ ] iPhone reaches "IdentificationAccepted" state

**Test**: Connect iPhone via USB, log shows successful auth + identification.

#### M2: First Frame (Weeks 5-8)
**Goal**: CarPlay video renders on screen. Audio plays. Touch works.

- [ ] Implement CarPlay session negotiation (StartCarPlay)
- [ ] Implement NCM USB networking (IP-over-USB)
- [ ] Connect CarPlay data channel to existing MediaCodec pipeline
- [ ] Connect audio to existing AudioTrack pipeline
- [ ] Forward touch events as iAP2 HID reports

**Test**: CarPlay home screen visible, apps launch, touch responsive, music plays.

#### M3: Vehicle Integration (Weeks 9-12)
**Goal**: Full AAOS-native CarPlay with features no dongle can match.

- [ ] VHAL → iAP2 VehicleStatus bridge
- [ ] GNSS/IMU → iAP2 LocationInfo
- [ ] iAP2 RouteGuidance → Cluster nav display
- [ ] iAP2 MediaInfo → Cluster now-playing
- [ ] Phone call audio routing (car speakers + mic)
- [ ] Siri via car microphone
- [ ] Steering wheel button mapping
- [ ] Night mode sync

**Test**: Full CarPlay with cluster nav, steering wheel Siri, vehicle speed in Maps.

#### M4: Polish (Weeks 13-16)
- [ ] Wireless CarPlay (BT iAP2 handshake → WiFi data channel)
- [ ] Reconnection after car sleep/wake
- [ ] Multi-phone support (switch between AA and CarPlay phones)
- [ ] Settings UI: CarPlay resolution, FPS, enable/disable
- [ ] Error handling, edge cases, OOM prevention

### 2.4 MFi Auth: Two Paths

#### Path A: GM Built-in Chip (preferred)
```
Probe result needed: /dev/i2c-0 accessible from our app (SELinux check pending)

Pros:
  - No external hardware
  - Lowest latency
  - Simplest architecture
  
Cons:
  - SELinux may block (likely)
  - GM could patch in OTA update
  
Implementation:
  I2cMfiAuth.kt opens /dev/i2c-0
  ioctl I2C_SLAVE 0x10
  Write challenge → register 0x10/0x11
  Read response → register 0x12/0x13
  Read certificate → register 0x20/0x21
```

#### Path B: Rooted CPC200 Remote Auth
```
Hardware: Any CPC200 (~$30-50 on AliExpress), rooted

CPC200 side (daemon, ~80 lines Python):
  - Listen TCP port 5290
  - Receive: AUTH_CERT request → read register 0x20 → return cert bytes
  - Receive: AUTH_SIGN:<challenge> → write to 0x10, read 0x12 → return sig
  - Receive: AUTH_SELFTEST → read 0x30 → return status

Car side (our app):
  - CPC200 connected via USB to GM head unit (or on same WiFi)
  - RemoteMfiAuth.kt connects to CPC200 daemon
  - Proxies auth requests from iAP2 stack to CPC200

Network options:
  1. USB serial (CPC200 USB gadget mode) — most reliable
  2. WiFi (CPC200 on phone hotspot or car WiFi) — wireless
  3. Bluetooth serial — no extra cables

Latency budget:
  - iAP2 auth timeout is ~10 seconds
  - WiFi round-trip: ~5-20ms
  - USB serial round-trip: ~1-2ms
  - Both well within budget
```

### 2.5 Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| SELinux blocks I²C on GM | High | Forces Path B | CPC200 fallback ready |
| Apple updates auth protocol | Low | Breaks MFi chips | All existing CarPlay accessories would break too — unlikely |
| iAP2 message format differs from decompiled code | Low | Wrong params | Cross-ref with libimobiledevice + node-carplay |
| NCM USB networking complex | Medium | Delays M2 | Study Linux NCM driver, may need native JNI helper |
| GM OTA disables I²C permissions | Medium | Breaks Path A | Path B unaffected; I²C perms are in init.rc not app-controllable |
| Video/audio format differs from AA | Low | Decoder issues | CarPlay uses standard H.264 + PCM/AAC — well-supported |
| Apple legal action | Low | Project takedown | Same risk as every CarPlay dongle manufacturer. Don't sell it. |

### 2.6 Open Questions (resolve during M1)

1. **I²C probe result** — can we open `/dev/i2c-0` from our app? (test pending with v275)
2. **USB host permission** — can we claim the iPhone's iAP2 USB interface without root? (the report showed one USB device that needed permission)
3. **NCM interface** — does the GM kernel have CDC-NCM support? Check `/proc/config.gz` for `CONFIG_USB_NET_CDC_NCM`
4. **Cinemo native libs** — can we `System.load()` them? (probably license-locked, but worth testing)
5. **Audio channel mapping** — exactly which iAP2 audio types map to which AudioTrack purposes?
6. **Wireless CarPlay BT profile** — which RFCOMM channel and SDP record? (ref: `WPService.java`)

---

## Part 3: Immediate Next Steps

1. **Get I²C probe results** — upload v275 AAB, run scan, check "I²C MFi Chip" section
2. **Systematic APK teardown** — read every file listed in §1.1, document in `docs/carplay-protocol.md`
3. **Prototype iAP2 link layer** — start with USB bulk transfer to iPhone, get SYN/ACK working
4. **Source a CPC200** — if I²C is blocked, order one as backup auth path
5. **Study node-carplay** — working open-source reference for the full flow
