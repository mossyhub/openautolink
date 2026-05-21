# CarPlay on AAOS — Investigation & Implementation Plan

## Architecture (LOCKED 2026-05-20)

Wireless CarPlay via the CPC200 dongle. **No vendor binary reverse engineering required.** The vendor stack on the CPC200 is left running purely as a BT/Wi-Fi pairing helper; its CarPlay daemons stall on "AppServer" — that is harmless noise and we ignore it.

```
iPhone ──BT pair (vendor stack)────────► CPC200 dongle
   │                                       │  hands iPhone the Wi-Fi creds over RFCOMM
   ▼                                       ▼
iPhone joins shared Wi-Fi  ◄── CPC200 AP  OR  GM car AP (see "Subnet caveat" below)
   │
   │  iPhone resolves _carplay._tcp.local via mDNS
   ▼
   CPC200 broker (oal-cp-broker)
     ├── mDNS responder      → advertises broker's own AP IP on port 7000
     ├── TCP listener :7000  → accepts iPhone's CarPlay session
     ├── TCP relay           → pipes bytes to AAOS app at <target>:7000
     └── MFi auth daemon     → 127.0.0.1:5290 (i²c-1 0x11 signing)
   │
   ▼
   AAOS app (CarPlayTcpListener) ── existing video/audio/sensor/cluster pipeline
```

**CPC200's complete responsibilities:**
1. BT-pair iPhone (vendor `bluetoothDaemon` + `AppleCarPlay`)
2. Hand iPhone Wi-Fi credentials over RFCOMM (vendor)
3. Advertise `_carplay._tcp` via mDNS (broker — ✅ working)
4. Accept iPhone TCP on port 7000 and relay to AAOS (broker — ✅ working as of 0.1.338)
5. Expose `/dev/i2c-1:0x11` MFi co-processor as TCP service (mfi_auth_daemon — ✅ working)

**Subnet caveat — GM car AP randomizes its subnet every boot.** Mirroring AA's pattern (the AAOS app does UDP broadcast discovery), the broker must not bake in a default subnet. As of 0.1.338 the broker:
- Refuses to run relay modes without an explicit `--target IP`.
- Auto-picks its mDNS advertise IP from the kernel's outbound route to `--target` (UDP-connect trick), so whichever subnet the AP gives us today, the broker advertises on the right interface.
- Accepts `--target auto` to passive-listen on UDP/5278 for the AAOS app's `OAL-CP-ANNOUNCE` broadcast (mirror of `PhoneDiscovery` on the AA side). The AAOS app's `CarPlayAnnouncer` broadcasts `OAL-CP-ANNOUNCE v=<ver> ip=<dotted> port=<n>` every 2s from every non-virtual interface; broker learns the IP at boot without any static config.

### Discarded paths (do not resurrect)
- **Vendor binary RE / AppServer impersonation.** Hewei's `AppleCarPlay`/`ARMiPhoneIAP2`/`ARMadb-driver` are statically linked, section-header-stripped, and string-table-encrypted (each binary starts with a unique 29-char hex hash). The CPC200 kernel has ptrace disabled (PTRACE_TRACEME returns EINVAL even for `/bin/true`), no `/proc/config.gz`, no kallsyms — deliberate anti-RE matching the binary obfuscation. We do **not** need to understand or replace these binaries.
- **USB CarPlay path on the GM HU.** The GM HU's internal MFi chip is SELinux-blocked from userspace (`u:r:untrusted_app` cannot touch `/dev/i2c-0`). Wireless via CPC200 is the only viable route.

## Executive Summary

The GM Blazer EV AAOS head unit contains a **complete CarPlay hardware and software stack** that GM has disabled via a calibration flag (`Enable_Application_Apple_Carplay`). Through reverse engineering of the decompiled GM CarPlay APKs, we have a full reference implementation showing every iAP2 message, state machine transition, and VHAL-to-CarPlay mapping. Combined with OpenAutoLink's existing video/audio/sensor/cluster infrastructure, this is a viable path to native CarPlay on AAOS — the only solution that would offer cluster/HUD, VHAL integration, factory touch, and proper audio routing.

**MFi Authentication**: A rooted CPC200 AutoKit dongle serves as a remote MFi signing daemon over TCP. The GM head unit's built-in chip is present but SELinux-blocked (`u:r:untrusted_app` cannot access `/dev/i2c-0`).

