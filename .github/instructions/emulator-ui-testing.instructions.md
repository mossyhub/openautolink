---
description: "Use when programmatically testing the AAOS emulator UI via ADB: tapping buttons, navigating screens, verifying display modes, and automating swipe stress tests. Contains validated touch coordinates and navigation patterns for the BlazerEV AAOS emulator."
---
# Emulator UI Testing via ADB

## Prerequisites

```powershell
# Add platform-tools to PATH
$env:Path += ";$env:LOCALAPPDATA\Android\Sdk\platform-tools"

# Verify emulator is running
adb devices  # Should show emulator-5554

# Verify app is installed
adb shell pm list packages | Select-String openautolink
```

## Display Dimensions

- **BlazerEV AAOS**: 2914x1134, 200dpi (real car and emulator AVD should match)
- **Status bar**: 95px (top)
- **Nav bar**: 120px (bottom, AAOS dock)
- **Usable area (all bars visible)**: 2914x919

## Finding UI Elements Dynamically

AAOS touch coordinates shift depending on the display mode. **Always find elements dynamically** using uiautomator dumps:

```powershell
# Dump UI hierarchy to device
adb shell uiautomator dump /sdcard/ui.xml 2>$null

# Read and parse XML
$c = [string](adb shell cat /sdcard/ui.xml)

# Find element by content-desc (icon buttons)
$m = [regex]::Match($c, 'content-desc="Settings"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
$cx = [math]::Floor(([int]$m.Groups[1].Value+[int]$m.Groups[3].Value)/2)
$cy = [math]::Floor(([int]$m.Groups[2].Value+[int]$m.Groups[4].Value)/2)
adb shell input tap $cx $cy

# Find element by text
$m = [regex]::Match($c, 'text="Display"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')

# List all text elements
$ms = [regex]::Matches($c, 'text="([^"]+)"[^>]*bounds="(\[[^\]]*\]\[[^\]]*\])"')
$ms | ForEach-Object { echo "$($_.Groups[1].Value) -> $($_.Groups[2].Value)" }

# Find checked radio buttons
$ms | Where-Object { $_ -match 'checked="true"' }
```

## Projection Screen Navigation

### Floating overlay buttons (right side, draggable)

The right-side overlay column has these icon buttons. Positions shift with display mode and the user can drag the column; **always use dynamic lookup by `content-desc`**:

| Element | Typical Bounds (system_ui_visible) | Content Description | Notes |
|---------|------------------------------------|---------------------|-------|
| Settings gear | [2320,664][2384,720] | `Settings` | |
| Stats overlay | [2320,728][2384,784] | `Stats for nerds` | |
| Switch phone | [2790,967][2918,1031] | `Switch phone` | Opens the multi-phone chooser. Approx (2854, 999) when overlay column is bottom-right. |
| Info / version | varies | `Info` | |

The drag wall is at ~1/3 across the screen — the column snaps to the nearest left/right edge.

**IMPORTANT**: In fullscreen/immersive modes, buttons shift down since more vertical space is available. In `nav_bar_hidden`, the Settings button moves to ~[2320,760][2384,816]. Always use dynamic lookup.

### Tapping the Settings button
```powershell
adb shell uiautomator dump /sdcard/ui.xml 2>$null
$c = [string](adb shell cat /sdcard/ui.xml)
$m = [regex]::Match($c, 'content-desc="Settings"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
$cx = [math]::Floor(([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
$cy = [math]::Floor(([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
adb shell input tap $cx $cy
```

## Settings Screen Navigation

### Tab Sidebar (left edge, x ~40)

Tabs listed vertically. Approximate y-positions in `system_ui_visible` mode:

| Tab | Approx Y | Notes |
|-----|----------|-------|
| Connection | ~257 | Default tab |
| Phones | ~295 | Shows paired BT phones |
| Bridge | ~371 | WiFi, identity, restart |
| Display | ~447-492 | Display modes, drive side |
| Video | ~523 | Codec, FPS, resolution |
| Audio | ~561 | Audio source, mic |
| Diagnostics | ~637 | Log forwarding |

