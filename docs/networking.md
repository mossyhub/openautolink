# OpenAutoLink — Network Architecture

## Overview

The OpenAutoLink bridge has **three network connections**, each serving a different purpose:

```
┌─────────────────────────────────────────────────────────────────┐
│                    OpenAutoLink Bridge (SBC)                     │
│                                                                 │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐  │
│  │   Phone ↔    │  │   Car  ↔     │  │   SSH/Management     │  │
│  │   Bridge     │  │   Bridge     │  │   (dev only)         │  │
│  │              │  │              │  │                      │  │
│  │  WiFi (wlan0)│  │  Onboard NIC │  │  USB NIC (eth1+)     │  │
│  │  or USB host │  │  (eth0)      │  │  plugged into SBC    │  │
│  │              │  │              │  │                      │  │
│  │  TCP :5277   │  │  TCP :5288   │  │  DHCP client         │  │
│  │              │  │  TCP :5290   │  │  (laptop WiFi share) │  │
│  │  BT pairing  │  │  TCP :5289   │  │                      │  │
│  │  + WiFi AP   │  │              │  │                      │  │
│  └──────────────┘  └──────────────┘  └──────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

## Network Roles

### 1. Phone → Bridge (Android Auto / CarPlay stream)

**How the phone connects:**
- **Android (wireless AA)**: Phone pairs via Bluetooth, joins bridge's WiFi AP, connects AA over TCP port 5277
- **iPhone (wireless CarPlay)**: iPhone pairs via Bluetooth, joins bridge's WiFi AP, connects CarPlay via RTSP port 5000 + AirPlay ports 7000/7001
- **Wired USB**: Phone plugs into bridge USB host port, uses AOA protocol (AA only)

Both phone types use the same WiFi AP and BT pairing infrastructure. The bridge advertises both AA and CarPlay BT UUIDs simultaneously (different RFCOMM channels, no conflict). Session mode is controlled by `OAL_PHONE_PROTOCOL`.

**Configuration:**
```bash
OAL_PHONE_MODE=wireless        # "wireless" or "usb"
OAL_PHONE_TCP_PORT=5277        # TCP port for wireless AA (default 5277)
OAL_PHONE_PROTOCOL=android-auto  # "android-auto", "carplay", or "auto"
```

### 2. Bridge → Car (OAL protocol relay)

**Three TCP ports** for the car app connection:

| Port | Purpose | Direction | Format |
|------|---------|-----------|--------|
| 5288 | Control | Bidirectional | JSON lines |
| 5290 | Video | Bridge → App | Binary (16B header + codec) |
| 5289 | Audio | Bidirectional | Binary (8B header + PCM) |

See [protocol.md](protocol.md) for full wire format.

**Connection method:** The SBC's **onboard ethernet NIC** (eth0) connects to a USB Ethernet adapter plugged into the car's USB port, via a short ethernet cable. The bridge NIC gets a static IP (`192.168.222.222`). The car's head unit sees the adapter as a USB network device and assigns itself an IP on the same subnet.

**Configuration:**
```bash
OAL_CAR_NET_MODE=external-nic     # Onboard eth0 = car network
OAL_CAR_NET_IP=192.168.222.222    # Static IP for bridge on car network
OAL_CAR_NET_MASK=24               # Subnet mask
OAL_CAR_TCP_PORT=5288             # Control port (video=5290, audio=5289 are fixed)
```

### 3. SSH/Management (dev only)

**For development and debugging only.** Not needed for production use. A second USB Ethernet adapter plugged into the SBC provides SSH access from a laptop.

Without this second NIC, the SBC has no SSH access — which is fine for normal operation (the bridge runs autonomously via systemd).

**Configuration:**
```bash
OAL_SSH_MODE=dhcp-client    # "dhcp-client" (default) or "dhcp-server"
OAL_SSH_IP=10.0.0.1         # Only used in dhcp-server mode
```

---

## IP Addressing

| Network | IP | Subnet | Who |
|---------|-----|--------|-----|
| Car network | `192.168.222.222` | `/24` | Bridge (static, onboard eth0) |
| Car network | `192.168.222.108` | `/24` | Car head unit (assigned by car) |
| Phone WiFi | `192.168.43.1` | `/24` | Bridge AP (wlan0) |
| Phone WiFi | `192.168.43.x` | `/24` | Phone (DHCP from dnsmasq) |
| SSH (dev) | varies | `/24` | DHCP from laptop WiFi share |
