# Wireless CarPlay on OpenAutoLink — Implementation Plan

> **Status**: Active implementation — see [work-plan.md](../work-plan.md) for milestone tracking.
> **Last updated**: April 2026 (codebase audit + architecture review)

## Executive Summary

Wireless CarPlay can be added to OpenAutoLink with **zero risk to the existing Android Auto implementation**. The OAL protocol architecture already isolates the phone-side protocol (aasdk/AA) from the car-side protocol (OAL over TCP). CarPlay slots in as an alternative phone-side session, feeding the same `OalSession` that AA uses. The car app never knows the difference.

No compile-time flags needed. Always compiled, runtime-activated via a single env var. One binary, one APK.

---

## Key Discovery: No MFi Chip Required

**Wireless CarPlay does NOT require an Apple MFi authentication chip.** The MFi chip is only needed for **wired** (USB iAP2) CarPlay. Wireless CarPlay uses **HomeKit Pairing v2** — a software-only protocol based on open, well-documented cryptography (SRP-6a, X25519, ChaCha20-Poly1305).

This means we can implement a wireless CarPlay receiver on the SBC bridge using the same WiFi + BT infrastructure already in place for Android Auto.

---

## Architecture: Why This Is Safe

### The Isolation Boundary Already Exists

The bridge has a clean split between "phone-side session" and "car-side protocol":

```
Phone Protocol (aasdk / CarPlay)          Car Protocol (OAL — unchanged)
┌──────────────────────────┐             ┌──────────────────────────────┐
│ LiveAasdkSession         │──writes──►  │ OalSession                   │
│   aasdk v1.6 callbacks   │             │   write_video_frame()        │
│   AA service handlers    │             │   write_audio_frame()        │
│   H.264/H.265/VP9/AV1   │             │   send_phone_connected()     │
└──────────────────────────┘             │   send_nav_state()           │
                                         │   ...                        │
┌──────────────────────────┐             │                              │
│ CarPlaySession  (NEW)    │──writes──►  │ (same interface, same queues,│
│   RTSP + AirPlay         │             │  same TCP flush threads)     │
│   HomeKit crypto         │             └──────────┬───────────────────┘
│   H.264 only             │                        │ OAL protocol
└──────────────────────────┘                        │ (JSON + binary frames)
                                         ┌──────────▼───────────────────┐
┌──────────────────────────┐             │ TcpCarTransport              │
│ OalMockSession (existing)│──writes──►  │   control :5288              │
│   synthetic test data    │             │   audio   :5289              │
└──────────────────────────┘             │   video   :5290              │
                                         └──────────┬───────────────────┘
                                                    │ TCP/Ethernet
                                         ┌──────────▼───────────────────┐
                                         │ AAOS Car App (Kotlin)        │
                                         │   Speaks OAL only            │
                                         │   Doesn't know AA vs CarPlay│
                                         └─────────────────────────────┘
```

`OalSession` already doesn't know or care what phone protocol feeds it. It accepts raw codec frames via `write_video_frame()` and raw PCM via `write_audio_frame()`. CarPlay frames go through the exact same calls. The car app receives identical OAL-framed data either way.

### Codebase Audit Results

The app was audited at the file level (68 Kotlin files). Protocol awareness breakdown:

| App Component | Files | AA-Specific? | CarPlay Impact |
|---------------|-------|-------------|----------------|
| Transport (TCP, OAL framing) | 9 | 0% | **No changes** |
| Video (MediaCodec, NalParser) | 5 | 0% | **No changes** |
| Audio (5-slot AudioTrack, ring buffer) | 9 | 0% | **No changes** |
| Input (touch, GNSS, IMU, VHAL) | 6 | VHAL only | **No changes** |
| Session orchestrator | 3 | 0% | **No changes** |
| Navigation | 5 | Cluster only | Cluster is AA-specific, rest generic |
| Cluster (`androidx.car.app.*`) | 5 | 100% | AA-only, fine to keep |
| Media session | 2 | 80% | AAOS integration, fine to keep |
| UI, diagnostics, update, data | 18 | 30% | Minor additions (settings, PIN screen) |
| **Overall** | **68** | **~20%** | **~80% protocol-agnostic** |