## Current Status (updated 2026-05-20)

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
- [x] AAOS-side CarPlay TCP listener (`transport/carplay/wireless/CarPlayTcpListener.kt`) on port **5000** (RTSP/pair-setup entry, per `wireless_carplay.md`)
- [x] **CPC200 broker** (`bridge/cpc200/oal-cp-broker`, 0.1.340) — mDNS responder + TCP relay + `--target auto` UDP discovery, auto-pick advertise IP, GM-AP-safe defaults
- [x] **CarPlayAnnouncer** (`transport/carplay/wireless/CarPlayAnnouncer.kt`) — AAOS-side UDP broadcaster that feeds the broker's `--target auto`
- [x] iPhone BT pair + Wi-Fi join on CPC200 AP, iPhone Discovery app sees `OpenAutoLink._carplay._tcp.local`
- [x] **mDNS TXT records aligned to real receivers** (0.1.340): `model=A15W`, `features=0x5A7FFFFH`, `srcvers=320.17`, `vv=2` — required so iOS treats us as a valid CarPlay receiver and proceeds to TCP connect



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
4. ~~USB host transport~~ → DEPRIORITIZED. We have no GM head unit to develop on; see Part 4.
5. ~~iAP2 session manager (USB)~~ → SCAFFOLDED in `transport/carplay/session/`, kept for reference.

---

## Part 4: Wireless CarPlay Pivot (2026-05-20)

### Why pivot

The USB plan assumed access to a GM AAOS head unit with the iPhone plugged in. We don't have that for dev; sitting in the car to iterate is too slow. The CPC200 dongle already does wireless CarPlay end-to-end in its stock firmware, with a known signing oracle (MFi chip on `/dev/i2c-1:0x11`) and a known WiFi handoff mechanism.

**New plan:** put the CPC200 in **lean AP mode** (no Carlinkit CarPlay services, see [scripts/cpc200/Enter-LeanMode.ps1](../scripts/cpc200/Enter-LeanMode.ps1)), then build:
- our own **CPC200 wireless broker binary** (replaces `AppleCarPlay`/`ARMiPhoneIAP2`) — does BT iAP2 handshake, calls our existing `mfi_auth_daemon` for signing, and tells the iPhone to send the CarPlay session to the **AAOS emulator's IP** (or eventually the head unit's IP).
- our own **AAOS app TCP listener** — receives the CarPlay-over-WiFi data channel after handoff. Reuses existing iAP2 message catalog (the message-level protocol is the same; only the transport changes from USB-bulk to TCP).

### Architecture (wireless)

```
┌─────────────┐                   ┌─────────────────────────────┐
│   iPhone    │                   │      CPC200 dongle          │
│             │                   │ (lean AP mode + our broker) │
│             │ ─── BT iAP2 ────► │   /tmp/oal-cp-broker        │
│             │  (RFCOMM ch 8)    │     ├── iAP2-over-RFCOMM    │
│             │ ◄─────────────── │     ├── MFi via daemon :5290 │
│             │                   │     └── injects target IP   │
│             │                   │            of AAOS app      │
│             │ ─── WiFi assoc ─► │   hostapd (AutoKit-XXXX)    │
│             │   to AP            │                             │
└──────┬──────┘                   └─────────────────────────────┘
       │
       │ ──── TCP :7000 ────►  ┌──────────────────────────────┐
       │  (CarPlay data       │      AAOS Emulator / Head    │
       │   channel:           │      Unit (our app)          │
       │   AirPlay video,     │   transport/carplay/wireless │
       │   audio, control)    │     ├── mDNS _carplay._tcp   │
       │                      │     ├── TCP listener :7000   │
       │                      │     └── iAP2 control session │
       │                      │   ▼                          │
       │                      │   video/ + audio/ + input/   │
       │                      │   (existing AAOS islands)    │
       │                      └──────────────────────────────┘
```

All three devices live on the CPC200's `192.168.43.x` subnet:
- CPC200 AP gateway: `192.168.43.1`
- iPhone (DHCP): observed `192.168.43.100`
- AAOS emulator host: observed `192.168.43.101`

### Component plan

