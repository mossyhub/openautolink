# OpenAutoLink — Open-Source Wireless Android Auto Bridge

An open-source wireless Android Auto adapter that runs on any Linux SBC
with WiFi and Bluetooth. A fully open-source wireless Android Auto bridge.

## How It Works

```
Phone (any Android with AA)
  ↕ WiFi + Bluetooth
SBC (Raspberry Pi, ROCK, etc.)
  ↕ TCP/IP over Ethernet
Car Head Unit (AAOS app)
```

1. **Phone** connects to the SBC — either wirelessly (BT pair + WiFi) or wired (USB cable)
2. **SBC** runs the Android Auto protocol (aasdk v1.6) over TCP or USB
3. **Car app** connects to the SBC over Ethernet and renders video/audio

## Phone Connection Modes

### Wireless (default)
Phone pairs via Bluetooth, joins the SBC's WiFi AP, and connects over TCP.
Requires a WiFi module on the SBC.

### Wired (USB Host)
Phone plugs into the SBC's USB host port with a USB cable. The SBC uses
libusb to switch the phone to AOA (Android Open Accessory) mode and runs
Android Auto over USB transport. No WiFi or Bluetooth needed.

Useful for SBCs without WiFi modules, or for lowest-latency connections.

Set in `/etc/openautolink.env`:
```bash
OAL_PHONE_MODE=usb       # wireless (default) or usb
```

## Supported Hardware

### USB Gadget Mode (SBC → car via USB-C)

Requires a SBC with USB OTG/peripheral support. The installer auto-detects RPi; other platforms may need manual DT configuration.

| SBC | USB Controller | Status | Notes |
|---|---|---|---|
| **Khadas VIM4** | Amlogic crgudc2 | Tested, working | 8 cores, 8GB RAM. aarch64. |
| **Raspberry Pi CM5** | DWC2 | Tested, working | `dtoverlay=dwc2` auto-configured. |
| Raspberry Pi CM4 | DWC2 | Expected to work | Same controller as CM5 |
| Raspberry Pi 4B | DWC2 | Expected to work | Use USB-C port (not USB-A) |
| Raspberry Pi Zero 2 W | DWC2 | Expected to work | Limited RAM (512MB) |
| Rockchip RK3568/RK3588 | DWC3 | Manual DT setup | Set `dr_mode = "peripheral"` in DT |
| Amlogic boards | crgudc2 | Manual DT setup | Platform-specific USB controller |

### External NIC Mode (any SBC)

No USB gadget needed — works with any SBC that has Ethernet. Plug a USB NIC into the car, run Ethernet cable to SBC.

### WiFi

The wireless script auto-probes hardware capabilities:
- Detects 5GHz support, available DFS-free channels, VHT/HE capabilities
- Picks the best config automatically (prefers 5GHz ch149 if available)
- Falls back to 2.4GHz if 5GHz isn't supported
- App can send band preference (5GHz/2.4GHz) which the SBC respects if hardware supports it

## Network Modes

The SBC connects to the car via Ethernet. Two modes are supported:

| Mode | How | Best For |
|---|---|---|
| `usb-gadget` (default) | SBC USB-C plugs into car USB port. Presents as CDC-ECM NIC + UDisk composite. | Minimal wiring, GM EV compatible |
| `external-nic` | USB NIC plugged into car, Ethernet cable to SBC RJ45. | Any SBC, no USB gadget needed |

Set in `/etc/openautolink.env`:
```bash
OAL_CAR_NET_MODE=usb-gadget   # or external-nic
OAL_CAR_NET_UDISK=1           # UDisk for GM EV (avoids "unsupported device" popup)
```

## Quick Start

```bash
# On a fresh SBC (Debian/Ubuntu ARM64):
sudo bash bridge/sbc/install.sh

# Edit configuration:
sudo nano /etc/openautolink.env

# Reboot:
sudo reboot
```

The installer:
- Installs dependencies (cmake, boost, hostapd, bluez, avahi, etc.)
- Builds `openautolink-headless` from source
- Deploys systemd services (auto-start on boot)
- Configures USB gadget (if `usb-gadget` mode)
- Sets hostname to `openautolink` with mDNS discovery

## Architecture

### SBC Services

| Service | Purpose |
|---|---|
| `openautolink.service` | Main AA bridge — aasdk + OAL protocol over TCP |
| `openautolink-car-net.service` | Car network setup (USB gadget or external NIC) |
| `openautolink-wireless.service` | WiFi AP for phone (hostapd + dnsmasq) |
| `openautolink-bt.service` | Bluetooth (BLE + HSP + AA RFCOMM ch8) |
| `openautolink-eth-ssh.service` | Debug SSH over Ethernet (optional) |

### Key Paths