The bridge was audited at the file level (10 C++ source files). The only AA-specific code is `live_session.cpp` (the aasdk service handlers). Everything downstream — `OalSession`, `TcpCarTransport`, `OalProtocol` — is phone-protocol-agnostic.

---

## Phone Protocol Comparison: AA vs CarPlay

### Connection Flow

```
Android Auto (existing):                    CarPlay (new):
1. BT BR/EDR pair (AA UUID, ch 8)          1. BT BR/EDR pair (iAP UUID, ch 1)
2. RFCOMM: WiFi credential exchange         2. RFCOMM: WiFi credential exchange
3. Phone joins WiFi AP (hostapd)            3. Phone joins WiFi AP (same hostapd)
4. Phone → TCP:5277 (aasdk)                4. Bonjour: _carplay._tcp + _airplay._tcp
5. TLS handshake (aasdk)                    5. Phone → TCP:5000 (RTSP control)
6. Protobuf service discovery               6. HomeKit pair-setup (first time, PIN)
7. Video/audio channels established         7. Pair-verify (subsequent, automatic)
8. H.264/H.265/VP9/AV1 stream              8. Encrypted AirPlay streams:
                                               - Video (H.264) TCP:7000
                                               - Audio (ALAC/AAC/PCM) TCP:7001
                                               - Control (RTSP) TCP:5000
```

### Port/UUID Conflict Analysis

| Layer | Android Auto | CarPlay | Conflict? |
|-------|-------------|---------|-----------|
| BT UUID | `4de17a00-52cb-11e6-bdf4-0800200c9a66` | `00000000-deca-fade-deca-deafdecacafe` | **No** — separate profiles |
| BT RFCOMM | Channel 8 | Channel 1 | **No** — different channels |
| BLE advert | AA service UUID | CarPlay service UUID | **No** — both in same advert array |
| WiFi AP | hostapd 5GHz | Same AP | **Shared** — both phone types join same net |
| Phone → SBC TCP | 5277 (aasdk) | 5000 (RTSP) | **No** — different ports |
| SBC → Car TCP | 5288/5289/5290 (OAL) | Same OAL ports | **Shared** — car app is unaware |

**Nothing conflicts.** Both protocol stacks can listen simultaneously.

---

## CarPlay Protocol Stack (Detail)

```
iPhone
  │
  ├── 1. Bluetooth BR/EDR pairing → bridge advertises as CarPlay accessory
  ├── 2. WiFi credentials exchange → bridge provides WiFi AP details
  ├── 3. iPhone joins WiFi AP → same AP used for AA (192.168.43.x)
  ├── 4. Bonjour discovery → bridge advertises _carplay._tcp + _airplay._tcp
  ├── 5. RTSP control session → TCP port 5000
  ├── 6. HomeKit Pairing v2 (SRP-6a) → first-time pairing with PIN
  ├── 7. Pair-Verify (X25519 + HKDF) → subsequent reconnections
  └── 8. Encrypted AirPlay streams:
       ├── Video (H.264) → TCP port 7000
       ├── Audio (ALAC/AAC/PCM) → TCP port 7001
       └── Control (RTSP) → TCP port 5000
```

### Cryptography Required (All Open Standard)

| Algorithm | Purpose | Library |
|-----------|---------|---------|
| SRP-6a (3072-bit, SHA-512) | First-time pairing (like HomeKit PIN) | OpenSSL 3.0 (on SBC) |
| X25519 (Curve25519) | Key exchange for pair-verify | OpenSSL 3.0 |
| ChaCha20-Poly1305 | Transport encryption | OpenSSL 3.0 |
| AES-256-GCM | Alternative transport encryption | OpenSSL 3.0 |
| HKDF-SHA-512 | Key derivation | OpenSSL 3.0 |
| Ed25519 | Long-term identity signing | OpenSSL 3.0 |

All available in OpenSSL 3.0 already on the SBC. No new crypto dependencies.

### Key Derivation Labels

