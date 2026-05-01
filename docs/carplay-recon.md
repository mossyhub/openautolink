# CarPlay Recon — Investigation Plan

## Goal

Determine whether the GM AAOS head unit has a functioning CarPlay stack (MFi chip, iAP2 daemon, CarPlay app) that could potentially be activated, or whether the installed CarPlay APK is a stub gated by missing hardware.

## Constraints

- **No ADB** on production GM head units
- All investigation must run from our deployed app (sideloaded via Play Store)
- Results streamed to the existing TCP listener (port 5288) or displayed on-screen
- iPhone must be USB-connected during scan for USB enumeration checks

## Architecture

### Hidden Screen Access

- Long-press (3+ seconds) on the "Debug" tab label in Diagnostics → navigates to the CarPlay Recon screen
- Not discoverable by casual users, not in any nav menu
- Separate ViewModel (`CarPlayReconViewModel`) to keep the existing DiagnosticsViewModel clean

### Screen Layout

Single scrollable report with a **"Run Full Scan"** button at the top. Each probe section shows:
- Section header (e.g., "Package Scan")
- Status indicator (pending / running / done / error)
- Results as monospace text block (copyable)

A **"Send to TCP"** button sends the full report to any connected TCP listener client.

## Probe Checklist

### 1. Package Scan
**What**: `PackageManager.getInstalledPackages(GET_ACTIVITIES | GET_SERVICES | GET_RECEIVERS | GET_PROVIDERS)` filtered for packages matching `carplay`, `apple`, `iap`, `projection`.

**Returns**: For each match:
- Package name, version, install path (`sourceDir`, `nativeLibraryDir`)
- All activities, services, receivers, providers with their intent filters
- Required permissions (both declared and `uses-permission`)
- Whether components are exported

**Why**: Reveals the IPC surface — what services exist, what intents they respond to, what permissions gate access.

### 2. System Properties Dump
**What**: `Runtime.exec("getprop")` → full property dump, filtered to interesting prefixes:
```
ro.vendor.carplay    persist.vendor.carplay
ro.vendor.iap        persist.sys.carplay
ro.vehicle           ro.boot.carline
ro.build.display     ro.product.model
ro.hardware          ro.board.platform
persist.vendor.apple persist.sys.projection
```

**Returns**: Key=value pairs for all matching properties.

**Why**: CarPlay enable/disable is often gated by `ro.vendor.carplay.enabled` or similar build props. Vehicle trim/region props may reveal configuration differences.

### 3. Filesystem Scan
**What**: `Runtime.exec("ls -laR <path>")` on world-readable directories:
```
/system/etc/init/           → init .rc service definitions
/vendor/etc/init/           → vendor init services
/system/etc/permissions/    → hardware feature declarations
/vendor/etc/permissions/
/vendor/etc/                → config files
/system/lib64/              → system shared libraries
/vendor/lib64/              → vendor shared libraries (filtered to *carplay* *iap* *mfi* *apple*)
/odm/etc/init/              → ODM init (GM-specific)
```

**Returns**: Directory listings for each path, plus file contents for any `.rc` files that mention carplay/iap/mfi/apple.

**Why**: Init `.rc` files define which daemons start at boot. Feature XMLs declare `com.apple.carplay` or similar hardware features. Native libraries (`libmfi.so`, `libiap2.so`) confirm the protocol stack exists.

### 4. Process Scan
**What**: Walk `/proc/*/cmdline` and `/proc/*/comm` for all readable PIDs.

**Returns**: List of all running processes, highlighted matches for: `carplay`, `iap`, `mfi`, `apple`, `projection`.

**Why**: If an MFi/iAP daemon is running, the hardware stack is alive — the question becomes "why won't it talk to us?" rather than "does it exist?"

### 5. Service Manager Query
**What**: Attempt `android.os.ServiceManager.listServices()` via reflection (hidden API). Also try `Runtime.exec("service list")` and `Runtime.exec("dumpsys -l")`.

**Returns**: List of registered system services, filtered to interesting names.

**Why**: CarPlay may register as a system service (e.g., `carplay`, `iap2`, `apple.auth`). This is the binder namespace — tells you what IPC endpoints exist.

### 6. USB Device Enumeration
**What**: `UsbManager.getDeviceList()` — enumerate all connected USB devices with:
- Vendor ID, Product ID
- Device class, subclass, protocol
- Interface descriptors (class, subclass, protocol for each)
- Whether an interface is claimed

**Returns**: Full USB tree. iPhone iAP2 interface shows as vendor class 0xFF.

**Why**: Confirms whether the iPhone's iAP2 interface is visible to the system and whether anything has claimed it. If no 0xFF interface appears, the USB controller may not be routing the phone to the AAOS side.

### 7. Service Bind Attempt
**What**: For each exported service found in Probe 1, attempt `bindService()` with an explicit `ComponentName`.

**Returns**: For each attempt:
- `SecurityException` → permission-gated (what permission?)
- Successful bind → call `IBinder.dump()` if possible
- `null` / not found → service is declared but not running

**Why**: A successful bind means the service is alive. A `SecurityException` tells you exactly which permission you're missing (could be signature-only = dead end, or could be a grantable permission).

### 8. Broadcast / Intent Probe
**What**: Send common projection-related broadcasts and start activities with diagnostic extras:
```
am broadcast -a com.gm.carplay.ACTION_CHECK_STATUS
am start -n <carplay-activity> --ez debug true
```
(Via `Runtime.exec()` or `context.sendBroadcast()`)

**Returns**: Logcat output captured immediately after each attempt (via `Runtime.exec("logcat -d -t 50")` filtered to carplay/iap tags).

