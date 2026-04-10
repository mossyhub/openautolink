# OpenAutoLink Bridge — SBC Setup Guide

## Overview

This guide walks you through setting up the bridge on an SBC. The process is:

1. Flash a Linux OS onto your SBC (you do this yourself)
2. Get SSH access while the SBC still has internet
3. Run the install script (downloads everything from GitHub)
4. Edit config if needed
5. Reboot — **after reboot, the SBC's network adapters are reconfigured for the car and phone, so internet access is no longer available**

> **Important:** The install script must be run while the SBC has internet access (to download packages and the bridge binary). After reboot, the onboard Ethernet becomes the car connection and the WiFi radio becomes connection for the phone to bridge AA communication — neither provides internet. Make sure you're happy with your config before rebooting.

## What You Need

- **ARM64 single-board computer** with WiFi + Bluetooth
  - Primary dev board: Khadas VIM4 (overkill, but what's tested)
  - Also tested: Raspberry Pi 5 / CM5 (also overkill)
  - Recommended for daily use: any DietPi-supported ARM64 board with onboard GigE, 5 GHz WiFi, and eMMC (see [README § Choosing an SBC](../../README.md#choosing-an-sbc))
- **microSD card** (16 GB+) or eMMC — eMMC preferred for faster boot
- **USB Ethernet adapter** (to connect the SBC to the car's USB port)
- **Temporary internet access** for initial setup (Ethernet to router, WiFi, or laptop sharing)

## Step 1: Flash an OS

Flash a lightweight **64-bit Linux** to your SBC's storage:

| SBC | Recommended OS | Flash Tool |
|-----|---------------|------------|
| Raspberry Pi 4 or 5 / CM5 | [DietPi (RPi)](https://dietpi.com/downloads/#raspberry-pi) or [Raspberry Pi OS Lite 64-bit](https://www.raspberrypi.com/software/) | Raspberry Pi Imager / Etcher |
| Orange Pi 5B / 3B | [DietPi (Orange Pi)](https://dietpi.com/downloads/#orange-pi) | Etcher / dd |
| Radxa ROCK 5B / 3A / 3C | [DietPi (Radxa)](https://dietpi.com/downloads/#radxa) | Etcher / dd |
| Khadas VIM4 | [DietPi (Khadas)](https://dietpi.com/downloads/#khadas) or [Ubuntu 22.04 Server](https://www.khadas.com/vim4) | Khadas Burn Tool / dd |
| Generic ARM64 | DietPi (see [supported hardware](https://dietpi.com/docs/hardware/)) or Ubuntu Server 22.04+ | Etcher / dd |

**Important**: Use the **server/lite** variant (no desktop). A GUI wastes RAM and isn't needed.

When flashing with Raspberry Pi Imager, enable SSH and set a username/password in the advanced settings.

## Step 2: Get SSH Access (with Internet)

You need SSH access **while the SBC can still reach the internet**. Pick one:

### Option A: Ethernet cable to your router
1. Plug an Ethernet cable between the SBC's onboard Ethernet and your router
2. Power on the SBC and wait ~30 seconds for boot
3. Find its IP:
   - Router admin page, or
   - `arp -a` on your laptop, or
   - `ping raspberrypi.local` (if mDNS works)
4. SSH in: `ssh pi@<IP>` (or whatever username you set)

### Option B: Windows WiFi sharing (ICS)
1. Plug a USB Ethernet adapter into your Windows laptop
2. Share your laptop's WiFi to the USB adapter via Internet Connection Sharing (ICS):
   - Network Settings → WiFi adapter → Properties → Sharing → "Allow other network users to connect"
   - Select the USB Ethernet adapter as the home networking connection
3. Connect the SBC's onboard Ethernet to the USB adapter with a cable
4. Power on the SBC — it gets an IP via DHCP from Windows (192.168.137.x range)
5. Find it: `arp -a` (look for 192.168.137.x entries)
6. SSH in: `ssh pi@192.168.137.x`

> This approach gives the SBC internet through your laptop's WiFi, which is needed for the install script. After OpenAutoLink is installed and rebooted, the SBC no longer needs this — you can unplug the USB adapter.

### Option C: USB serial console (Raspberry Pi)
1. Connect a USB-to-UART adapter to the GPIO pins
2. Open a serial terminal (PuTTY, `screen /dev/ttyUSB0 115200`)
3. Log in, then ensure the SBC has internet (e.g., plug Ethernet into a router)

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
6. Set up mDNS discovery (Avahi) so the car app can find the bridge automatically
7. Create an `openautolink` user with passwordless sudo
8. Install and enable systemd services

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

> **After this reboot, the SBC's network adapters are reconfigured:**
> - **Onboard Ethernet** → static IP `192.168.222.222` for the car connection
> - **WiFi radio** → access point for the phone (SSID auto-generated, password saved to `/opt/openautolink/wifi-password.txt`)
> - **Internet access** → gone (neither adapter connects to the internet anymore)

The bridge starts automatically on boot. 

### Accessing the SBC After Reboot

Since the SBC no longer has a normal network connection, you have two options:

1. **SSH via the WiFi AP**: Connect your laptop to the OpenAutoLink WiFi network (check the SSID/password), then SSH to `192.168.43.1`
2. **Plug in a USB Ethernet adapter** (a second one, not the one going to the car): The SBC will assign it an SSH role. With the default static mode, SSH to `192.168.137.2`

## Updating

To update, you need to temporarily give the SBC internet access again:

1. Plug it into a router via Ethernet, or use the laptop WiFi sharing (ICS) setup from Step 2
2. SSH in and run the installer again:

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