```
Control-Read-Encryption-Key     — decrypt control channel from phone
Control-Write-Encryption-Key    — encrypt control channel to phone
DataStream-Output-Encryption-Key — encrypt media/data to phone
DataStream-Input-Encryption-Key  — decrypt media/data from phone
Events-Read-Encryption-Key      — decrypt event channel from phone
Events-Write-Encryption-Key     — encrypt event channel to phone
Pair-Verify-ECDH-Salt / Info    — HKDF params for pair-verify M1
Pair-Verify-Encrypt-Salt / Info — HKDF params for pair-verify M3
```

### Bonjour Service Records

```
_carplay._tcp    port 5000    — CarPlay RTSP control
_airplay._tcp    port 7000    — AirPlay media streams
```

TXT record keys for `_airplay._tcp` include device model, features bitmap, protocol version. The features bitmap is a ~64-bit field — wrong bits = iPhone silently refuses to connect.

### Bluetooth SDP Record

```
Service: Wireless iAP
UUID: 00000000-deca-fade-deca-deafdecacafe
RFCOMM Channel: 1
```

Different from AA UUID (`4de17a00-52cb-...` on channel 8). Both coexist as separate BlueZ profile registrations.

---

## Feature-Flag Design: Runtime Only, No Compile Gates

### Why No Compile-Time Flag

The existing `#ifdef PI_AA_ENABLE_AASDK_LIVE` exists because aasdk drags in heavy dependencies (Boost, libusb, protobuf) that aren't available on every build machine. CarPlay's dependencies are much lighter:

- **OpenSSL** — already required by aasdk (already linked)
- **avahi-client** — already installed on the SBC (already there)
- No Boost, no protobuf, no libusb

CarPlay code compiles everywhere the aasdk-live binary already compiles. The binary size increase is ~50-100KB. Always compile both, gate at runtime.

### Runtime Activation

Single env var in `/etc/openautolink.env`:

```bash
# Values: android-auto (default), carplay, auto
OAL_PHONE_PROTOCOL=android-auto
```

In `run-openautolink.sh`, mapped to `--session-mode`:

```bash
case "${OAL_PHONE_PROTOCOL:-android-auto}" in
    android-auto) ARGS+=(--session-mode=aasdk-live) ;;
    carplay)      ARGS+=(--session-mode=carplay-live) ;;
    auto)         ARGS+=(--session-mode=auto) ;;
esac
```

In `main.cpp` — no `#ifdef`, just another `else if` branch:

```cpp
if (session_mode == SessionMode::AasdkLive) {
    // Existing AA path — completely untouched
    auto live = std::make_unique<LiveAasdkSession>(...);
    live->set_oal_session(&oal);
    ...
} else if (session_mode == SessionMode::CarPlayLive) {
    // New CarPlay path — only entered when env says so
    auto carplay = std::make_unique<CarPlaySession>(oal, config);
    carplay->start();
    ...
} else if (session_mode == SessionMode::Auto) {
    // Both listeners, first phone to connect wins
    ...
}
```

When `OAL_PHONE_PROTOCOL=android-auto` (default), the CarPlay code path is never entered. Dead code at runtime — zero risk to AA.

### Session Mode Enum

New values added to the existing `SessionMode` enum in `session.hpp`:

```cpp
enum class SessionMode {
    Stub,              // existing
    AasdkPlaceholder,  // existing
    AasdkLive,         // existing — Android Auto
    OalMock,           // existing — test mode
    CarPlayLive,       // NEW — CarPlay only
    Auto,              // NEW — both, first phone wins
};
```

---

## Bridge Implementation

### New Source Files (All New — No Existing Files Modified Except main.cpp)

```
bridge/openautolink/headless/
├── include/openautolink/
│   ├── carplay_session.hpp        — CarPlay session manager (owns RTSP, crypto, AirPlay)
│   ├── carplay_rtsp.hpp           — RTSP server (port 5000)
│   ├── carplay_crypto.hpp         — SRP-6a, X25519, ChaCha20, HKDF wrappers
│   └── carplay_bonjour.hpp        — Avahi mDNS service advertisement
├── src/
│   ├── carplay_session.cpp        — ~800 lines: lifecycle, OalSession integration
│   ├── carplay_rtsp.cpp           — ~600 lines: RTSP parser + response builder
│   ├── carplay_crypto.cpp         — ~700 lines: HomeKit pair-setup/verify state machines
│   ├── carplay_airplay.cpp        — ~800 lines: AirPlay stream decrypt + deframe
│   └── carplay_bonjour.cpp        — ~200 lines: avahi-client wrapper
```

