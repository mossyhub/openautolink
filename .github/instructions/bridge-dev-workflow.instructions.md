---
description: "Use when building, deploying, or debugging the C++ bridge binary. Covers WSL cross-compilation, SBC deployment via SCP, and iterative dev workflow. Use this skill whenever the user wants to build bridge code, deploy to the SBC, or troubleshoot bridge builds."
---
# Bridge Dev Workflow

## Development Modes

Three validated development environments. Choose based on what hardware is available:

### Mode 1: In-Car (Real SBC + Real Phone + Real Car)
```
┌─────────────┐    USB-NIC     ┌─────────────┐   onboard NIC   ┌───────────────┐
│  Dev Laptop │◄── ICS SSH ───►│     SBC     │◄── bridge net ──►│   GM AAOS HU  │
│  (Windows)  │  192.168.137.x │ (ARM64)     │  192.168.222.x   │   (real car)  │
└─────────────┘                │             │◄── WiFi AP ──────►│  Phone (AA)   │
                               └─────────────┘   192.168.43.x    └───────────────┘
```
- **SBC SSH**: Prefer `openautolink` via mDNS; on the car network, direct SSH is `openautolink@192.168.222.222`
- **Laptop WiFi**: Connected to house WiFi, shared via ICS to USB-NIC → SBC gets internet
- **Bridge network**: SBC onboard NIC ↔ car USB port (192.168.222.x)
- **Phone**: Connects via BT pairing → WiFi AP → TCP:5277

### Mode 2: In-House (Real SBC + Emulator + Real Phone)
```
┌─────────────┐    USB-NIC     ┌─────────────┐   onboard NIC   ┌───────────────┐
│  Dev Laptop │◄── ICS SSH ───►│     SBC     │◄── bridge net ──►│ AAOS Emulator │
│  (Windows)  │  192.168.137.x │ (ARM64)     │  (no car)        │ (on laptop)   │
└─────────────┘                │             │◄── WiFi AP ──────►│  Phone (AA)   │
                               └─────────────┘   192.168.43.x    └───────────────┘
```
- Same SBC + SSH setup as Mode 1, just no car
- AAOS emulator connects to SBC bridge via `adb reverse` TCP tunnels
- Real phone pairs via BT → joins SBC WiFi AP → full AA session
- See [docs/testing.md](../../docs/testing.md) for emulator setup

---

## Overview

The bridge binary (`openautolink-headless`) is an ARM64 Linux binary for SBC deployment. Two build paths: WSL cross-compile (dev iteration) and GitHub CI (releases). Do NOT build on the SBC directly — always cross-compile.

## One-Time Setup

Run in WSL to install the cross-compilation toolchain:

```bash
# From repo root in WSL:
bash scripts/setup-wsl-cross-compile.sh
```

This installs `aarch64-linux-gnu-g++`, ARM64 Boost/OpenSSL/protobuf/libusb libraries, and CMake.

## SBC SSH Access

**Prefer `ssh openautolink`** to connect to the SBC. If mDNS is unavailable but the car network is directly reachable, use `ssh openautolink@192.168.222.222`.

The install script sets the hostname to `openautolink`, so the hostname should be the default path.

```powershell
# Check bridge logs:
ssh openautolink "journalctl -u openautolink -n 100 --no-pager"

# Check service status:
ssh openautolink "systemctl status openautolink"

# Restart bridge:
ssh openautolink "sudo systemctl restart openautolink"
```

Scripts and examples should prefer `openautolink` by default.

## Build + Deploy (Single Command)

From PowerShell:

```powershell
# Build in WSL + deploy binary to SBC:
scripts\deploy-bridge.ps1

# Clean rebuild + deploy:
scripts\deploy-bridge.ps1 -Clean

# Deploy only (skip build, use last binary):
scripts\deploy-bridge.ps1 -SkipBuild

# Full provisioning (fresh/wiped SBC — installs packages, scripts, services, config, certs + binary):
scripts\deploy-bridge.ps1 -Full -SkipBuild

# Custom SBC address:
scripts\deploy-bridge.ps1 -SbcHost 10.0.0.1 -SbcUser openautolink
```

## Build Only (No Deploy)

From WSL:

```bash
bash scripts/build-bridge-wsl.sh        # incremental
bash scripts/build-bridge-wsl.sh clean   # full rebuild
```

Output: `build-bridge-arm64/openautolink-headless-stripped`

## Key Files

| File | Purpose |
|------|---------|
| `scripts/setup-wsl-cross-compile.sh` | One-time WSL toolchain setup |
| `scripts/build-bridge-wsl.sh` | WSL cross-compile script |
| `scripts/deploy-bridge.ps1` | Build + SCP + restart service |
| `build-bridge-arm64/` | Build output dir (gitignored) |

## How It Works

1. **WSL cross-compiles** using `aarch64-linux-gnu-g++` with a CMake toolchain file
2. Binary is built in `build-bridge-arm64/` (on the Windows filesystem, accessible from both WSL and PowerShell)
3. **SSH** stops the service
4. **PowerShell SCPs** the stripped binary directly to `/opt/openautolink/bin/` on the SBC
5. **SSH** restarts the service