**Tab y-positions shift by ±40px depending on display mode**. Use dynamic lookup:

```powershell
adb shell uiautomator dump /sdcard/ui.xml 2>$null
$c = [string](adb shell cat /sdcard/ui.xml)
$m = [regex]::Match($c, 'text="Phones"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
$y = [math]::Floor(([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
adb shell input tap 40 $y
```

### Right pane content

Content starts at x=104. Radio buttons at x ~120-130, labels at x ~164+. The clickable row spans x=[104, ~1694].

## Multi-Phone & Direct-Mode Testing

The emulator's user-mode NAT (10.0.2.0/24) **cannot see real mDNS** on your home WiFi, so phones never auto-discover. The emulator CAN reach phones via outbound TCP through host NAT (`10.0.2.15 → 10.0.2.2 → host LAN`). Three ways to bootstrap a phone into discovery:

### A) `SET_PREF` broadcast → manualIp auto-inject (preferred, no UI)

The `SettingsReceiver` (manifest-registered, exported) lets you write any DataStore pref via ADB without launching settings UI. Setting `manual_ip_enabled=true` + `manual_ip_address=<phone-ip>` makes `ProjectionViewModel` inject the phone into discovery on next launch (`phone_id=debug_test_phone`, `name="Test Phone (debug)"`).

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
# App must be alive for the broadcast to be received (statically-registered
# receiver still needs the process running). Launch once, then SET_PREF, then
# force-stop + relaunch to apply.
& $adb -s emulator-5554 shell monkey -p com.openautolink.app -c android.intent.category.LAUNCHER 1
Start-Sleep -Seconds 3
& $adb -s emulator-5554 shell "am broadcast -a com.openautolink.app.SET_PREF --es key manual_ip_enabled --ez bvalue true com.openautolink.app"
& $adb -s emulator-5554 shell "am broadcast -a com.openautolink.app.SET_PREF --es key manual_ip_address --es svalue 192.168.1.174 com.openautolink.app"
# Verify receiver fired:
& $adb -s emulator-5554 logcat -d | Select-String 'SettingsRcv.*manual_ip'
# Restart so the new pref takes effect:
& $adb -s emulator-5554 shell am force-stop com.openautolink.app
& $adb -s emulator-5554 shell monkey -p com.openautolink.app -c android.intent.category.LAUNCHER 1
```

Full pref list and key names: see [SettingsReceiver.kt](app/src/main/java/com/openautolink/app/diagnostics/SettingsReceiver.kt) (KDoc header). Supports `aa_dpi`, `aa_resolution`, `video_codec`, `manual_ip_enabled`, `manual_ip_address`, `call_audio_via_car`, `bt_mac_override`, and more.

**Watch out**: `pm clear com.openautolink.app` does NOT reset manualIp on the next launch reliably — the prior value persists somehow (DataStore quirk). After `pm clear`, the very first launch may still inject the old IP. Always re-set via `SET_PREF` after a clear.

### B) `DEBUG_INJECT_PHONE` broadcast (multi-phone scenarios)

Register sites: `SessionManager.registerDebugReceiver()` — called from inside `startSession`, so **the receiver is only live after at least one session attempt**. For a clean cold-launch test, bootstrap with `SET_PREF`/manualIp first, then broadcast additional phones.

```powershell
adb shell "am broadcast -a com.openautolink.app.DEBUG_INJECT_PHONE \
  --es host 192.168.0.29 \
  --es phone_id samsung_test \
  --es name 'Samsung (debug)' \
  com.openautolink.app"
```

Extras: `host` (required), `port` (default 5277), `phone_id` (default `debug_inject_<host>`), `name` (default `Test Phone @ <host>`).

### C) `DEBUG_SIMULATE_SLEEP` broadcast (car-sleep/wake)

```powershell
adb shell "am broadcast -a com.openautolink.app.DEBUG_SIMULATE_SLEEP \
  --es duration_ms 60000 \
  com.openautolink.app"
