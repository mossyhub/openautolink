# OpenAutoLink — Local Testing Guide

## Overview

Full end-to-end testing requires the AAOS app talking to the bridge over a real network. Since the GM head unit has **no ADB access** due to GM restrictions, we use the Android SDK AAOS emulator as a stand-in. The bridge runs on a physical SBC connected to the development PC via two separate network cables.

```
┌──────────────────┐         ┌─────────────────────────────────────┐
│  Dev PC (Windows) │         │          SBC (Bridge Relay)         │
│                   │         │                                     │
│  AAOS Emulator    │◄──eth──►│  Onboard NIC (eth0)                │
│  (192.168.222.108)│  bridge │  Static IP: 192.168.222.222        │
│                   │  traffic│  Ports: 5288 (control), 5291 (relay)│
│                   │         │                                     │
│  PC NIC / USB-NIC │◄──eth──►│  USB NIC (eth1+)                   │
│  (DHCP / static)  │   SSH   │  SSH access                        │
│                   │         │                                     │
│  adb → emulator   │         │  Phone ── WiFi ──► wlan0 (5277)    │
└──────────────────┘         └─────────────────────────────────────┘
```

The app connects **outbound** to the bridge relay on two TCP channels:
- **Control** (TCP:5288) — lightweight JSON signaling (hello, relay_ready, diagnostics)
- **Relay** (TCP:5291) — raw byte splice between app and phone (AA protocol via aasdk JNI)

The bridge does **zero** AA protocol processing. aasdk runs inside the app via NDK/JNI.

## Prerequisites

- Android SDK with emulator and platform-tools installed
- AAOS emulator AVDs (see Emulator Setup below):
  - **`BlazerEV_AAOS`** — Android 33 Automotive (Google APIs, no Play Store), x86_64, 2914×1134. Supports `adb root` and VHAL injection. Primary testing image.
  - **`BlazerEV_AAOS_14`** — Android 14 Automotive (Google APIs, no Play Store), x86_64, 2914×1134. Supports `adb root` and VHAL injection. AAOS 14 compatibility testing.
  - **`DD_AAOS_33`** — Android 33 Automotive Distant Display (Google Play), x86_64, 2914×1134 + cluster displays. For visual cluster testing. No `adb root`.