| Component | Location | Status |
|-----------|----------|--------|
| CPC200 lean-mode neutralizer | `scripts/cpc200/deploy/lean-ap-mode.sh` + `Enter-LeanMode.ps1` | DONE |
| CPC200 MFi signing daemon | `scripts/mfi_auth_daemon.c` (port 5290) | DONE (from Part 2.4) |
| **CPC200 wireless broker binary** | `bridge/cpc200/oal-cp-broker/` | **NEW — this PR** |
| App: mDNS `_carplay._tcp` responder | `transport/carplay/wireless/CarPlayMdnsResponder.kt` | **NEW — this PR** |
| App: CarPlay TCP listener (port 7000) | `transport/carplay/wireless/CarPlayTcpListener.kt` | **NEW — this PR** |
| App: wireless probe orchestrator | `transport/carplay/wireless/CarPlayWirelessProbe.kt` | **NEW — this PR** |
| App: diagnostic screen | `ui/diagnostics/carplay/CarPlayWirelessProbeScreen.kt` | **NEW — this PR** |
| App: iAP2-over-TCP transport | `transport/carplay/wireless/Iap2TcpTransport.kt` | TODO (next PR) |
| App: AirPlay receiver (RTSP + RAOP) | `transport/carplay/wireless/airplay/` | TODO |
| App: CarPlay control protocol | reuse `Iap2Message.kt` catalog | TODO (wire to TCP transport) |

### CPC200 broker — high-level flow

```c
// bridge/cpc200/oal-cp-broker (C, static armhf, runs on CPC200)
main():
  open BT HCI
  register CarPlay SDP profile on RFCOMM ch 8
  hciconfig hci0 piscan          // be discoverable
  loop:
    accept RFCOMM connection from iPhone
    do iAP2 link-layer handshake (SYN/SYN-ACK/ACK)
    on RequestAuthCertificate:
      forward to mfi_auth_daemon @ 127.0.0.1:5290 GET_CERT, relay back
    on RequestAuthChallengeResponse:
      forward to mfi_auth_daemon SIGN, relay signed response back
    on RequestIdentification:
      send IdentificationInformation (model = OpenAutoLink, supports CarPlay)
    on RequestWiFiInformation:
      send our SSID + password (= CPC200 AP creds from /etc/wifi_name)
    on StartCarPlay:
      respond with target IP = AAOS_TARGET_IP (config), port 7000
      close RFCOMM — iPhone now switches to WiFi+TCP
    log everything to /tmp/oal-cp-broker.log with version stamp
```

Reference implementations to mine: `external/headunit-revived/` (HU Revived), `external/wireless-helper/`, and Carlinkit's own `AppleCarPlay`/`ARMiPhoneIAP2` binary strings (via `strings` on the binary in `/tmp/bin/`).

### App-side first deliverable (this PR — testable now)

A purely passive **wireless probe** in the app:
1. Advertises `_carplay._tcp` on port 7000 via `NsdManager`.
2. Listens on TCP 7000, logs the hex of any incoming bytes (since without the broker, the iPhone will not initiate CarPlay — but this confirms our listener is reachable and gives us a known-good baseline before plumbing the broker).
3. Surfaces a Compose diagnostic screen showing: mDNS service name, listen port, current local IP, live event log (connections, bytes, errors).

This is intentionally tiny. Its purpose is to (a) prove the listener-side plumbing on the AAOS emulator while connected to the CPC200 AP, and (b) be the home for the iAP2-over-TCP state machine in the next iteration.

### Next milestones

| Milestone | Deliverable |
|-----------|-------------|
| **W1** (this PR) | Lean AP + mDNS + TCP listener + diagnostic UI. Validate emulator reachable from iPhone on CPC200 subnet. |
| **W2** | CPC200 broker C scaffold compiling in WSL. Empty BT listener + RFCOMM accept. Verify iPhone BT-discovers it as a CarPlay accessory. |
| **W3-W4** | Broker iAP2 link layer + MFi relay. iPhone completes auth and joins our WiFi. |
| **W5-W6** | Broker StartCarPlay → AAOS emulator IP. iPhone opens TCP to our app. Log raw protocol bytes. |
| **W7-W10** | App: iAP2 control over TCP + AirPlay video receive + decode → MediaCodec → Surface. First frame. |
| **W11+** | Audio, touch, control sites, vehicle data forwarding (these all reuse existing islands). |