```

Tears down the active session, sleeps for `duration_ms` (min 1000), then exercises the wake auto-reconnect path. Use to validate: clean idle, IDR-only first frame on resume, no audio pop.

### Phone chooser UI

Opens automatically when `defaultPhoneId` is blank (first install / data wipe), or after `PICKER_ESCALATION_THRESHOLD=2` consecutive auto-reconnect failures, or via the **Switch phone** overlay button.

Each phone row has:
- **ACTIVE** badge — currently in-session
- **DEFAULT** badge — `defaultPhoneId` in DataStore
- `Default` button — promote this phone to default (clickable if not already)
- `Forget` button — remove from `KnownPhonesStore`

Dynamic Default-button lookup (skip the one rendered as the **DEFAULT** badge — it's not clickable):

```powershell
adb shell uiautomator dump /sdcard/ui.xml 2>$null
adb pull /sdcard/ui.xml D:\temp\ui.xml | Out-Null
$c = Get-Content D:\temp\ui.xml -Raw
[regex]::Matches($c, 'text="Default"[^/]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') |
  ForEach-Object { $cx=([int]$_.Groups[1].Value+[int]$_.Groups[3].Value)/2; $cy=([int]$_.Groups[2].Value+[int]$_.Groups[4].Value)/2; "Default @ $cx,$cy" }
adb shell input tap <cx> <cy>
```

### Validated test scenarios

| Scenario | Setup | Expected log signature |
|---|---|---|
| **First-discovery auto-promote** | Uninstall app → install → set manualIp → launch | `No default phone yet — auto-promoting first discovered '<name>'` |
| **Default-arrives-first** | Default set, manualIp on, cold launch | `Resolved via mDNS within 3000ms` (no head-start log) — ~1s to STREAMING |
| **Non-default-first, default wins race** | Flip default to phone B in picker, cold launch, broadcast B inject within 500ms of resolve start | `Non-default '<A>' arrived first — waiting 500ms for default head-start` → `Default arrived during head-start — using <B>` |
| **Non-default-first, no default arrives** | Flip default to unreachable phone, cold launch | `Non-default '<A>' arrived first — waiting 500ms for default head-start` → `Default head-start elapsed — using <A>` (~524ms) |
| **Picker escalation** | Set default to unreachable IP, cold launch | 2 TCP failures → `Reconnect attempt 2 reached escalation threshold — opening chooser` |
| **Sleep/wake** | Active session → `DEBUG_SIMULATE_SLEEP` 5000 | Session torn down → auto-reconnect → first frame IDR (no artifact frames) |

### Race-timing tips

- Broadcasts via `am` have ~50-150ms slop. To land an inject inside the 500ms head-start window, use a `Start-Sleep -Milliseconds <2050±100>` after `monkey` launch (resolve typically starts ~2050ms after launch).
- The `manualIpAddress` pref watcher auto-injects ~700-900ms after app launch — this is **always the first phone in discovery** unless you suppress it first by clearing the pref.
- `phoneDiscovery.phones` is a debounced flow; emissions can lag the broadcast by ~50ms.

## Display Mode Testing

### Expected surface dimensions per mode

### Expected surface dimensions per mode

| Mode | Surface Size | Calculation |
|------|-------------|-------------|
| `system_ui_visible` | 2914x919 | 1134 - 95 (status) - 120 (nav) |
| `status_bar_hidden` | 2914x1014 | 1134 - 120 (nav only) |
| `nav_bar_hidden` | 2914x1039 | 1134 - 95 (status only) |
| `fullscreen_immersive` | 2914x1134 | Full screen, no bars |
| `custom_viewport` | 2914x1134 | Full screen + custom Modifier.size |

### Testing a display mode

```powershell
# 1. Navigate to Settings > Display tab
# 2. Tap the radio button for the desired mode (find y dynamically)
# 3. Restart the app
adb shell am force-stop com.openautolink.app
adb logcat -c
adb shell am start -n com.openautolink.app/.MainActivity 2>$null
Start-Sleep -Seconds 3

# 4. Verify mode was applied
adb logcat -d | Select-String 'applyDisplayMode'

