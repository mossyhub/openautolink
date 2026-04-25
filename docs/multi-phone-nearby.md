# Multi-Phone Support — Nearby Connections Plan

**Date**: 2026-04-24  
**Status**: Planning  
**Scope**: Phone identification, default phone auto-connect, phone switching UI

---

## Problem

Two phones of the same make/model (e.g., two OnePlus 13s) both advertise as `"CPH2655"` via Nearby Connections. They are indistinguishable. Additionally:

- Nearby endpoint IDs are **random per-session** — not stable across reboots
- `DiscoveredEndpointInfo` only exposes `endpointName` (string) and `serviceId` (string) — no MAC, no serial, no fingerprint
- OAL currently auto-connects to the **first discovered endpoint** with no chooser UI
- `lastDeviceName` is in-memory only — not persisted across app restarts
- The current Wireless Helper companion app advertises `Build.MODEL` as the endpoint name

---

## Solution: Three Layers

### Layer 1: Fix Phone Names (Wireless Helper change)

**Change**: In `external/wireless-helper/.../StrategyNearby.kt`, change the advertised endpoint name from `Build.MODEL` to the user-set device name:

```kotlin
// Before:
val endpointName = android.os.Build.MODEL  // "CPH2655"

// After:
val deviceName = Settings.Global.getString(context.contentResolver, "device_name")
val endpointName = if (deviceName.isNullOrBlank() || deviceName == Build.MODEL) {
    // No custom name set — append ANDROID_ID suffix for uniqueness
    "${Build.MODEL} (${Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).take(4)})"
} else {
    deviceName  // "John's OnePlus 13"
}
```

**Result**: 
- If user has a custom device name (Settings → About → Device Name): shows that (e.g., "John's OnePlus 13")
- If not customized: shows model + 4-char unique suffix (e.g., "CPH2655 (a3f2)" vs "CPH2655 (7b1c)")
- Two identical phones are always distinguishable

**Effort**: 1 line change + rebuild Wireless Helper APK

---

### Layer 2: Default Phone + Persistent Auto-Connect (OAL app change)

#### New Preference
| Key | Type | Default | Purpose |
|-----|------|---------|---------|
| `default_phone_name` | String | `""` | Name of the phone to auto-connect to |

#### Auto-Connect Logic
```
Discovery starts:
  ├─ default_phone_name is set?
  │   ├─ YES: Only auto-connect if discovered name == default_phone_name
  │   │       (ignore other phones)
  │   └─ NO: Auto-connect to first discovered phone
  │          Save its name as default_phone_name
  │
  └─ default_phone_name set but phone not found after 30s?
      └─ Show status: "Waiting for [name]..."
         (don't auto-connect to other phones — user must tap "Switch Phone" to pick a different one)
```

#### Changes Required
1. **AppPreferences**: Add `default_phone_name` string preference
2. **AaNearbyManager**: 
   - Persist `lastDeviceName` to/from `default_phone_name` preference
   - Only auto-connect when name matches (or no default set)
3. **Settings UI (Connection tab)**: Show "Default Phone: [name]" with "Clear" button
4. **ProjectionUiState**: Add `phoneName` (already exists) — ensure it shows the friendly name

---

### Layer 3: Phone Chooser Overlay + Switch Button (OAL app change)

#### Switch Phone Button
Re-introduce from bridge/main branch — a floating overlay button on the projection screen (next to Settings and Stats buttons). Icon: swap/switch arrows.

Visible when:
- Session is STREAMING (active projection) — allows switching mid-session
- Session is IDLE/CONNECTING — allows picking a phone before connecting

#### Tap Flow
```
User taps "Switch Phone" button
  ├─ If streaming: disconnect current session
  ├─ Restart Nearby discovery with autoConnect = false
  ├─ Show phone chooser overlay (slide-in panel, like settings)
  │   ├─ "Searching for phones..." with spinner
  │   ├─ Live-updating list of discovered phones:
  │   │   ├─ Phone name (friendly, from Layer 1)
  │   │   ├─ Signal indicator (if available)
  │   │   └─ "Default" badge on the current default phone
  │   ├─ Tap a phone → connect to it
  │   │   ├─ Save as new default_phone_name
  │   │   └─ Close chooser → session starts
  │   └─ "Cancel" button → close chooser, restart auto-connect to default
  │
  └─ If only 1 phone found after 3s and no default set:
      └─ Auto-select it (skip chooser)
```

#### Chooser UI Design
- Semi-transparent overlay panel (like the settings panel — slides in from right)
- Centered card with phone list
- Each row: phone icon + name + "Default ★" badge if default
- Tap to connect immediately
- Background: projection surface stays visible (dimmed)

---

## Future: OAL Companion App

Writing our own companion app (replacing Wireless Helper) would enable:

| Capability | Wireless Helper | OAL Companion |
|-----------|----------------|---------------|
| Custom advertise name | ✅ (Layer 1 change) | ✅ Built-in |
| Unique phone fingerprint | ✅ (ANDROID_ID suffix) | ✅ |
| Phone-side "pair with car" UI | ❌ | ✅ Show car name, confirm |
| Phone-initiated connect | ❌ | ✅ Phone finds car |
| Phone battery/status push | ❌ | ✅ Before AA starts |
| Auto-start on BT proximity | ❌ | ✅ Detect car BT |
| Version compatibility check | ❌ | ✅ Verify OAL version |
| Custom protocol extensions | ❌ | ✅ Pre-AA metadata exchange |

**Verdict**: Fix Wireless Helper now (trivial). Plan OAL Companion as a separate project when multi-phone is a real daily need.

---

## Implementation Priority

| Phase | What | Effort | Milestone |
|-------|------|--------|-----------|
| **1** | Fix Wireless Helper endpoint name | Trivial | Next build |
| **2** | Add `default_phone_name` + persist auto-connect | Low | Next build |
| **3** | Show default phone in Connection settings | Low | Next build |
| **4** | Phone chooser overlay + Switch button | Medium | v0.2.x |
| **5** | OAL Companion app | Large | v0.3.x |

---

## Technical Notes

### Nearby Connections API Constraints
- `endpointId`: Random 4-char string, changes every advertising session. **Cannot** be used for persistent identification.
- `endpointName`: Set by the advertiser's first argument to `startAdvertising()`. This is the only stable, human-readable identifier.
- `serviceId`: Always `"com.andrerinas.hurev"` (shared with HUR for compatibility).
- No MAC address, serial number, or hardware fingerprint is exposed by the API.

### Service ID Compatibility
All three apps (OAL, Wireless Helper, HUR) use `"com.andrerinas.hurev"` as the Nearby service ID. Changing this would break compatibility with HUR users. If we eventually make our own companion app, we could use a dual-service-ID approach (discover both `"com.andrerinas.hurev"` and `"com.openautolink"` service IDs).

### Wireless Helper Deep Link
Wireless Helper already supports `wirelesshelper://start?mode=nearby` to auto-start Nearby advertising. This is used by our automated tests. The companion app would support similar intents.