---

## Part 5: Pivot 2026-05-20 (evening) — Hybrid hijack first, from-scratch iAP2 later

### What we discovered on the CPC200

The dongle already ships a full closed-source CarPlay stack:

- `/usr/sbin/AppleCarPlay` (325 KB) — CarPlay orchestrator
- `/usr/sbin/ARMiPhoneIAP2` (181 KB) — iAP2 link layer
- `/usr/sbin/bluetoothDaemon`, `/usr/sbin/hcid`, `/usr/sbin/hciconfig`, `/usr/sbin/hcitool` — BlueZ-style BT stack
- `/usr/sbin/hfpd` — Handsfree profile
- `/usr/sbin/hwSecret` — wraps the on-board MFi co-processor
- `/usr/sbin/usbmuxd`, `/usr/sbin/mdnsd`
- Boot flow runs `/script/start_main_service.sh` → `init_bluetooth_wifi.sh` → `start_iap2_ncm.sh` → `boxNetworkService`

This means BT pairing, iAP2 SYN/ACK, MFi cert + challenge signing, and the WiFi handoff RoleSwitch are all already implemented on the box — by binaries we don't own.

### Decision

**Short term (this milestone): Path #1 — hybrid hijack.** Let the vendor binaries do the BT/iAP2/MFi dance. After RoleSwitch, the iPhone opens a TCP session to the vendor's CarPlay listener on the CPC200. We **transparently NAT that TCP stream** to the AAOS app's `:7000` (via `iptables -t nat … DNAT --to 192.168.43.101:7000` or userspace `socat`), and keep our own `_carplay._tcp` mDNS advert pointing at the AAOS IP so the iPhone never sees the vendor target. Goal: get raw CarPlay-over-TCP bytes into the AAOS app the fastest possible way, then start decoding them.

Trade-offs accepted: we depend on a closed binary, the vendor stack may also try to claim display/audio locally (we'll have to suppress or ignore), and the path won't survive a vendor firmware refresh.

### TODO (must do later): Path #3 — from-scratch iAP2 + MFi proxy

Replace the vendor stack entirely with our own broker. This is the only path that gives long-term control, allows us to ship without the Carlinkit dongle, and works on hardware we choose (e.g. a generic SBC + USB BT dongle + MFi chip).

Scope of the eventual replacement:
- Open `hci0` directly via BlueZ socket APIs; register CarPlay SDP record on RFCOMM ch 8.
- Implement iAP2 link layer (SYN / SYN-ACK / ACK / EAP packets / control session messages) — reference: `external/headunit-revived/`, `external/wireless-helper/`, and reverse-engineered iAP2 specs.
- Implement the MFi challenge flow against an MFi auth co-processor we control (I²C, same protocol as Apple's spec).
- Implement `RequestWiFiInformation` (give iPhone our AP creds) and `StartCarPlay` (point it at our AAOS app IP/port).
- Drop-in replace `AppleCarPlay` + `ARMiPhoneIAP2` on the CPC200, and also be portable to a non-CPC200 SBC.

Reasons we're not doing this now: weeks of work, MFi co-processor RE on the CPC200 board, and we'd be unable to validate end-to-end video/audio decoding until all of it lands. Hijack lets us get the data flowing and start working on the AAOS-side decoder in parallel.

### Hijack milestones (replaces W3-W6 above)

| Milestone | Deliverable |
|-----------|-------------|
| **H1** | Identify which TCP port `AppleCarPlay` listens on after iPhone BT-pair + MFi success. Capture with `netstat`/`ss` while the vendor stack is alive. |
| **H2** | Confirm vendor mDNS advert vs our own — leave only ours; verify iPhone still connects. |
| **H3** | Add an `iptables` PREROUTING DNAT (or `socat` userspace tee) on the CPC200 from the vendor port → `192.168.43.101:7000`. Verify bytes hit AAOS app's TCP listener. |
| **H4** | Hex-dump the first 4 KB of the post-MFi stream into a fixture file. Identify framing (AirPlay RTSP? raw iAP2? something else). |
| **H5** | Build initial parser in `transport/carplay/wireless/` for whatever framing H4 reveals. First decoded frame on the AAOS side. |