- SBC with bridge built and running (see [bridge/sbc/BUILD.md](../bridge/sbc/BUILD.md))
- Two ethernet cables
- One USB ethernet adapter (for the SBC's SSH connection)
- A phone with Android Auto for full end-to-end tests

## 1. Hardware Setup

### Two-Cable Network Connection

The SBC requires **two separate network connections** to your PC:

| Cable | SBC Side | PC Side | Purpose |
|-------|----------|---------|---------|
| **Cable 1 -- Bridge traffic** | **Onboard NIC** (eth0, RJ45 on the board) | Dedicated NIC or USB ethernet adapter on PC | Relay (5291) + control (5288) |
| **Cable 2 -- SSH** | **USB NIC** (USB ethernet adapter plugged into SBC) | Any available NIC on PC | SSH access for development |

> **Why two cables?** The onboard NIC is locked to the car network subnet (192.168.222.0/24) with a static IP. Mixing SSH and bridge traffic on one interface creates routing conflicts and doesn't match the real car topology.

### SSH Connection via WiFi Sharing

The simplest way to give the SBC an SSH-accessible IP from your dev PC:

1. **Share your PC's WiFi to the SSH NIC** — In Windows: Settings → Network → Wi-Fi → Properties → "Share this connection" → select the NIC connected to the SBC's USB ethernet adapter
2. Windows ICS (Internet Connection Sharing) runs a DHCP server on that NIC in the **`192.168.137.0/24`** subnet (the PC becomes `192.168.137.1`)
3. The SBC's USB NIC gets a DHCP lease (e.g. `192.168.137.x`)
4. **Discover the SBC's IP via ARP:**
   ```powershell
   # After the SBC boots and gets a DHCP lease:
   arp -a | Select-String "192.168.137"
   ```
5. SSH in: `ssh khadas@192.168.137.x` (substitute your SBC's username and discovered IP)

> **Tip:** The SBC's MAC address won't change, so Windows ICS will usually assign the same IP across reboots. Note it down after first discovery.

### IP Addressing

This mirrors the real car environment:

| Device | IP | Role |
|--------|-----|------|
| SBC onboard NIC (eth0) | `192.168.222.222` | Bridge — already configured by `setup-car-net.sh` |
| Emulator (acting as head unit) | `192.168.222.108` | App — matches the IP the GM head unit assigns to its USB NIC |
| SBC USB NIC (eth1+) | DHCP from Windows ICS | SSH management — `192.168.137.x` subnet |
| PC SSH NIC | `192.168.137.1` | SSH client access (Windows ICS gateway) |

## 2. SBC Initial Setup (First Time Only)

Before the bridge can be built and deployed, the SBC needs a one-time setup for key-based SSH and passwordless sudo so that development tools (including Copilot) can work freely on the SBC.

### 2a. OS Setup

1. Flash the SBC's OS image per its manufacturer's instructions
2. Boot the SBC and connect via SSH (password auth initially): `ssh <user>@<sbc-ip>`
3. Update the OS:
   ```bash
   sudo apt update && sudo apt upgrade -y
   ```

### 2b. Key-Based SSH

Generate an SSH key on your dev PC (if you don't have one) and copy it to the SBC:

```powershell
# On the dev PC (PowerShell)
# Generate key if needed:
ssh-keygen -t ed25519 -C "openautolink-dev"

# Copy public key to SBC (will prompt for password one last time):
type $env:USERPROFILE\.ssh\id_ed25519.pub | ssh <user>@<sbc-ip> "mkdir -p ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 700 ~/.ssh && chmod 600 ~/.ssh/authorized_keys"
```

Verify: `ssh <user>@<sbc-ip>` should now connect without a password prompt.

### 2c. Passwordless Sudo

On the SBC, add a sudoers rule so the dev user can run commands without a password:

```bash
# On the SBC:
sudo visudo -f /etc/sudoers.d/dev-nopasswd
# Add this line (replace <user> with your SBC username):
<user> ALL=(ALL) NOPASSWD: ALL
```

Verify: `sudo whoami` should return `root` without a password prompt.

> **Why passwordless sudo?** Copilot and deployment scripts (`scp`, `ssh` commands in `deploy-to-sbc.ps1`) need to restart services, install files to `/opt/openautolink/`, and run `systemctl` without interactive prompts.

### 2d. Verify

```powershell
# From the dev PC — should work with no prompts:
ssh <user>@<sbc-ip> "sudo systemctl status sshd"
```

Once complete, proceed to build and deploy the bridge per [bridge/sbc/BUILD.md](../bridge/sbc/BUILD.md).

## 3. Emulator Setup

### Starting the Emulator

We use **three AVDs** for complete testing coverage:

| AVD | Image | Root | VHAL Injection | Cluster Visual | Use For |
|-----|-------|------|---------------|----------------|---------|
| **`BlazerEV_AAOS`** | `android-33;android-automotive;x86_64` | Yes | Yes | Logs only | Primary: VHAL, video, audio, session, reconnect |
| **`BlazerEV_AAOS_14`** | `android-34-ext9;android-automotive;x86_64` | Yes | Yes | Logs only | AAOS 14 compatibility testing |
| **`DD_AAOS_33`** | `android-33;android-automotive-distant-display-playstore;x86_64` | No | No | Yes (visible panel) | Visual cluster verification |

All AVDs use **2914×1134 @ 200dpi** to match the real GM Blazer EV display.

#### Creating the AVDs (one-time)

```powershell
# Install system images
sdkmanager "system-images;android-33;android-automotive;x86_64"
sdkmanager "system-images;android-33;android-automotive-distant-display-playstore;x86_64"
sdkmanager "system-images;android-34-ext9;android-automotive;x86_64"

# Create AVDs
echo "no" | avdmanager create avd --name "BlazerEV_AAOS" `
  --package "system-images;android-33;android-automotive;x86_64" `
  --device "automotive_1080p_landscape" --force

echo "no" | avdmanager create avd --name "BlazerEV_AAOS_14" `
  --package "system-images;android-34-ext9;android-automotive;x86_64" `
  --device "automotive_1080p_landscape" --force

echo "no" | avdmanager create avd --name "DD_AAOS_33" `
  --package "system-images;android-33;android-automotive-distant-display-playstore;x86_64" `
  --device "automotive_distant_display_with_play" --force
```

Then edit each AVD's `config.ini` (`~/.android/avd/<name>.avd/config.ini`):
- Set `hw.lcd.width=2914`, `hw.lcd.height=1134`, `hw.lcd.density=200` to match the Blazer EV
- For `DD_AAOS_33`: shrink the secondary displays to avoid an oversized window:
  ```
  hw.display6.height=240
  hw.display6.width=400
  hw.display7.height=240
  hw.display7.width=800
  ```

#### Launching

```powershell
# Primary testing (VHAL + root):
emulator -avd BlazerEV_AAOS -no-audio -gpu swiftshader_indirect -no-boot-anim

# AAOS 14 compatibility testing:
emulator -avd BlazerEV_AAOS_14 -no-audio -gpu swiftshader_indirect -no-boot-anim

# Cluster visual testing (distant display):
emulator -avd DD_AAOS_33 -no-audio -gpu swiftshader_indirect -no-boot-anim
```

#### VHAL Testing (BlazerEV_AAOS only)

The non-Play Store image supports `adb root` and VHAL property injection:

```powershell
# Get root access
adb root

# Grant car permissions (required — declared in manifest)
adb shell "pm grant com.openautolink.app android.car.permission.CAR_SPEED"
adb shell "pm grant com.openautolink.app android.car.permission.CAR_ENERGY"
adb shell "pm grant com.openautolink.app android.car.permission.CAR_POWERTRAIN"
adb shell "pm grant com.openautolink.app android.car.permission.CAR_EXTERIOR_ENVIRONMENT"
adb shell "pm grant com.openautolink.app android.car.permission.CAR_INFO"

# Inject Blazer EV-like values
adb shell "cmd car_service inject-vhal-event 291504647 0.0"      # speed: 0 m/s
adb shell "cmd car_service inject-vhal-event 289408000 4"        # gear: PARK
adb shell "cmd car_service inject-vhal-event 291504905 41550.0"  # EV battery: 41550 Wh
adb shell "cmd car_service inject-vhal-event 291505923 13.0"     # outside temp: 13°C
adb shell "cmd car_service inject-vhal-event 291504904 214984.4" # range: 214984 m
adb shell "cmd car_service inject-vhal-event 287310855 0"        # night mode: off
adb shell "cmd car_service inject-vhal-event 287310850 0"        # parking brake: off

# Simulate driving
adb shell "cmd car_service inject-vhal-event 291504647 16.7"     # speed: 60 km/h
adb shell "cmd car_service inject-vhal-event 289408000 8"        # gear: DRIVE
```

> **Note:** VHAL injection requires the non-Play Store image (`BlazerEV_AAOS`). The distant display image (`DD_AAOS_33`) blocks `inject-vhal-event` with a security exception.

### Configuring the Emulator's Network for Bridge Traffic

The emulator must be reachable at `192.168.222.108` on the bridge network — this is the IP the GM head unit always assigns when a USB NIC is plugged in. Configuring the emulator this way ensures the app behaves identically to the real car.

#### Option A: Host-Side Port Forwarding (Simplest)

Use `adb` to forward the emulator's ports through the host PC's bridge NIC:

```powershell
# Forward bridge ports from PC (192.168.222.108) to emulator
# Run these after the emulator is booted

# On the PC NIC connected to the SBC, set a secondary IP:
# (Control Panel → Network Adapter → Properties → IPv4 → Advanced → Add 192.168.222.108)
# Or via PowerShell (run as Administrator):
New-NetIPAddress -InterfaceAlias "Ethernet 2" -IPAddress 192.168.222.108 -PrefixLength 24

# Forward ports from PC to emulator
adb forward tcp:5288 tcp:5288
adb forward tcp:5289 tcp:5289
adb forward tcp:5290 tcp:5290
```

> **Note:** Replace `"Ethernet 2"` with the name of your PC NIC connected to the SBC's onboard NIC. Run `Get-NetAdapter` to list adapters.

With this approach, the bridge connects to `192.168.222.108:5288/5289/5290` and the traffic is forwarded into the emulator.

#### Option B: Reverse ADB (App Connects Outbound)

Since the app initiates the TCP connections (app → bridge), you can use `adb reverse` to let the emulator reach the SBC:

```powershell
# Make the emulator see 192.168.222.222:5288 as localhost:5288
adb reverse tcp:5288 tcp:5288
adb reverse tcp:5289 tcp:5289
adb reverse tcp:5290 tcp:5290
```

Then configure the app to connect to `localhost` (or `127.0.0.1`) as the bridge address. The traffic will route through the host to the SBC at `192.168.222.222`.

> **Recommended:** Option B is the most reliable for the app's connection model (app connects to bridge). Set the bridge IP in the app's settings to `127.0.0.1` and use `adb reverse` to tunnel.

## 4. Installing and Running the App

### Build and Install

```powershell
# Build debug APK
.\gradlew :app:assembleDebug

# Install to running emulator
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

### Launch the App

```powershell
adb shell am start -n com.openautolink.app/.ui.MainActivity
```

### View Logs

```powershell
# All app logs
adb logcat --pid=$(adb shell pidof com.openautolink.app)

# Filter for OpenAutoLink tags
adb logcat -s OAL:* Transport:* Video:* Audio:*
```

## 5. Bridge Setup (SBC Side)

### Verify Car Network

SSH into the SBC via the USB NIC connection:

```bash
# Check the onboard NIC has the correct IP
ip addr show eth0
# Should show 192.168.222.222/24

# Verify the bridge service is running
sudo systemctl status openautolink.service

# Check bridge logs
sudo journalctl -u openautolink.service -f
```

### Verify Network Connectivity

From the SBC, confirm the emulator (or its host) is reachable:

```bash
ping -c 3 192.168.222.108
```

From the PC, confirm the bridge is reachable:

```powershell
Test-NetConnection -ComputerName 192.168.222.222 -Port 5288
```

## 6. End-to-End Test Workflow

1. **Boot SBC** — bridge starts automatically via systemd
2. **SSH into SBC** — verify `eth0` is `192.168.222.222`, bridge is running
3. **Start emulator** — launch `BlazerEV_AAOS`
4. **Configure networking** — set up port forwarding or `adb reverse` (see Section 2)
5. **Install app** — `adb install` the debug APK
6. **Launch app** — app should show "Connecting..." and attempt to reach the bridge
7. **Pair phone** — use Bluetooth on the SBC to pair with a phone running Android Auto
8. **Verify projection** — video should render in the emulator, audio should play, touch should respond

### What to Verify

| Component | How to Test | Expected Behavior |
|-----------|-------------|-------------------|
| **TCP connection** | App status UI / logcat | Three channels connect (control, video, audio) |
| **Video** | Emulator display | Phone screen projected, smooth rendering |
| **Audio** | Emulator audio output | Media/nav audio plays through correct purposes |
| **Touch** | Click/drag in emulator | Touch events forwarded to phone, UI responds |
| **Reconnection** | Kill bridge, restart | App shows "Connecting...", reconnects when bridge returns |
| **Settings** | App settings screen | Bridge IP, codec preferences persisted via DataStore |

## 7. Unit and Integration Tests

These run without hardware:

```powershell
# Unit tests (no emulator needed)
.\gradlew :app:testDebugUnitTest

# Instrumentation tests (requires running emulator)
.\gradlew :app:connectedDebugAndroidTest
```

## 8. Mock Bridge Testing (No SBC Required)

The mock bridge (`scripts/mock_bridge.py`) lets you test the app's full video/audio/control pipeline without a physical SBC, phone, or car. It speaks OAL protocol directly to the app, generating synthetic H.264 video (via ffmpeg) and PCM audio (sine wave).

### What You Can Test

| Area | What the mock provides |
|------|----------------------|
| **TCP connection + handshake** | Hello exchange, phone_connected event, config_echo |
| **Video rendering** | H.264 test pattern with timestamp — verifies MediaCodec init, SPS/PPS parsing, frame rendering |
| **Audio playback** | 48kHz stereo PCM sine wave on media purpose — verifies AudioTrack creation, purpose routing |
| **Session state machine** | IDLE → CONNECTING → BRIDGE_CONNECTED → PHONE_CONNECTED → STREAMING transitions |
| **Reconnection** | Kill and restart mock — app should reconnect, wait for IDR, resume cleanly |
| **Touch forwarding** | Touch events appear in mock bridge console output |
| **Settings UI** | Bridge IP, codec display, all tabs functional |
| **Stats overlay** | FPS counter, codec info, frame counts all update |
| **Media metadata** | Track title/artist/album cycling every 15s with album art |
| **Navigation state** | Maneuver cycling every 10s with colored nav images |
| **Cluster display** | Nav maneuvers forwarded to instrument cluster |

### What You Can't Test (Requires Real SBC + Phone)

- Actual AA phone session (Bluetooth, WiFi AP, aasdk)
- Real H.265/VP9 codec negotiation
- HFP call audio (B2)
- GNSS data from a real car (emulator GPS is simulated)
- Hardware video decoder behavior (Qualcomm C2)
- Real car VHAL properties that are permission-denied on Blazer EV (steering, lights, doors, HVAC)

### Setup

1. **Install ffmpeg in WSL** (one-time):
   ```bash
   wsl -d Ubuntu-24.04 -- sudo apt-get install -y ffmpeg
   ```

2. **Start the mock bridge** (from PowerShell):
   ```powershell
   # Default: 1920x1080 @ 30fps, stereo audio
   scripts\start-mock-bridge.ps1

   # Match car display resolution:
   scripts\start-mock-bridge.ps1 -Width 2628 -Height 800 -Fps 60

   # Video only (no audio):
   scripts\start-mock-bridge.ps1 -NoAudio
   ```

3. **Start the AAOS emulator and configure networking**:
   ```powershell
   # The mock bridge listens on localhost. Use adb reverse so the
   # emulator app can reach it:
   adb reverse tcp:5288 tcp:5288
   adb reverse tcp:5289 tcp:5289
   adb reverse tcp:5290 tcp:5290
   ```

4. **Configure app settings**: Set bridge IP to `127.0.0.1` (or `10.0.2.2` without adb reverse).

5. **Launch the app**: Video test pattern should appear, audio should play.

### Mock Bridge Console Output

The mock bridge logs all events to the terminal:

```
==================================================
  OpenAutoLink Mock Bridge
==================================================
  Control: 0.0.0.0:5288
  Video:   0.0.0.0:5290 (1920x1080 @ 30fps)
  Audio:   0.0.0.0:5289 (48kHz stereo)
  Source:  ffmpeg test pattern
==================================================
  Waiting for app connection...

[control] App connected from ('127.0.0.1', 54321)
[control] App hello: OpenAutoLink App 2628x800
[control] Simulated phone connection
[video] App connected from ('127.0.0.1', 54322)
[video] Sent codec config (42 bytes)
[video] Sent IDR frame #1 (8432 bytes)
[video] 150 frames, 30.0 fps
[audio] App connected from ('127.0.0.1', 54323)
```

### Using a Captured H.264 File

You can replay real video captured from the bridge instead of the test pattern:

```powershell
# Capture from the SBC (while a phone session is active):
ssh khadas@192.168.137.x "timeout 10 tcpdump -i eth0 port 5290 -w -" > capture.raw

# Play it back through the mock bridge:
scripts\start-mock-bridge.ps1 -VideoFile capture.h264
```

## 9. Testing in the Real Car

In-car testing is harder than emulator testing but critical at key milestones — it's the only way to validate real hardware decoders, audio routing, VHAL integration, and actual reconnection behavior.

### What's Different

- **No ADB access** — GM locks down ADB on production head units. No `logcat`, no `adb install`, no `adb reverse`
- **No debug APKs** — the app must be built as a **signed AAB** (Android App Bundle) and uploaded to the **Google Play Console internal/closed testing track** for every new build
- **No live debugging** — rely on the app's built-in diagnostics screen for status and error info
- **Bridge SSH still works** — the second USB NIC cable from your laptop to the SBC provides SSH access, same as in emulator testing. You can still deploy bridge updates, view logs, and restart services from your laptop while sitting in the car

### Deploying a Test Build

```powershell
# 1. Build signed AAB (prompts for keystore password)
.\scripts\bundle-release.ps1

# 2. Upload to Play Console
#    Play Console → OpenAutoLink → Testing → Internal testing → Create new release
#    Upload: app/build/outputs/bundle/release/app-release.aab
#    Roll out to internal testers

# 3. On the head unit: open Play Store → update the app
#    (or wait for auto-update if already installed)
```

> **Turnaround time:** Play Console internal testing typically processes builds in minutes, but there's no instant deploy path. Plan testing sessions accordingly.
>
> **Keystore:** The signing keystore lives at `secrets/upload-key.jks` (gitignored). Create one with `scripts\create-upload-keystore.ps1` if you don't have it yet.

### Bridge Access While In-Car

SSH into the SBC from your laptop the same way as on the bench — share WiFi to your USB NIC, discover the SBC via ARP, and connect:

```powershell
arp -a | Select-String "192.168.137"
ssh khadas@192.168.137.x
# View bridge logs live:
sudo journalctl -u openautolink.service -f
```

### What to Test In-Car (vs Emulator)

| Area | Why in-car matters |
|------|-------------------|
| **Hardware video decoding** | Real Qualcomm C2 decoders behave differently than emulator software decoders |
| **Audio routing** | Car audio system, steering wheel controls, multi-zone output |
| **Reconnection** | Real power cycle — ignition off/on, SBC cold boot |
| **Touch calibration** | Real touchscreen DPI, coordinate mapping on 2914×1134 display |
| **VHAL data** | Real vehicle speed, gear, parking state |
| **Network stability** | Real USB gadget/NIC behavior on the car's USB port |

This is why emulator testing handles the bulk of development — it's the only environment with full `adb` access for rapid iteration. In-car testing validates what the emulator can't.

## Troubleshooting

| Problem | Cause | Fix |
|---------|-------|-----|
| Emulator can't reach `192.168.222.222` | Port forwarding / reverse not set up | Run `adb reverse` commands (Section 3, Option B) |
| Bridge can't reach `192.168.222.108` | PC NIC missing secondary IP | Add `192.168.222.108` to the PC NIC on the bridge subnet |
| App shows "Connecting..." forever | Bridge not running or wrong IP in app | Check `systemctl status openautolink.service`; verify app bridge IP setting |
| Video renders but no audio | Audio purpose routing mismatch | Check logcat for AudioTrack errors; see [embedded-knowledge.md](embedded-knowledge.md) |
| Emulator is slow / video stutters | x86_64 emulated decoder limitations | Expected — emulator hardware decoding is weaker than the real head unit |
| SSH connection drops | SBC USB NIC lost / wrong cable | Verify USB NIC is `eth1+` not `eth0`; check `setup-eth-ssh.sh` logs |
