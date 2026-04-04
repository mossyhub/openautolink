# OpenAutoLink Bridge — SBC Setup Guide

## What You Need

- **ARM64 single-board computer** with WiFi + Bluetooth
  - Tested: Raspberry Pi CM5, Khadas VIM4
  - Should work: any ARM64 SBC with WiFi AP capability and BT 4.0+
- **microSD card** (16GB+) or eMMC module
- **USB Ethernet adapter** (to connect the SBC to the car's USB port)
- **Way to SSH into the SBC** (USB-C cable, Ethernet cable, or serial console)

## Step 1: Flash an OS

Flash a lightweight **64-bit Linux** to your SBC's storage:

| SBC | Recommended OS | Flash Tool |
|-----|---------------|------------|
| Raspberry Pi 4 or 5 | [Raspberry Pi OS Lite (64-bit)](https://www.raspberrypi.com/software/) | Raspberry Pi Imager |
| Khadas VIM4 | [Ubuntu 22.04 Server](https://www.khadas.com/vim4) | Khadas Burn Tool / dd |
| Generic ARM64 | Ubuntu Server 22.04+ or Debian 12+ | Etcher / dd |

**Important**: Use the **server/lite** variant (no desktop). A GUI wastes RAM and isn't needed.

When flashing with Raspberry Pi Imager, enable SSH and set a username/password in the advanced settings.

## Step 2: Get SSH Access

You need a way to reach your SBC's terminal. Pick one:

### Option A: Ethernet cable to your laptop/router
1. Plug an Ethernet cable between the SBC and your laptop (or router)
2. Power on the SBC and wait ~30 seconds for boot
3. Find its IP:
   - Router admin page, or
   - `arp -a` on your laptop, or
   - `ping raspberrypi.local` (if mDNS works)
4. SSH in: `ssh pi@<IP>` (or whatever username you set)

### Option B: USB serial console (Raspberry Pi)
1. Connect a USB-to-UART adapter to the GPIO pins
2. Open a serial terminal (PuTTY, `screen /dev/ttyUSB0 115200`)
3. Log in at the console

### Option C: Windows WiFi sharing (developer setup)
1. Share your Windows WiFi to a USB Ethernet adapter via ICS
2. Connect the SBC to the USB NIC
3. Find the SBC via `arp -a` (look for 192.168.137.x)
4. SSH in: `ssh user@192.168.137.x`

## Step 3: Install OpenAutoLink

Once you have an SSH session, run the installer. It downloads the latest bridge binary, scripts, and services from GitHub and sets everything up:

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
6. Create an `openautolink` user with passwordless sudo (for SSH deploy)
7. Install and enable systemd services

### SSH Key Auth (recommended)

After installation, set up SSH key auth so deploy scripts don't prompt for a password:

```bash
# From your development machine (Windows/Mac/Linux):
ssh-copy-id openautolink@<SBC_IP>

# Or if you already have a key, add an SSH config entry:
# ~/.ssh/config
Host oal-sbc
    HostName 192.168.137.2
    User openautolink
    IdentityFile ~/.ssh/id_ed25519_openautolink
```

The deploy script (`scripts/deploy-bridge.ps1`) uses `openautolink` as the default SSH user.

## Step 4: Configure

Edit the config file to match your setup:

```bash
sudo nano /etc/openautolink.env
```

Key settings to check:

| Setting | Default | Description |
|---------|---------|-------------|
| `OAL_CAR_NET_MODE` | `auto` | How the SBC connects to the car (`auto`, `usb-gadget`, `external-nic`) |
| `OAL_CAR_NET_IP` | `192.168.222.222` | IP address the car app connects to |
| `OAL_VIDEO_WIDTH` | `2400` | Video resolution width sent to phone |
| `OAL_VIDEO_HEIGHT` | `960` | Video resolution height sent to phone |
| `OAL_AA_FPS` | `60` | Target FPS |
| `OAL_AA_CODEC` | `h264` | Video codec (`h264`, `h265`, `vp9`) |

See the comments in the file for all options.

## Step 5: Reboot

```bash
sudo reboot
```

After reboot, the bridge starts automatically. Check status:

```bash
systemctl status openautolink
journalctl -u openautolink -f    # live logs
```

## Updating

To update to the latest version, just run the installer again:

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
ping -c1 192.168.222.222            # is the bridge IP reachable?
systemctl status openautolink-car-net
```

### Bluetooth issues
```bash
systemctl status openautolink-bt
bluetoothctl show                   # is BT adapter visible?
```

## File Layout

```
/opt/openautolink/
├── bin/openautolink-headless    # Bridge binary
├── scripts/aa_bt_all.py         # Bluetooth pairing + WiFi exchange
├── run-openautolink.sh          # Launch wrapper (reads env)
├── setup-car-net.sh             # Car network setup
├── setup-network.sh             # Unified network setup
├── start-wireless.sh            # WiFi AP setup
└── setup-eth-ssh.sh             # SSH NIC setup

/etc/openautolink.env            # Configuration
/etc/systemd/system/
├── openautolink.service         # Main bridge service
├── openautolink-car-net.service # Car network
├── openautolink-wireless.service # WiFi AP
├── openautolink-bt.service      # Bluetooth
└── openautolink-eth-ssh.service # SSH NIC
```
| `bridge/openautolink/headless/src/live_session.cpp`      | `/opt/openautolink-src/headless/src/live_session.cpp`     |
| `bridge/openautolink/headless/include/openautolink/*.hpp` | `/opt/openautolink-src/headless/include/openautolink/*.hpp`|
| `external/aasdk/`                                        | `/opt/openautolink-src/aasdk/`                            |

## Common Pitfalls

- **NEVER put source/build in user home** (`/home/khadas/`). Use `/opt/openautolink-src/`.
- **Touch files after scp** — CMake may not detect timestamp changes from Windows.
- **Must stop service before copying binary** — `Text file busy` error if binary is running.
- **Strip the binary** — unstripped is ~43MB, stripped is ~2.3MB.
- **Phone reconnection after restart** — takes 30-60s for BT+WiFi to re-establish.