**Why**: Many OEM projection apps honor debug intents. Even failure logs reveal the internal state machine.

### 9. Hardware Feature Check
**What**: `PackageManager.hasSystemFeature()` for:
```
com.apple.carplay
android.hardware.usb.accessory
android.hardware.usb.host
com.gm.carplay
```
Also dump `/system/etc/permissions/*.xml` for any feature declarations.

**Returns**: Boolean per feature + raw XML content of matching permission files.

**Why**: If `com.apple.carplay` is declared as a system feature, GM explicitly configured this build for CarPlay hardware.

### 10. APK Extraction
**What**: For each matching package from Probe 1:
1. Read `applicationInfo.sourceDir` (e.g., `/data/app/com.gm.carplay-xxx/base.apk`)
2. Read `applicationInfo.nativeLibraryDir` (e.g., `/data/app/com.gm.carplay-xxx/lib/arm64`)
3. Copy the APK bytes via `FileInputStream` → stream over the TCP listener to a connected client

**Implementation**:
- APK files are world-readable on Android (they must be, so the system can verify signatures)
- `nativeLibraryDir` is also world-readable
- Transfer protocol: simple length-prefixed binary frame over the existing TCP listener
  - Client sends `GET_APK:<package_name>\n`
  - Server responds with `APK:<filename>:<length>\n` + raw bytes
  - Repeat for each `.so` file in the native lib dir
- Alternative: if TCP transfer is flaky, just report the file paths and sizes — user can `adb pull` from a different device if they ever get ADB, or we can write the APK to our app's external storage and retrieve it via the companion app + Nearby

**Why**: With the APK on your laptop, you can run `apktool d`, `jadx`, `strings`, Ghidra on the native libs. This reveals:
- Every string ("compatible phone not connected" → find the code path that triggers it)
- AIDL/proto definitions for the CarPlay service IPC
- Hardcoded intent actions, broadcast receivers, content provider URIs
- MFi chip communication code (I²C addresses, ioctl calls, HAL interface)

**Is this really possible?** Yes. Every installed APK on Android is stored in a world-readable directory. The path from `applicationInfo.sourceDir` is readable by any app. The bytes can be read with a standard `FileInputStream`. No root, no ADB, no special permissions needed. The only question is size (CarPlay APKs could be 50-100MB with native libs) — streaming over TCP at LAN speed is fine.

## Output Format

The full scan produces a single text report:
```
=== CarPlay Recon Report ===
Date: 2026-05-01 14:30:00
Device: <model> / <manufacturer> / SDK <level>

--- PACKAGES ---
[package scan results]

--- PROPERTIES ---
[filtered getprop output]

--- FILESYSTEM ---
[directory listings]

--- PROCESSES ---
[process list with highlights]

--- SERVICES ---
[service list]

--- USB DEVICES ---
[USB tree]

--- BIND ATTEMPTS ---
[results per service]

--- BROADCASTS ---
[logcat captures]

--- FEATURES ---
[feature checks]

--- APK PATHS ---
[paths + sizes for extraction]
```

This report is:
1. Displayed on-screen in a scrollable monospace view
2. Available via "Send to TCP" button → streams to connected laptop
3. Saved to app internal storage for later retrieval

## Implementation Plan

### Files to Create

| File | Purpose |
|------|---------|
| `app/.../ui/diagnostics/carplay/CarPlayReconScreen.kt` | Compose UI — single scrollable report |
| `app/.../ui/diagnostics/carplay/CarPlayReconViewModel.kt` | ViewModel — runs probes, manages state |
| `app/.../ui/diagnostics/carplay/CarPlayReconScanner.kt` | Scanner logic — each probe as a suspend function |

### Files to Modify

| File | Change |
|------|--------|
| `DiagnosticsScreen.kt` | Add long-press on Debug tab → navigate to recon screen |
| `AppNavHost.kt` | Add route for recon screen (or render as overlay like diagnostics) |

### Probe Execution

All probes run sequentially on `Dispatchers.IO` to avoid overwhelming the system. Each probe updates its section in the UI state as it completes. The full scan takes ~10-30 seconds depending on filesystem size.

### TCP Transfer Protocol (for APK extraction)

When a laptop client connects to port 5288 and sends `RECON_REPORT\n`, the app streams the full text report. When the client sends `GET_APK:<package>\n`, the app streams the APK binary. This reuses the existing TCP listener infrastructure.

## Go / No-Go Decision Tree

```
Scan results → Decision:

No carplay packages at all          → GM stripped it from this build. Dead end.
Package exists but no native libs   → Stub APK, no protocol stack. Dead end.
Package + native libs + no daemon   → Stack installed but not started. Check .rc files for enable conditions.
Package + daemon + no MFi refs      → Software-only projection? Unusual. Investigate further.
Package + daemon + MFi lib/service  → Full stack present. Focus on why auth fails.
  └── Feature flag disabled         → Try toggling (if persist.* → setprop via exploit? Unlikely without root)
  └── Signature permission          → Can't bind. Would need platform cert. Dead end without root.
  └── Missing USB routing           → Phone's iAP2 interface not reaching AAOS. Hardware routing issue.
  └── MFi chip present but no auth  → Chip exists, investigate I²C addresses in .so files via Ghidra.
```

## Risk Assessment

- **Zero risk to vehicle**: All probes are read-only (filesystem reads, package queries, USB enumeration). The bind attempt is the only "active" probe, and Android's security model prevents any damage from a failed bind.
- **Privacy**: The report may contain device identifiers (serial, IMEI if exposed). Keep reports local / on your own network only.
- **App size**: No impact — this is pure Kotlin code, no new native libs or assets.