# 5. Verify surface dimensions
adb logcat -d | Select-String 'Surface attached' | Select-Object -Last 3
```

### AAOS system bars

AAOS uses `TopCarSystemBar` and `BottomCarSystemBar` (not standard Android bars). The `WindowInsetsControllerCompat` hides/shows these. On the emulator, bars may appear transparent but still consume inset space unless the app opts out of `windowInsetsPadding`.

## Video Stress Testing

### Swipe patterns for map movement

```powershell
# Horizontal swipes (left-right panning)
adb shell input swipe 1800 480 400 480 100   # fast left
adb shell input swipe 400 480 1800 480 100   # fast right

# Vertical swipes
adb shell input swipe 1200 200 1200 700 100  # down
adb shell input swipe 1200 700 1200 200 100  # up

# Diagonal (maximum scene change)
adb shell input swipe 1800 200 200 700 30    # extreme diagonal 30ms
```

### Full stress test (120 swipes)
```powershell
adb logcat -c
for ($i = 0; $i -lt 30; $i++) {
    adb shell input swipe 1800 480 400 480 100
    adb shell input swipe 400 480 1800 480 100
    adb shell input swipe 1200 200 1200 700 100
    adb shell input swipe 1200 700 1200 200 100
}
```

### Analyzing results
```powershell
$logs = adb logcat -d -t 5000 | Select-String 'MediaCodecDecoder:' |
    Where-Object { $_ -notmatch 'Surface attached|at com\.|SharedFlow|BaseContinuation|ThreadPool|DispatchedTask|java.lang.Thread' }

$drops = ($logs | Select-String 'Decoder behind').Count
$idrs = ($logs | Select-String 'IDR keyframe').Count
$badState = ($logs | Select-String 'bad state').Count

echo "Drop events: $drops"
echo "IDR keyframes: $idrs"
echo "Bad state errors: $badState"
```

### Emulator decoder limitations

The emulator uses `c2.goldfish.h264.decoder` (software). Some drops during extreme movement (30ms diagonal swipes) are expected. Real HW decoders (`c2.qti.avc.decoder` on Snapdragon) handle 1080p60 in <1ms.

## Bridge Connectivity (ADB reverse + port proxy)

The emulator can't reach 192.168.222.222 directly. Use ADB reverse + Windows port proxy:

```powershell
# One-time setup: Windows port proxy (localhost → SBC bridge ports).
# Survives reboots. Run once from an admin PowerShell:
netsh interface portproxy add v4tov4 listenport=5288 listenaddress=127.0.0.1 connectport=5288 connectaddress=192.168.222.222
netsh interface portproxy add v4tov4 listenport=5289 listenaddress=127.0.0.1 connectport=5289 connectaddress=192.168.222.222
netsh interface portproxy add v4tov4 listenport=5290 listenaddress=127.0.0.1 connectport=5290 connectaddress=192.168.222.222

# Verify:
netsh interface portproxy show v4tov4

# Per-session: ADB reverse (emulator localhost → host localhost)
adb reverse tcp:5288 tcp:5288
adb reverse tcp:5289 tcp:5289
adb reverse tcp:5290 tcp:5290
```

Set the app's bridge IP to `127.0.0.1` in Settings > Connection.

> **Do NOT use SSH tunnels.** The SBC is directly reachable from the laptop via the USB NIC on the 192.168.222.x subnet. The port proxy handles the localhost→SBC hop without an active SSH session.

## Bridge-Side Verification

```powershell
# Check bridge service status
ssh openautolink 'systemctl is-active openautolink.service openautolink-bt.service openautolink-wireless.service'

# Check video stats (0 drops = healthy)
ssh openautolink "sudo journalctl -u openautolink.service --since '2 min ago' --no-pager | grep 'OAL.*video'" | Select-Object -Last 5

# Check paired phones
ssh openautolink "bluetoothctl devices Paired"

# Check TCP listeners
ssh openautolink "ss -tlnp | grep -E '5288|5289|5290'"
```

## App Lifecycle

```powershell
# Launch
adb shell am start -n com.openautolink.app/.MainActivity

# Force stop (clean restart)
adb shell am force-stop com.openautolink.app

# Check if running
adb shell "dumpsys activity activities | grep openautolink.*Resumed"

# Main activity class
com.openautolink.app/.MainActivity
```