**Total: ~3,100 lines of new C++, ~5 new source files.**

### Existing Files Modified

| File | Change | Impact on AA |
|------|--------|-------------|
| `main.cpp` | New `else if` branches for `CarPlayLive` and `Auto` modes | Zero — existing branches untouched |
| `session.hpp` | Two new values in `SessionMode` enum | Zero — existing values unchanged |
| `CMakeLists.txt` | Add new source files, link avahi-client | Zero — no AA sources touched |

### How CarPlaySession Feeds OalSession

`CarPlaySession` calls the exact same `OalSession` methods that `LiveAasdkSession` uses:

```
CarPlaySession                              OalSession (unchanged)
─────────────                               ────────────────────
AirPlay H.264 frame received                
  → decrypt with session key                
  → strip AirPlay framing                   
  → call oal.write_video_frame(             
      width, height, pts_ms,                → builds OAL 16-byte header
      flags, codec_data, size)              → enqueues to video_writes_ deque
                                            → TCP:5290 flush thread sends to app

AirPlay audio frame received                
  → decrypt                                 
  → decode ALAC/AAC → PCM (if needed)       
  → call oal.write_audio_frame(             
      pcm, size, purpose,                   → builds OAL 8-byte header
      sample_rate, channels)                → enqueues to audio_writes_ deque
                                            → TCP:5289 flush thread sends to app

iPhone connects                              
  → call oal.on_phone_connected(            
      "iPhone", "iphone")                   → sends JSON: {"type":"phone_connected",
                                                            "name":"iPhone",
                                                            "phone_type":"iphone"}
                                            → app shows "CarPlay" in UI

First-time pairing PIN                       
  → call oal.send_control_line(             
      {"type":"carplay_pin","pin":"4829"})  → app shows PIN entry screen
```

### Reusable Existing Infrastructure

| Component | Already Have | Reuse For CarPlay |
|-----------|-------------|-------------------|
| WiFi AP (hostapd) | ✅ 5GHz 802.11ac | Same AP, same subnet |
| BT advertising (BlueZ) | ✅ BLE + BR/EDR | Add CarPlay SDP + UUID alongside AA |
| mDNS (avahi) | ✅ `_openautolink._tcp` | Add `_carplay._tcp` + `_airplay._tcp` |
| OalSession | ✅ Video/audio queues, TCP flush | Same interface, same code |
| TcpCarTransport | ✅ 3 TCP channels | Same channels, same OAL framing |
| Touch forwarding | ✅ OAL touch messages | Bridge converts HID ↔ OAL touch format |
| Video pipeline | ✅ H.264 decode path | Same codec (CarPlay = H.264 only) |
| Audio pipeline | ✅ PCM/48kHz AudioTrack | Same PCM format after bridge decodes ALAC/AAC |
| SCO audio | ✅ BT HFP phone calls | iPhone HFP works the same way |

### New Dependencies on SBC

| Dependency | Already Installed? | Purpose |
|------------|-------------------|---------|
| OpenSSL 3.0 | ✅ Yes (for aasdk) | All CarPlay crypto |
| avahi-client | ✅ Yes (for OAL discovery) | Bonjour `_carplay._tcp` |
| libavcodec (ffmpeg) | ❌ Need to install | ALAC/AAC → PCM decode |

`libavcodec` is the only new system package. It's needed because CarPlay audio arrives as ALAC or AAC-LC, not raw PCM. The bridge must decode to PCM before forwarding via OAL. Alternative: use Apple's open-source ALAC reference decoder (smaller footprint, but AAC still needs libavcodec or similar).

---

## Bluetooth Script Changes (`aa_bt_all.py`)

The existing script is already a multi-profile registrar (AA + HSP + HFP + BLE). CarPlay adds one more profile, gated by the env var:

```python
# Existing (untouched):
AA_UUID = "4de17a00-52cb-11e6-bdf4-0800200c9a66"  # channel 8

# New (only when protocol includes carplay):
CARPLAY_UUID = "00000000-deca-fade-deca-deafdecacafe"  # channel 1

phone_protocol = os.environ.get("OAL_PHONE_PROTOCOL", "android-auto")

if phone_protocol in ("carplay", "auto"):
    cp = CarPlayProfile(bus, "/pi_aa/carplay")
    pm.RegisterProfile("/pi_aa/carplay", CARPLAY_UUID, {
        "Name": "Wireless iAP", "Role": "server",
        "Channel": dbus.UInt16(1),
        "ServiceRecord": carplay_sdp_xml
    })
```

The `BLEAd` class adds the CarPlay UUID alongside the AA UUID in `ServiceUUIDs`:

```python
service_uuids = [AA_UUID]
if phone_protocol in ("carplay", "auto"):
    service_uuids.append(CARPLAY_UUID)
```

The existing AA profile, HSP, HFP, and BLE all continue working. The agent auto-accepts pairing for both phone types.

### Auto-Connect on Boot

The existing `do_connect()` function tries `HFP_AG` → `HSP_AG` → generic `Connect()` for paired devices. For CarPlay, the same flow applies — when an iPhone pairs via CarPlay UUID, BlueZ remembers it. On next boot, `Connect()` triggers the iPhone to initiate WiFi + CarPlay automatically.

---

## App Changes (Minimal)

### What Changes

| Change | Type | Gated By |
|--------|------|----------|
| CarPlay settings section in SettingsScreen | New UI section | `bridgeCapabilities.carplaySupported` from bridge `hello` |
| CarPlay PIN entry screen | New Composable | Bridge sends `{"type":"carplay_pin","pin":"..."}` |
| New `ControlMessage` subtypes | Sealed class entries | Never instantiated unless bridge sends them |
| Phone type display | Already works | `phone_connected.phone_type` = `"iphone"` vs `"android"` |

### What DOESN'T Change

- Transport layer (TCP, OAL framing) — 0% change
- Video decoder (MediaCodec, NalParser) — 0% change
- Audio player (5-slot AudioTrack, ring buffer) — 0% change
- Input forwarding (touch, GNSS, IMU) — 0% change
- Session orchestrator — 0% change
- Cluster service — AA-only, stays AA-only
- VHAL monitoring — AAOS-only, stays AAOS-only

### How It's Gated in Kotlin

No compile-time flags in Kotlin. All runtime:

```kotlin
// Bridge hello message includes capabilities
data class BridgeHello(
    val version: Int,
    val name: String,
    val carplaySupported: Boolean = false,  // NEW — default false
    ...
)

// Settings UI — section only visible when bridge reports CarPlay
if (bridgeCapabilities.carplaySupported) {
    CarPlaySettingsSection(...)
}

// PIN screen — only navigated when bridge sends carplay_pin message
when (message) {
    is ControlMessage.CarPlayPin -> navController.navigate("carplay_pin/${message.pin}")
    // ... all existing message handlers untouched
}
```

If the bridge is in AA mode, it never sends `carplaySupported=true` or `carplay_pin` messages. The new code paths never execute.

---

## Auto Mode: Both Phones Welcome

When `OAL_PHONE_PROTOCOL=auto`:

```
Bridge boots
  │
  ├── BT: registers AA profile (ch 8) + CarPlay profile (ch 1)
  ├── BLE: advertises both UUIDs
  ├── WiFi: hostapd starts (same AP for both)
  ├── AA listener: TCP:5277 waiting for aasdk connection
  ├── CarPlay listener: RTSP:5000 + Bonjour waiting for iPhone
  │
  ├─── Android phone pairs on AA UUID ──►
  │    RFCOMM WiFi exchange → phone joins AP → TCP:5277 → aasdk session
  │    atomic<PhoneProtocol> = AndroidAuto
  │    CarPlay listener stays up but won't activate
  │
  └─── iPhone pairs on CarPlay UUID ──►
       RFCOMM exchange → phone joins AP → RTSP:5000 → CarPlay session
       atomic<PhoneProtocol> = CarPlay
       AA listener stays up but won't activate
```

Only one phone at a time (one screen, one video stream). On disconnect, the `atomic` resets to `None` and both listeners accept again. The user experience is transparent — connect whichever phone you have.

If both phones try simultaneously (rare), first TCP handshake to complete wins. The other gets connection refused until the active session ends.