## Troubleshooting

### ARM64 package conflicts
If `apt-get install libboost-*:arm64` fails with conflicts, you may need:
```bash
sudo apt-get install -f    # fix broken deps
```

### CMake can't find ARM64 libs
The toolchain file sets `CMAKE_FIND_ROOT_PATH` to `/usr/aarch64-linux-gnu` and `/usr`. If Boost or OpenSSL aren't found, check:
```bash
ls /usr/lib/aarch64-linux-gnu/libboost_system*
ls /usr/lib/aarch64-linux-gnu/libssl*
```

### SSH key not set up
If SCP prompts for a password every time:
```powershell
ssh-copy-id khadas@192.168.137.197
```

### SBC address changes
The SBC gets its SSH IP via DHCP from Windows ICS (192.168.137.x). Find it with:
```powershell
arp -a | Select-String "192.168.137"
```

### Protobuf version mismatch
aasdk's FetchContent downloads its own protobuf. If cross-compile fails on protobuf, the host `protoc` must match. Install the native (x86_64) protobuf-compiler alongside the ARM64 libs:
```bash
sudo apt-get install protobuf-compiler
```

## Build Architecture

```
Windows (PowerShell)
  │
  ├── scripts\deploy-bridge.ps1
  │     │
  │     ├── 1. wsl bash scripts/build-bridge-wsl.sh
  │     │        └── cmake cross-compile (x86_64 host → aarch64 target)
  │     │        └── output: build-bridge-arm64/openautolink-headless-stripped
  │     │
  │     ├── 2. ssh: stop service
  │     │
  │     ├── 3. scp binary → SBC:/opt/openautolink/bin/
  │     │
  │     └── 4. ssh: restart service
  │
  └── SBC running at 192.168.137.x (via Windows ICS)
```

## Phone ADB via SBC (Remote Phone Debugging)

The phone and SBC share the bridge's WiFi AP network (192.168.43.x). This enables
a remote debugging chain from the dev PC through the SBC to the phone:

```
Dev PC  ─── SSH openautolink ───▶  SBC (192.168.43.1)  ─── ADB ───▶  Phone (192.168.43.x)
```

This lets you run `adb logcat` on the phone **from the dev PC or from Copilot** to see
AA, Google Assistant, Maps, and sensor logs — invaluable for debugging issues like
mic/voice failures, EV data, or AA protocol behavior.

### One-Time Setup

**On the phone:**
1. Enable **Developer Options**: Settings → About Phone → tap Build Number 7 times
2. Enable **Wireless debugging**: Developer Options → Wireless debugging → ON
3. Tap **Wireless debugging** to see the pairing code and port

**On the SBC (via SSH):**
```bash
# Install adb (one-time):
ssh openautolink "sudo apt-get install -y android-tools-adb"

# Pair with phone (one-time — enter the 6-digit code shown on phone):
ssh openautolink "adb pair <phone_ip>:<pairing_port>"
# Example: adb pair 192.168.43.100:37123

# Connect (needed after each phone reboot/WiFi reconnect):
ssh openautolink "adb connect <phone_ip>:<debug_port>"
# Example: adb connect 192.168.43.100:42345
# Note: pairing port and debug port are DIFFERENT — check phone screen
```

### Daily Use

After initial pairing, reconnect is usually automatic when the phone joins the
bridge WiFi. If not, just `adb connect` again.

```powershell
# Verify phone is connected:
ssh openautolink "adb devices"

# Full logcat (verbose — use filters!):
ssh openautolink "adb logcat -d | tail -200"

# AA-specific logs:
ssh openautolink "adb logcat -s AndroidAuto -d | tail -50"

# Google Assistant / voice debugging:
ssh openautolink "adb logcat -s GoogleAssistant,AssistantVoice,MicrophoneInputStream -d | tail -50"

# Maps / navigation / EV data:
ssh openautolink "adb logcat -s Maps,GoogleMaps,SensorManager -d | tail -50"

# AA protocol / connection:
ssh openautolink "adb logcat -d | grep -iE 'androidauto|gearhead|projection|sensor.*fuel' | tail -50"

# Live streaming logcat (Ctrl+C to stop):
ssh openautolink "adb logcat -s AndroidAuto"

# Clear logcat buffer (do before reproducing an issue):
ssh openautolink "adb logcat -c"
```

### Debugging Workflow

For targeted issue debugging (e.g. mic/voice):
1. Clear logcat: `ssh openautolink "adb logcat -c"`
2. Reproduce the issue on the car (press mic button, etc.)
3. Pull logs: `ssh openautolink "adb logcat -d | grep -iE 'voice|mic|assistant|audio' | tail -100"`

### Important Notes
- **No root required** — standard ADB wireless debugging works
- Phone must have **screen unlocked** for initial wireless debugging pairing
- The debug port changes on each phone reboot — check phone's wireless debugging screen
- The pairing code is one-time; after pairing, only `adb connect` is needed
- ADB over WiFi adds ~1-2ms latency — negligible for logcat
- If `adb devices` shows "offline", disconnect and reconnect: `adb disconnect; adb connect <ip>:<port>`
