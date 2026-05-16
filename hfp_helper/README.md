# HFP Helper — TinyS3 (ESP32-S3)

Small Bluetooth Hands-Free helper that lets phone call audio reach the car
speakers and lets the car mic reach the phone, **without** putting BT HFP on
the phone-to-car BT pairing.

## Why this exists

Phone-side Android Auto deliberately refuses to route call audio over the AA
protocol (see [docs/aa-undocumented-features.md](../docs/aa-undocumented-features.md)).
On AAOS, intercepting BT SCO call audio from a third-party app is blocked by
`BLUETOOTH_PRIVILEGED` / signature-level permissions. The only practical
hardware-free workaround is to enable BT call audio on the phone↔car pairing,
which causes the OEM dialer to take the screen during the call.

This helper sidesteps both by being a **separate BT device** that the phone
treats as its hands-free headset. Audio enters and leaves the helper over BT,
then crosses to the AAOS app over WiFi as ordinary PCM.

```
Phone ──BT HFP/SCO──▶ TinyS3 (this firmware)
                       │ ─── WiFi UDP ───▶ AAOS OAL app ──▶ AudioTrack ──▶ car speakers
                       │ ◀── WiFi UDP ─── AAOS OAL app ◀── AudioRecord ◀── car mic
```

## Hardware

| Item | Notes |
|---|---|
| **TinyS3** (Unexpected Maker, ESP32-S3FN8) | 8 MB flash, 8 MB PSRAM, native USB-C, BT 5.0 BR/EDR + LE, WiFi b/g/n |
| USB-C cable for power + flash | Any USB-C with data lines |
| Optional LiPo battery | If you want it to live in the glovebox |

## How it works end to end

1. **First-time provisioning (one-time)**
   - Helper boots in provisioning mode if no WiFi creds in NVS.
   - Advertises a BLE service `OpenAutoLink Helper Setup` (custom UUID — see
     [docs/design.md](docs/design.md#ble-provisioning-protocol)).
   - User opens the OpenAutoLink **companion app** on the phone → Settings →
     "Pair HFP helper".
   - Companion app discovers the BLE service, writes WiFi SSID + password
     and (optionally) a static AAOS-app IP to provisioning characteristics.
   - Helper persists to NVS flash, reboots, exits provisioning mode.

2. **Operating mode (every subsequent boot)**
   - Helper connects to the saved WiFi.
   - Discovers the AAOS app via mDNS (`_oal-hfp._udp.local`) or falls back to
     the static IP if one was saved.
   - Enables BT Classic, advertises as `OpenAutoLink Helper` with the HFP
     Hands-Free profile.
   - User pairs **once** in the phone's normal Bluetooth settings, the same
     way they'd pair any BT headset.

3. **During a phone call**
   - Phone opens an SCO link to the helper for the call audio.
   - Helper reads SCO PCM and forwards 20 ms frames over WiFi UDP to the
     AAOS app.
   - AAOS app plays PCM via `AudioTrack` with `USAGE_VOICE_COMMUNICATION`
     attributes — comes out the car speakers via the standard call audio bus.
   - AAOS app captures the car mic via `AudioRecord` (`VOICE_RECOGNITION`)
     and streams it back to the helper over UDP.
   - Helper writes that PCM onto the SCO socket — phone hears the user.

## Crucially, what this helper does NOT do

- It is **not** an Android Auto head unit. AA still runs in the car app over
  WiFi from your real phone to your real head unit. The helper is only for
  call audio.
- It is **not** an HFP Audio Gateway. We're the **Hands-Free** role; the
  phone is the gateway.
- It does **not** talk to GM AAOS at any privileged level. From the head
  unit's perspective it's a normal app receiving UDP audio.

## Status

- [ ] Milestone 1 — BT HFP pair + SCO open + PCM on serial
- [ ] Milestone 2 — BLE WiFi provisioning
- [ ] Milestone 3 — WiFi UDP relay (downlink: phone → helper → app)
- [ ] Milestone 4 — WiFi UDP relay (uplink: app → helper → phone)
- [ ] Milestone 5 — AAOS app integration + AudioTrack routing
- [ ] Milestone 6 — Robust reconnect: BT drops, WiFi drops, AAOS app restarts
- [ ] Milestone 7 — mDNS discovery + companion-app pairing UX
- [ ] Milestone 8 — OTA from companion app

## Build & flash (Windows)

Prerequisite: install [ESP-IDF v5.2+](https://docs.espressif.com/projects/esp-idf/en/latest/esp32s3/get-started/windows-setup.html)
via the official Windows installer. After install, every new shell needs
`%USERPROFILE%\esp\esp-idf\export.ps1` sourced.

```powershell
# From repo root:
.\hfp_helper\scripts\build.ps1
.\hfp_helper\scripts\flash.ps1 COM5    # whatever COM port the TinyS3 mounts on
.\hfp_helper\scripts\monitor.ps1 COM5  # serial console
```

Press the `RESET` button on the TinyS3 after `flash.ps1` to run the new image.

## Layout

```
hfp_helper/
├── README.md
├── firmware/
│   ├── CMakeLists.txt               # ESP-IDF top-level
│   ├── sdkconfig.defaults           # canonical project config
│   ├── partitions.csv               # OTA-friendly partition table
│   └── main/
│       ├── CMakeLists.txt
│       ├── main.c                   # boot, init order
│       ├── hfp_client.c / .h        # BT HFP HF — pair, SLC, SCO
│       ├── audio_relay.c / .h       # ring buffer between SCO and UDP
│       ├── transport_wifi.c / .h    # UDP relay to AAOS app
│       └── provisioning.c / .h      # BLE WiFi provisioning
├── scripts/
│   ├── build.ps1
│   ├── flash.ps1
│   └── monitor.ps1
└── docs/
    └── design.md
```
