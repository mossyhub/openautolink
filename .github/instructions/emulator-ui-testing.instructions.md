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

- **BlazerEV AAOS emulator**: 2400x960 physical, 160dpi
- **Status bar**: 76px (top)
- **Nav bar**: 96px (bottom, AAOS dock)
- **Usable area (all bars visible)**: 2400x788

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

### Button positions (system_ui_visible mode, typical)

| Element | Typical Bounds | Content Description |
|---------|---------------|---------------------|
| Settings gear | [2320,664][2384,720] | `Settings` |
| Stats overlay | [2320,728][2384,784] | `Stats for nerds` |
| Phone switcher | [2320,792][2384,848] | `Switch phone` |

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

## Display Mode Testing

### Expected surface dimensions per mode

| Mode | Surface Size | Calculation |
|------|-------------|-------------|
| `system_ui_visible` | 2400x788 | 960 - 76 (status) - 96 (nav) |
| `status_bar_hidden` | 2400x864 | 960 - 96 (nav only) |
| `nav_bar_hidden` | 2400x884 | 960 - 76 (status only) |
| `fullscreen_immersive` | 2400x960 | Full screen, no bars |
| `custom_viewport` | 2400x960 | Full screen + custom Modifier.size |

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

## Bridge Connectivity (SSH tunnel for emulator)

The emulator can't reach 192.168.222.222 (bridge car network). Use ADB reverse + SSH tunnel:

```powershell
# ADB reverse: emulator localhost → host localhost
adb reverse tcp:5288 tcp:5288
adb reverse tcp:5289 tcp:5289
adb reverse tcp:5290 tcp:5290

# SSH tunnel: host localhost → VIM4 bridge ports
ssh -N -L 5288:localhost:5288 -L 5289:localhost:5289 -L 5290:localhost:5290 openautolink
```

Set the app's bridge IP to `127.0.0.1` in Settings > Connection.

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
