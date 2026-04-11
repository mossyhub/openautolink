# OpenAutoLink Bridge — SBC Setup Guide

## Overview

This guide walks you through setting up the bridge on an SBC. The process is:

1. Flash a Linux OS onto your SBC (you do this yourself)
2. Get SSH access while the SBC still has internet (via router or laptop)
3. Run the one-line install script (downloads everything from GitHub)
4. Edit config if needed (optional — defaults work for most GM EVs)
5. Reboot — the bridge starts automatically

> **After install and reboot, the SBC's onboard Ethernet becomes the car connection (static IP) and the WiFi radio becomes a phone hotspot. The SBC will no longer be reachable via your home network.** This is by design — the bridge doesn't need internet at runtime, and it auto-updates via the car app. See [SSH Access After Install](#ssh-access-after-install) if you need to get back in.

## What You Need

- **ARM64 single-board computer** with WiFi + Bluetooth
  - Primary dev board: Khadas VIM4 (overkill, but what's tested)
  - Also tested: Raspberry Pi 5 / CM5 (also overkill)
  - Recommended for daily use: any DietPi-supported ARM64 board with onboard GigE, 5 GHz WiFi, and eMMC (see [README § Choosing an SBC](../../README.md#choosing-an-sbc))
- **microSD card** (16 GB+) or eMMC — eMMC preferred for faster boot
- **USB Ethernet adapter** (to connect the SBC to the car's USB port)
- **Temporary internet access** for initial setup (Ethernet to router, WiFi, or laptop sharing)

## Step 1: Flash an OS

Flash a lightweight **64-bit Linux** to your SBC's storage. [DietPi](https://dietpi.com/) is recommended — it's minimal, boots fast, and supports most ARM64 boards out of the box.

| SBC | Recommended OS | Flash Tool |
|-----|---------------|------------|
| Raspberry Pi 4 or 5 / CM5 | [DietPi (RPi)](https://dietpi.com/downloads/#raspberry-pi) or [Raspberry Pi OS Lite 64-bit](https://www.raspberrypi.com/software/) | Raspberry Pi Imager / Etcher |
| Orange Pi Zero 2 / 3B / 5B | [DietPi (Orange Pi)](https://dietpi.com/downloads/#orange-pi) or [Armbian](https://www.armbian.com/) | Etcher / dd |
| Radxa ROCK 5B / 3A / 3C | [DietPi (Radxa)](https://dietpi.com/downloads/#radxa) | Etcher / dd |
| Khadas VIM4 | [DietPi (Khadas)](https://dietpi.com/downloads/#khadas) or [Ubuntu 22.04 Server](https://www.khadas.com/vim4) | Khadas Burn Tool / dd |
| Generic ARM64 | DietPi (see [supported hardware](https://dietpi.com/docs/hardware/)) or Ubuntu Server 22.04+ | Etcher / dd |

**Important**: Use the **server/lite** variant (no desktop). A GUI wastes RAM and isn't needed.

## Step 2: Get SSH Access (with Internet)

The SBC needs internet to download packages during install. Connect its onboard Ethernet to anything that provides DHCP and internet:

- **Home router** — plug the SBC's Ethernet into a LAN port, find its IP via `arp -a` or your router's admin page
- **Laptop with ICS** — share your laptop's WiFi to a USB Ethernet adapter via Windows Internet Connection Sharing, connect the SBC's Ethernet to that adapter (it gets a 192.168.137.x IP)

SSH in with whatever credentials came with your OS image (DietPi default: `root` / `dietpi`):

```bash
ssh root@<IP>
```

## Step 3: Install OpenAutoLink

Once you have an SSH session **with internet access**, run the installer:

```bash
curl -fsSL https://raw.githubusercontent.com/mossyhub/openautolink/main/bridge/sbc/install.sh | sudo bash
```

Or if you prefer to inspect the script first:

```bash
curl -fsSL -o install.sh https://raw.githubusercontent.com/mossyhub/openautolink/main/bridge/sbc/install.sh
cat install.sh        # read it
sudo bash install.sh  # run it
```

The installer will:
1. Install system packages (hostapd, dnsmasq, bluez, avahi)
2. Download the latest `openautolink-headless` binary from GitHub Releases
3. Deploy scripts and config to `/opt/openautolink/`
4. Create `/etc/openautolink.env` (your main config file)
5. Configure USB gadget support (Raspberry Pi auto-detected)
6. Set hostname to `openautolink` and enable mDNS (Avahi)
7. Create an `openautolink` user with passwordless sudo
8. Install and enable systemd services
9. Apply the network configuration immediately

When the script finishes, it prints a summary like:

```
=== Installation complete ===

  Binary:   /opt/openautolink/bin/openautolink-headless
  Hostname: openautolink
  Version:  v0.1.81

  Network (active now):
    Onboard NIC (eth0) -> 192.168.222.222  (car connection)
    USB NIC (if plugged in)    -> DHCP        (SSH access)
    WiFi radio                 -> phone hotspot (after reboot)

  To SSH into the SBC in the car:
    - Plug a USB Ethernet adapter into the SBC
    - It picks up DHCP automatically from your network
    - Or connect to the OpenAutoLink WiFi, SSH to 192.168.43.1

  Reboot to start all services: sudo reboot
```

The onboard Ethernet now has a static IP for the car. The WiFi hotspot and bridge services start after reboot.

## Step 4: Configure (Do This Before Rebooting)

Edit the config file to match your setup **now**, while you still have SSH access:

```bash
sudo nano /etc/openautolink.env
```

Key settings to check:

| Setting | Default | Description |
|---------|---------|-------------|
| `OAL_CAR_NET_MODE` | `external-nic` | How the SBC connects to the car. `external-nic` = onboard Ethernet to car via USB adapter (recommended) |
| `OAL_CAR_NET_IP` | `192.168.222.222` | IP address the car app connects to |
| `OAL_VIDEO_WIDTH` | `2400` | Video resolution width |
| `OAL_VIDEO_HEIGHT` | `960` | Video resolution height |
| `OAL_AA_FPS` | `60` | Target FPS |
| `OAL_AA_CODEC` | `h264` | Video codec (`h264`, `h265`, `vp9`) |
| `OAL_WIRELESS_COUNTRY` | `US` | Your regulatory country code (affects WiFi channels) |
| `OAL_AA_INIT_STABLE_INSETS` | `0,0,0,110` | Display safe area (default is for 2024 Blazer EV curved bezel — clear this if your display is flat) |

See the comments in the env file for all options.

## Step 5: Reboot

```bash
sudo reboot
```

After reboot, all services start automatically. The bridge is ready for the car.

## SSH Access After Install

After install, the SBC's onboard Ethernet is dedicated to the car and the WiFi radio is a phone hotspot. **You do not need SSH access for normal operation** — the bridge auto-updates itself via the car app (new releases are pushed over TCP from the AAOS app).

SSH is only needed for development, debugging, or recovery. Two options:

### Option 1: USB Ethernet adapter (recommended)

Plug a USB Ethernet adapter into the SBC and connect it to a network with DHCP (router, or a laptop with Windows ICS sharing its WiFi). The SBC requests a DHCP address automatically on any USB NIC.

To find it, connect your laptop to the same network and use mDNS:

```bash
ssh root@openautolink.local
```

Or find the IP via `arp -a` and connect directly.

> **Tip for developers**: Set up key-based SSH and add this to your `~/.ssh/config` so you can `ssh openautolink` regardless of which SBC is plugged in:
> ```
> Host openautolink
>     HostName openautolink.local
>     User root
>     StrictHostKeyChecking no
>     UserKnownHostsFile NUL
> ```
> `UserKnownHostsFile NUL` avoids host-key conflicts when swapping between multiple SBCs that share the same hostname.

### Option 2: WiFi AP

Connect your laptop to the OpenAutoLink WiFi network (the SSID and password are printed at first boot and saved to `/opt/openautolink/wifi-password.txt`), then SSH to `192.168.43.1`.

## Updating

The bridge auto-updates itself. When the car app detects a newer release on GitHub, it pushes the update to the bridge over TCP. No manual action or internet access is needed on the SBC.

To disable auto-update (for development): set `OAL_BRIDGE_UPDATE_MODE=disabled` in `/etc/openautolink.env`.

To manually update (e.g., if you need to update the install scripts or system packages), give the SBC temporary internet access via a USB Ethernet adapter and run the installer again:

```bash
curl -fsSL https://raw.githubusercontent.com/mossyhub/openautolink/main/bridge/sbc/install.sh | sudo bash
sudo reboot
```

Your `/etc/openautolink.env` config is preserved across updates.

## Troubleshooting

### Bridge won't start
```bash
journalctl -u openautolink -n 50    # check logs
ls -la /opt/openautolink/bin/       # binary exists?
```

### WiFi AP not starting
```bash
systemctl status openautolink-wireless
journalctl -u openautolink-wireless -n 30
iw list | grep -A5 "Supported interface modes"   # does HW support AP?
```

### Car app can't connect
```bash
ip addr show                        # check car-facing NIC has an IP
systemctl status openautolink-network
journalctl -u openautolink-network -n 30
```

### Bluetooth issues
```bash
systemctl status openautolink-bt
bluetoothctl show                   # is BT adapter visible?
```

### Need to reconfigure but can't SSH in
Connect the SBC to a monitor + keyboard (or USB serial console), log in at the console, and edit `/etc/openautolink.env`.

## File Layout

After installation:

```
/opt/openautolink/
├── bin/openautolink-headless    # Bridge binary
├── scripts/aa_bt_all.py         # Bluetooth pairing + WiFi exchange
├── run-openautolink.sh          # Launch wrapper (reads env)
├── setup-network.sh             # Car + SSH network setup
├── start-wireless.sh            # WiFi AP setup
└── stop-wireless.sh             # WiFi AP teardown

/etc/openautolink.env            # Configuration
/etc/avahi/services/
└── openautolink.service         # mDNS discovery (car app auto-finds bridge)
/etc/systemd/system/
├── openautolink.service         # Main bridge service
├── openautolink-network.service # Network setup (car IP + SSH)
├── openautolink-wireless.service # WiFi AP
└── openautolink-bt.service      # Bluetooth
```