| Path | Contents |
|---|---|
| `/opt/openautolink/bin/` | `openautolink-headless` binary |
| `/opt/openautolink/scripts/` | BT script, helpers |
| `/etc/openautolink.env` | All configuration |
| `/etc/avahi/services/openautolink.service` | mDNS discovery |

### Android App

The companion AAOS app connects to the bridge over TCP.

The app auto-discovers the bridge via mDNS (`_openautolink._tcp`) or manual IP entry.

## What's Supported

### Android Auto Protocol (aasdk v1.6)
- Version exchange, TLS handshake, auth, ServiceDiscovery
- 8 channels: Video, MediaAudio, SpeechAudio, SystemAudio, AudioInput, Sensor, Input, Bluetooth
- Dynamic resolution (800p–4K), codec (H264/H265/VP9), FPS (30/60)
- Ping keep-alive, stable multi-minute sessions
- 13 AA sensor types: speed, gear, parking brake, fuel/EV range, odometer, environment, night mode, driving status, location, door, lights, tire pressure, HVAC

### Wireless Connection
- WiFi AP: 5GHz ch149 (802.11ac, 80MHz VHT) with 2.4GHz fallback
- Bluetooth: BLE advertisement + HSP + RFCOMM channel 8
- Auto-generated SSID: `OpenAutoLink-{MAC4}`

### Car Integration
- OAL protocol over TCP (JSON control, binary video/audio)
- mDNS discovery (`_openautolink._tcp`)
- Vehicle sensor forwarding: app reads AAOS VHAL (37 properties via reflection), sends vehicle data JSON to bridge, bridge builds SensorBatch protobuf → phone AA
- Multi-codec video: H.264, H.265 (HEVC), VP9 — selectable in app settings
- Bidirectional mic/touch: coordinate scaling, OAL→aasdk action mapping

## Configuration

All settings are in `/etc/openautolink.env`. Key options:

```bash
# App display (how the app renders on the car's screen)
OAL_VIDEO_WIDTH=2400           # Match car display
OAL_VIDEO_HEIGHT=960
OAL_VIDEO_DPI=160

# AA stream (what the phone sends — separate from app display!)
OAL_AA_RESOLUTION=1080p        # 480p/720p/1080p/1440p*/4k*  (* = untested)
OAL_AA_FPS=60                  # 30 or 60
OAL_AA_CODEC=h264              # h264/h265*/vp9*  (* = phone may not support)

# Car network
OAL_CAR_NET_MODE=usb-gadget    # usb-gadget | external-nic
OAL_CAR_NET_UDISK=1            # GM EV UDisk composite
OAL_CAR_TCP_PORT=5288          # TCP port for car app

# Phone connection
OAL_PHONE_MODE=wireless        # wireless (WiFi+BT) | usb (wired AA)
OAL_PHONE_TCP_PORT=5277        # TCP port for phone AA session (wireless only)
OAL_WIRELESS_BAND=5ghz         # 5ghz (auto-probes) | 24ghz
```

> **Note**: "App Display" resolution and "AA Stream" resolution are independent.
> The app display resolution controls how the app renders on the car's screen.
> The AA stream resolution controls what the phone encodes — this is what the
> bridge tells the phone to send. Resolutions above 1080p and codecs other
> than H.264 are in the AA protocol spec but have not been confirmed working.

## Source Layout

```
bridge/
  openautolink/          — Source code
    headless/            — C++ headless binary
      include/openautolink/
        headless_config.hpp  — Shared configuration struct
        live_session.hpp     — aasdk AA session (TCP + USB host)
        oal_session.hpp      — OAL protocol session state machine
        tcp_car_transport.hpp — TCP server for car app
        i_car_transport.hpp  — Abstract transport interface
        oal_protocol.hpp     — OAL wire format (video/audio headers)
      src/
        main.cpp         — CLI entry point (--usb, --aa-resolution, etc.)
        live_session.cpp — AA session + USB scanning + disconnect callback
        oal_session.cpp  — OAL session, video/audio relay
      CMakeLists.txt     — Build with PI_AA_ENABLE_AASDK_LIVE=ON
    scripts/             — BT service, helpers
  sbc/                   — Deployment (SBC-agnostic)
    install.sh           — One-shot installer
    openautolink.env     — Default configuration
    run-openautolink.sh  — Launcher (reads env, passes CLI flags)
    setup-car-net.sh     — Car network (USB gadget or external NIC)
    start-wireless.sh    — WiFi AP (auto-probes 5GHz/VHT)
    prebuilt/aarch64/    — Pre-compiled binary for quick deployment
    openautolink*.service — systemd units
external/
  opencardev-aasdk/      — opencardev/aasdk v1.6 (AA protocol library)
  opencardev-openauto/   — opencardev/openauto (reference, not used at runtime)
```
