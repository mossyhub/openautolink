# HFP Helper — Design

## Overview

ESP32-S3 firmware that:

- Acts as a BT HFP Hands-Free Unit to the user's phone (the phone is the
  Audio Gateway).
- Relays SCO PCM to/from an AAOS app over WiFi UDP.
- Is configured by the OpenAutoLink companion app via BLE on first run.

## State machine

```
                ┌───────────────┐
                │   BOOT        │
                └──────┬────────┘
                       │
              NVS has WiFi creds?
                ┌──────┴────────┐
                │ no            │ yes
                ▼               ▼
       ┌────────────────┐  ┌────────────────────┐
       │ PROVISIONING   │  │ WIFI_CONNECTING    │
       │ - BLE GATT     │  └──────┬─────────────┘
       │   server up    │         │
       │ - LED slow blink         │ connected
       └──────┬─────────┘         ▼
              │              ┌────────────────────┐
              │ creds saved  │ HFP_ADVERTISING    │
              ▼              │ - mDNS lookup app  │
       (reboot)              │ - BT discoverable  │
                             │ - LED solid        │
                             └──────┬─────────────┘
                                    │
                                    │ phone HFP-pairs + connects
                                    ▼
                             ┌────────────────────┐
                             │ HFP_CONNECTED      │
                             │ - SLC up           │
                             │ - waiting for SCO  │
                             └──────┬─────────────┘
                                    │
                                    │ phone opens SCO (call active)
                                    ▼
                             ┌────────────────────┐
                             │ AUDIO_ACTIVE       │
                             │ - SCO ↔ UDP relay  │
                             │ - LED fast blink   │
                             └────────────────────┘
```

All error paths (WiFi disconnect, BT disconnect, AAOS app unreachable) fall
back to a sensible "wait and retry" state without rebooting. A long-press of
the user button forces a re-provisioning cycle (wipes NVS WiFi creds).

## BLE provisioning protocol

We use ESP-IDF's `wifi_provisioning` component as the transport, but with a
custom payload format so we can also receive optional AAOS-app pinning data.

### Service UUID
`oal-hfp-prov` → `1f3e6e00-7d2a-4c1e-9c0c-0a1bfd7f5a01` (private OUI-derived)

### Characteristics
| UUID suffix | Direction | Format | Purpose |
|---|---|---|---|
| `…5a02` | Write | UTF-8 string ≤32 B | WiFi SSID |
| `…5a03` | Write | UTF-8 string ≤63 B | WiFi PSK |
| `…5a04` | Write | UTF-8 string ≤45 B (or empty) | AAOS app static IP (optional; mDNS used if empty) |
| `…5a05` | Read + Notify | uint8 | Provisioning state (`0=idle`, `1=connecting`, `2=connected`, `0xFE=bad_creds`, `0xFF=error`) |
| `…5a06` | Write | uint8 `1` | Commit (save NVS + reboot) |

All writes are encrypted using BLE LE Secure Connections with passkey
displayed on the helper's serial console (default `123456` for dev — to be
replaced by random per-boot passkey in v2).

### Companion app flow
1. Open BLE scan filtering on `oal-hfp-prov` service UUID.
2. Connect to advertised device named `OpenAutoLink Helper`.
3. Bond with displayed passkey.
4. Write SSID, PSK, optional static IP, in any order.
5. Subscribe to `…5a05` notifications.
6. Write `1` to `…5a06`.
7. Helper reboots into operating mode. Companion app closes the bond
   (or keeps it for later config writes; both fine).

## Audio protocol over WiFi

### Discovery
Helper publishes mDNS service:
```
_oal-hfp._udp.local     port 5278    TXT: v=1, dir=both, fmt=pcm16, sr=16000
```
AAOS app does mDNS discovery on `_oal-hfp._udp.local` and unicasts UDP to
that helper. If the user provisioned a static AAOS-app IP, the helper
unicasts to that IP and waits for the app to send a "hello" packet so it
knows the source port for the reverse direction.

### Packet format
```
struct oal_hfp_packet {
    uint8_t  magic[2];   // 'O','H'
    uint8_t  flags;      // bit 0: direction (0=helper→app, 1=app→helper)
                         // bit 1: codec (0=PCM16 LE 16k mono, 1=PCM16 LE 8k mono)
                         // bit 2: keepalive (no PCM payload)
                         // bits 3-7: reserved, must be 0
    uint8_t  seq;        // wraps 0..255
    uint16_t timestamp;  // ms since helper boot mod 65536
    uint8_t  reserved[2];
    int16_t  pcm[];      // 320 samples for 16k 20ms, 160 samples for 8k 20ms
};
```
Fixed 20 ms frame size matches SCO framing exactly. Total packet size:
- 16 kHz wideband (mSBC): 8 + 640 = 648 bytes — fits in any MTU.
- 8 kHz narrowband (CVSD): 8 + 320 = 328 bytes.

Keepalive packets every 1 s when no call active, so the AAOS app can detect
helper liveness.

### Sequence numbers
Receiver tracks `seq`, drops out-of-order packets older than 100 ms,
inserts silence for any packet not received within 30 ms of expected
arrival. No retransmit — call audio is live, dropped is dropped.

## Latency budget

| Hop | Approx | Notes |
|---|---|---|
| Phone → SCO over BT | 30 ms | One way, BT HFP standard |
| SCO socket → UDP send (helper) | 5 ms | One frame buffered |
| WiFi UDP | 5–15 ms | Depends on AP load |
| UDP receive → AudioTrack write | 30 ms | One frame buffered |
| AudioTrack → speakers | 20–50 ms | AAOS audio HAL |
| **Downlink total** | **~90–130 ms** | Acceptable |

Mic uplink mirrors this. Round-trip target < 250 ms — comfortably inside
the noticeable threshold for telephony.

## Pairing UX summary

For the user, the experience is:

1. **First time** (one-time, ~30 seconds):
   - Plug helper into car USB. Helper boots, blinks slowly.
   - Open OAL companion app on phone → Settings → "Set up HFP helper".
   - App finds helper, asks "Connect to car WiFi?" → enter SSID/PSK or
     pick from saved networks.
   - Helper reboots. LED goes solid → ready.

2. **First time, then again** (one-time, ~10 seconds):
   - Phone Settings → Bluetooth → scan → pair `OpenAutoLink Helper`.
   - Phone now treats helper as a BT headset.

3. **Every time after** (zero seconds):
   - Sit in car. Helper boots when USB power comes up. Auto-pairs to the
     car WiFi. Phone auto-pairs to the helper via BT.
   - Call comes in → audio plays through car speakers, mic captures from
     car.

## Future enhancements

- OTA from companion app over the provisioning BLE channel (reuse the
  GATT bond).
- Per-boot pairing passkey display on the AAOS app instead of fixed
  `123456`, transferred via the OAL TCP control channel before provisioning.
- mSBC wideband audio negotiation (currently planning CVSD 8 kHz as the
  guaranteed-compatible fallback; mSBC is preferred when both sides
  support it).
- Multi-phone support: helper remembers up to N paired phones, auto-routes
  to whichever is currently in HFP-connected state.