---

## Implementation Phases

### Phase 1: Discovery (~400 lines, Low Risk)

**Goal**: iPhone sees "OpenAutoLink" in Settings → General → CarPlay.

1. Add `carplay_bonjour.cpp` — advertise `_carplay._tcp` and `_airplay._tcp` via avahi-client
2. Add basic `carplay_rtsp.cpp` — listen on TCP:5000, handle RTSP OPTIONS/DESCRIBE
3. Add `CarPlaySession` skeleton that starts Bonjour + RTSP
4. Add `--session-mode=carplay-live` to `main.cpp`
5. Add `CarPlayProfile` to `aa_bt_all.py` (BT SDP + BLE UUID)
6. Add `OAL_PHONE_PROTOCOL` to `openautolink.env`

**Validation**: Set `OAL_PHONE_PROTOCOL=carplay`, reboot SBC, check iPhone Settings → CarPlay. If "OpenAutoLink" appears, architecture is proven.

**Risk to AA**: Zero. Default env is `android-auto`. New code only runs when explicitly enabled.

**Testing hardware**: iPhone 15/16 series, iOS 17+.

### Phase 2: HomeKit Pairing (~1,500 lines, Medium-Hard)

**Goal**: iPhone successfully pairs and shows "Connected" in CarPlay settings.

1. Implement SRP-6a pair-setup in `carplay_crypto.cpp`
   - Bridge generates random 4-digit PIN → sends to car app via OAL control
   - Car app displays PIN screen → user enters on iPhone
   - SRP-6a exchange completes → long-term public key (LTPK) stored on bridge
2. Implement pair-verify for subsequent connections (X25519 + HKDF)
   - Uses stored LTPK — no PIN needed after first pairing
3. Establish encrypted control channel (ChaCha20-Poly1305)
4. Add PIN entry screen to car app (Compose)

