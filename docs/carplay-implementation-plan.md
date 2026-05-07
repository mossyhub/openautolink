# CarPlay on AAOS — Investigation & Implementation Plan

## Executive Summary

The GM Blazer EV AAOS head unit contains a **complete CarPlay hardware and software stack** that GM has disabled via a calibration flag (`Enable_Application_Apple_Carplay`). Through reverse engineering of the decompiled GM CarPlay APKs, we have a full reference implementation showing every iAP2 message, state machine transition, and VHAL-to-CarPlay mapping. Combined with OpenAutoLink's existing video/audio/sensor/cluster infrastructure, this is a viable path to native CarPlay on AAOS — the only solution that would offer cluster/HUD, VHAL integration, factory touch, and proper audio routing.

**MFi Authentication**: A rooted CPC200 AutoKit dongle serves as a remote MFi signing daemon over TCP. The GM head unit's built-in chip is present but SELinux-blocked (`u:r:untrusted_app` cannot access `/dev/i2c-0`).

## Current Status (updated 2026-05-07)

### Confirmed Hardware
- **GM I²C MFi chip**: EXISTS at `/dev/i2c-0:0x10`, but EACCES (SELinux `untrusted_app` context). **Dead — no userspace path without root.**
- **CPC200 MFi chip**: CONFIRMED at `/dev/i2c-1:0x11`. Apple Auth Coprocessor v3, firmware 0x01, protocol 2.0. 945-byte certificate reads successfully. Signing works.

### Built & Tested
- [x] Recon scanner — probed all packages, properties, filesystem, services, USB, features, I²C
- [x] MFi auth daemon (`scripts/mfi_auth_daemon.c`) — deployed on CPC200, TCP port 5290, tested: PING, GET_INFO, GET_CERT, SIGN all working
- [x] MFi probe tool (`scripts/mfi_probe.c`) — confirmed chip on CPC200
- [x] Key-based SSH to CPC200 (dropbear, RSA-2048)
- [x] iAP2 link layer (`transport/carplay/iap2/Iap2LinkLayer.kt`) — frame encode/decode, SYN params
- [x] iAP2 message serialization (`transport/carplay/iap2/Iap2Message.kt`) — message + param TLV, message ID catalog
- [x] MFi auth client (`transport/carplay/auth/MfiAuthClient.kt`) — Kotlin TCP client for CPC200 daemon

### Architecture (confirmed)
```
iPhone ──USB──→ GM Head Unit (our app)
                  ├── iAP2 link layer (Iap2LinkLayer.kt)
                  ├── iAP2 control session (Iap2Message.kt)
                  ├── MFi auth relay ──WiFi TCP──→ CPC200 (/dev/i2c-1:0x11)
                  ├── H.264 video → MediaCodec [EXISTING]
                  ├── Audio → AudioTrack [EXISTING]
                  ├── Touch → iAP2 HID [EXISTING logic]
                  ├── VHAL/GPS/Cluster [EXISTING]
                  └── CarPlay data channel (AirPlay over USB NCM or WiFi)
```

### Key Findings from Decompiled Code
- **Calibration gatekeeper**: `Enable_Application_Apple_Carplay` → `stopSelf()` if false
- **Error 30**: "compatible phone not connected" = wireless CarPlay unavailable (phone reports via iAP2)
- **IP redirect works**: `CarPlayStartSession.setIPAddress()` accepts any IP — validated from decompiled `getWirelessAttributes()`
- **Wireless requires BT**: iPhone must BT-discover CarPlay SDP service. No software-only trigger exists on iOS (unlike AA's `WirelessStartupReceiver`)
- **CPC200 is AutoKit**: model = "Magic-Car-Link-1.00", has `ARMiPhoneIAP2`, `AppleCarPlay`, `usbmuxd` binaries

---

## Part 1: Decompiled APK Deep Dive

### 1.1 Priority Files to Reverse Engineer

These are in `D:\personal\openautolink\recon_dump\apks\com_gm_domain_server_delayed\java_src\com\p006gm\`:

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

### 2.4 MFi Auth: CPC200 Remote Signing (confirmed working)

```
Hardware: Rooted CPC200 AutoKit "Magic-Car-Link-1.00"
  SSH: root@192.168.43.1 (dropbear, key auth, RSA-2048)
  MFi chip: /dev/i2c-1 addr 0x11, Apple Auth Coprocessor v3
  Daemon: /tmp/mfi_auth_daemon on TCP port 5290

Protocol: [cmd:1][len:2 LE][data:len] → [status:1][len:2 LE][data:len]
  0x01 PING → "OK"
  0x02 GET_CERT → 945-byte Apple certificate
  0x03 SIGN_CHALLENGE → ECDSA signed response
  0x04 GET_INFO → chip version/firmware/proto/error/selftest

Tested: All commands work. Certificate is valid ASN.1/DER (starts with 0x3082).
Cross-compile: wsl arm-linux-gnueabihf-gcc -static -O2

Note: GM head unit I²C path is DEAD.
  /dev/i2c-0 exists, chmod 0666 in init.carplay.rc, but:
  - DAC: read=false, write=false (chmod didn't run or was overridden)
  - SELinux: our app context u:r:untrusted_app blocks I²C access
  - No path to system_app context without GM's platform signing key
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

### 2.6 Resolved Questions

1. **I²C probe result** — BLOCKED. EACCES (errno 13), SELinux `untrusted_app` context. No path without root.
2. **USB host permission** — GM AAOS prompts every time (known bug). Functional but annoying.
3. **NCM interface** — TBD. Need to check `/proc/config.gz` for `CONFIG_USB_NET_CDC_NCM`.
4. **Cinemo native libs** — Not pursuing. Building our own iAP2 stack from decompiled reference.
5. **Audio channel mapping** — TBD. Will map during M2.
6. **Wireless CarPlay BT profile** — Requires CPC200 BT. iPhone must discover SDP service. No software-only trigger on iOS.
7. **CarPlay IP redirect** — CONFIRMED. `setIPAddress()` in StartCarPlay accepts any IP. CPC200 can tell iPhone to connect data channel to head unit's IP.

---

## Part 3: Current Next Steps

1. ~~Get I²C probe results~~ → DONE. Blocked by SELinux.
2. ~~Source a CPC200~~ → DONE. Rooted, MFi daemon running.
3. ~~Prototype iAP2 link layer~~ → DONE. Frame codec + SYN params.
4. **Build USB host transport** — `UsbDeviceConnection` to claim iPhone iAP2 interface
5. **Build iAP2 session manager** — state machine: SYN → auth → identify → StartCarPlay
6. **Get first iAP2 handshake working** — iPhone accepts our SYN, we pass auth via CPC200
7. **CarPlay data channel** — receive AirPlay H.264 stream, feed to MediaCodec