**Reference implementations**:
- [pyatv](https://github.com/postlund/pyatv) — Apple TV protocol with pair-setup/verify (Python, well-documented)
- [hap-nodejs](https://github.com/homebridge/HAP-nodejs) — HomeKit protocol (Node.js)
- [airplay2-receiver](https://github.com/openairplay/airplay2-receiver) — AirPlay 2 receiver (Python)

**Validation**: iPhone completes pairing, reconnects automatically on subsequent connections.

**Why this is hard**: The crypto primitives are straightforward (OpenSSL has everything), but Apple's state machine has precise byte-level requirements. Every SRP-6a message step, every HKDF label, every TLV encoding must be exact. Off-by-one in a salt label = silent failure, no error message. Needs iterative debugging with the real iPhone.

### Phase 3: AirPlay Streams (~1,200 lines, Hard)

**Goal**: CarPlay UI renders on car display, audio plays.

1. Accept AirPlay video stream (H.264) on TCP:7000
2. Accept AirPlay audio stream (ALAC/AAC-LC/PCM) on TCP:7001
3. Decrypt streams using session keys from pair-verify
4. Strip AirPlay framing, extract raw H.264 NAL units
5. Decode ALAC/AAC audio to PCM (via libavcodec)
6. Forward video frames to car app via `oal.write_video_frame()` (same as AA path)
7. Forward audio frames to car app via `oal.write_audio_frame()` (same as AA path)

**Validation**: CarPlay home screen renders on car display. Audio plays through car speakers.

**Why this is the hardest phase**: AirPlay framing is reverse-engineered, not officially documented. The stream format details are scattered across open-source receiver projects. Audio requires a new dependency (libavcodec) for ALAC/AAC→PCM decoding. Every open-source CarPlay/AirPlay receiver has spent the most debugging time here.

### Phase 4: Input — Touch + HID (~300 lines, Easy-Medium)

**Goal**: Touch interaction and Siri work on CarPlay UI.

1. Receive OAL touch messages from car app (existing path)
2. Convert to CarPlay HID touch reports (USB HID digitizer format)
3. Send via encrypted control channel to iPhone
4. Handle Siri button, home button via HID key events

**Validation**: Can navigate CarPlay UI by touch. Siri activates via button.

### Phase 5: Auto Mode (~200 lines, Easy)

**Goal**: Bridge accepts whatever phone connects, no env var change needed.

1. Both AA and CarPlay listeners start simultaneously
2. `std::atomic<PhoneProtocol>` tracks active session (`None`, `AndroidAuto`, `CarPlay`)
3. First phone to complete handshake wins
4. On disconnect, reset to `None`, resume both listeners

**Validation**: Pair Android phone → AA works. Unpair, pair iPhone → CarPlay works. No SBC reconfiguration.

---

## Difficulty Assessment

| Phase | New Code | Difficulty | Can Be Written Confidently Without Hardware? | Needs iPhone Testing? |
|-------|----------|------------|----------------------------------------------|----------------------|
| 1. Discovery | ~400 lines | Easy | Yes — standard protocols | Yes — validate iPhone sees us |
| 2. Pairing | ~1,500 lines | Medium-Hard | Mostly — crypto is deterministic | Yes — iterative debugging |
| 3. Streams | ~1,200 lines | Hard | Partially — framing needs real-device validation | Yes — critical |
| 4. Input | ~300 lines | Easy-Medium | Yes — HID spec is well-defined | Yes — validate touch works |
| 5. Auto Mode | ~200 lines | Easy | Yes — just control flow | Yes — validate both phones |

**Total: ~3,600 lines of new C++** plus ~50 lines of Python and ~200 lines of Kotlin.

**Estimated effort**: 4-6 focused coding sessions with iterative iPhone testing between each phase.

---

## Key Risks

1. **Apple protocol changes** — Apple occasionally updates the AirPlay/CarPlay protocol. Future iOS updates may break compatibility. Mitigation: this is a known risk shared by every open-source CarPlay receiver (e.g., Tesla's aftermarket CarPlay adapter, various Chinese head units).

2. **Feature bitmap** — The `_airplay._tcp` TXT record includes a features bitmap (~64-bit) that tells the iPhone what capabilities the receiver supports. Getting this wrong causes **silent** connection failures — the iPhone just doesn't show the device. Mitigation: start with known-working bitmaps from reference implementations.

3. **Audio transcoding** — CarPlay sends ALAC or AAC-LC audio, not raw PCM. The bridge must decode to PCM before forwarding via OAL. This adds a dependency (libavcodec / ffmpeg) and a small CPU cost on the SBC. Mitigation: ALAC is lossless and simple to decode; Apple's reference decoder is open-source.

4. **DRM/FairPlay** — Some AirPlay content uses FairPlay Streaming. CarPlay screen mirroring does NOT use FairPlay (it's raw H.264), but some audio streams might. Mitigation: CarPlay projection audio (the UI sounds, Maps guidance, etc.) is unprotected. Only DRM music playback might be affected, and that's not the primary use case.

5. **Timing** — AirPlay uses NTP-based timestamps for A/V sync. The existing OAL `pts_ms` field in the video header should suffice, but may need adjustment. Mitigation: same timing model as AA — pass through timestamps, let the app handle sync.

6. **No aasdk equivalent** — For AA, aasdk does the heavy lifting (TLS, protobuf, channel mux). For CarPlay, we're building the protocol handler from scratch using reference implementations. This means more code to maintain and more surface area for bugs.

---

## Risk to Existing AA Users

| Scenario | Risk |
|----------|------|
| Default env (`OAL_PHONE_PROTOCOL=android-auto`) | **Zero** — CarPlay code never executes |
| Bridge binary size | **Negligible** — ~50-100KB larger |
| Build time | **Negligible** — 5 extra source files |
| AA session stability | **Zero** — AA code path is untouched, no shared mutable state |
| App behavior | **Zero** — new control messages ignored if bridge doesn't send them |
| SBC boot time | **Zero** — no new services, just a longer BLE advertisement |

---

## Recommendation

Start with **Phase 1 (Discovery)** as a go/no-go validation gate. It's ~400 lines, low risk, and the success criterion is binary: does the iPhone show "OpenAutoLink" in CarPlay settings? If yes, the architecture and Bonjour/RTSP plumbing are proven and we proceed. If no, we debug TXT records and feature bitmaps before investing in crypto and streaming code.

Phase 1 can be tested with any iPhone running iOS 16+ (2022 or newer).
