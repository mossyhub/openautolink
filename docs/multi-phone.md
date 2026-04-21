# Multi-Phone Support — Feature Specification

## Overview

OpenAutoLink supports pairing multiple Bluetooth phones with the bridge, with one phone active at a time. This document defines the as-built design, expected behavior, and edge cases for multi-phone management.

**Invariant**: Android Auto is a 1:1 protocol. Multi-phone means "multiple paired, one active at a time, with seamless switching." This document never attempts parallel AA sessions.

---

## Architecture

```
                              ┌──────────────────────────────────────────────┐
                              │            Bridge (SBC)                      │
                              │                                              │
  Phone A ──BT──┐             │  aa_bt_all.py                               │
                │             │    • RFCOMM gating (NewConnection)           │
                ├──RFCOMM──▶  │    • WiFi credential exchange               │
                │             │    • Preferred-phone probe (startup)         │
  Phone B ──BT──┘             │    • Reconnect worker (daemon thread)        │
                              │    • Switch override file reader             │
                              │                                              │
  (only 1 at a time) ──WiFi─▶│  openautolink-headless (C++)                 │
                              │    • handle_switch_phone()                   │
                              │    • handle_forget_phone()                   │
                              │    • handle_list_paired_phones()             │
                              │    • handle_set_pairing_mode()               │
                              │    • config_echo → default_phone_mac         │
                              │    • OAL_DEFAULT_PHONE_MAC in env            │
                              └──────TCP:5288/5289/5290──────────────────────┘
                                              │
                                              ▼
                              ┌──────────────────────────────────────────────┐
                              │            Car App (AAOS)                    │
                              │                                              │
                              │  Settings Screen                             │
                              │    • Paired phone list (refresh/switch/forget)│
                              │    • Default phone MAC selection             │
                              │    • Pairing mode toggle                     │
                              │                                              │
                              │  Projection Screen                           │
                              │    • Phone switcher popup (overlay button)   │
                              │    • Quick switch during active streaming    │
                              └──────────────────────────────────────────────┘
```

---

## Core Requirements

### R1 — First-Paired Phone Is the Default

When a phone completes BT pairing for the first time and `OAL_DEFAULT_PHONE_MAC` is empty (no default set yet), the bridge MUST automatically set this phone as the default. This ensures that single-phone users never need to touch the "default phone" setting.

**Implementation**:
- In `aa_bt_all.py`, after a successful RFCOMM WiFi credential exchange (`handle_aa_rfcomm` completes), check if `OAL_DEFAULT_PHONE_MAC` is empty. If so, write the phone's MAC to `/etc/openautolink.env` as `OAL_DEFAULT_PHONE_MAC=<mac>`.
- The bridge C++ process reads this on next `config_echo`.

**Current state**: Not implemented. The default phone must be set manually in Settings.

### R2 — Default Phone Selection Lives on the Bridge

The bridge is the single source of truth for the default phone. The env var `OAL_DEFAULT_PHONE_MAC` in `/etc/openautolink.env` is the canonical store.

**Flow**:
1. App sends `config_update` with `default_phone_mac` field
2. Bridge persists to `/etc/openautolink.env` via sed upsert (in `handle_config_update`)
3. Bridge sends `config_echo` with the current value
4. BT script reads via `_preferred_bt_mac()` — re-reads the file on every call (no cache)

**Why bridge-side**: The app may be reinstalled, multiple head units may connect to the same bridge, and the bridge's BT stack is what enforces the preference. The app mirrors this value locally in DataStore for display purposes only.

**Current state**: ✅ Implemented. The app's `SettingsViewModel.updateDefaultPhoneMac()` sends `config_update` to the bridge and also writes to local DataStore. The bridge persists and echoes it back.

### R3 — Pairing During Active Streaming

When Phone A is actively paired, connected, and streaming, and a new Phone B enters pairing mode:

1. **Pairing MUST succeed** — BlueZ accepts the pairing via the `NoInputNoOutput` agent. The new device appears in `bluetoothctl devices Paired`.
2. **WiFi/AA handoff MUST NOT happen** — The BT script's RFCOMM gate (`AAProfile.NewConnection`) rejects Phone B's RFCOMM attempt because Phone A (the default) is currently connected. Phone B gets no WiFi credentials, so it cannot establish an AA session.
3. **Phone B is BT-disconnected after rejection** — After RFCOMM rejection, the BT script disconnects Phone B from BT entirely (2s delayed `bluetoothctl disconnect`). This prevents Phone B's HFP/A2DP audio from bleeding into the bridge as a dead end.
4. **No disruption to Phone A** — Phone A's BT, WiFi, and AA session continue uninterrupted.
5. **Phone B is available for future switching** — The new device appears in the paired phones list. The user can switch to it later via the UI.

**RFCOMM rejection conditions** (in priority order):
1. Switch override active and connecting phone isn't the target → reject
2. Within startup grace period, preferred phone set, and this isn't it (unless probe confirmed preferred is offline) → reject
3. Preferred/default phone is currently BT-connected and this is a different phone → reject

**Current state**: ✅ Partially implemented. Rejection condition 3 covers this case. However, if no default phone is set, any phone can RFCOMM through while another is connected — the gating only triggers when a default phone is configured. See [R1](#r1--first-paired-phone-is-the-default) for why auto-setting the default matters.

### R4 — Single-Phone Reliability Is Sacred

Multi-phone logic MUST NOT degrade the single-phone experience. All multi-phone code paths must be effectively no-ops when only one phone is paired.

**Design constraints**:
- The startup grace period (`DEFAULT_GRACE_SEC = 10s`) MUST NOT delay the only paired phone. When `OAL_DEFAULT_PHONE_MAC` is set and matches the only paired phone, it passes through immediately (it IS the default).
- The reconnect worker with no default set or only one paired device simply tries all devices — identical to pre-multi-phone behavior.
- The preferred-phone probe is skipped when `OAL_DEFAULT_PHONE_MAC` is empty.
- The switch override file is only created by explicit user action (`switch_phone` command).

**Verification**: Single-phone scenarios to continuously test:
1. Clean pair + first connect (no prior state)
2. Car power cycle reconnect (bridge boots, phone reconnects)
3. Phone power cycle reconnect (phone reboots, bridge reconnects)
4. Phone walks away and returns (BT disconnect → reconnect)
5. App reinstall (no DataStore state, bridge still has env config)

**Current state**: ✅ Implemented. The grace period only applies to non-default phones. The probe is only started when a default is set. Single-phone users are unaffected.

### R5 — Startup Phone Selection with Fallback

When the bridge boots with multiple paired phones nearby:

**Primary behavior**: Only allow the default phone to connect for a grace period.

**Fallback**: If the default phone is not present, allow other phones through without excessive delay.

**Implementation** (as-built):

```
Bridge boots
  ├─ OAL_DEFAULT_PHONE_MAC is set?
  │   ├─ YES: Start preferred-phone probe (async BT Connect attempt, 8s D-Bus timeout)
  │   │   ├─ Probe says REACHABLE: Only allow default phone (reject others at RFCOMM)
  │   │   ├─ Probe says UNREACHABLE: Grace period bypassed for non-default phones
  │   │   └─ Probe still running + grace period active: Reject non-default phones
  │   └─ Grace period (10s) expires: Non-default phones allowed if default not connected
  │
  └─ NO default set: All phones allowed, first to RFCOMM wins
```

**Reconnect worker fallback** (separate from RFCOMM gate):
- Tries preferred phone first for 3 consecutive cycles (~45s of failures)
- After 3 misses, also tries non-default paired devices
- If switch override is active, only tries the override target

**Current state**: ✅ Implemented with the probe + grace period mechanism. The probe prevents the full 10s wait when the default phone is clearly not nearby (e.g., at home while car is at work).

### R6 — Active Phone Switching

When the default phone is actively streaming and the user switches to another phone:

**Required sequence**:
1. User taps Phone B in the projection overlay or settings screen
2. App sends `switch_phone` with Phone B's MAC to bridge
3. Bridge writes switch override file (`/run/openautolink/switch_override`) with Phone B's MAC + 90s expiry
4. Bridge sends AA `ByeByeRequest` to Phone A (graceful AA session teardown)
5. Bridge disconnects all BT devices except Phone B (prevents Phone A auto-reconnect during the window)
6. Bridge initiates BT connect to Phone B
7. BT script's RFCOMM gate sees the switch override → allows Phone B, rejects Phone A if it tries to reconnect
8. Phone B completes RFCOMM → receives WiFi credentials → connects WiFi → TCP → AA handshake
9. Switch override is cleared when Phone B completes RFCOMM exchange
10. App receives `phone_connected` with Phone B's name

**Critical**: Phone A MUST NOT auto-reconnect during this window. The switch override file is the mechanism that prevents this — the BT script rejects Phone A's RFCOMM attempts while the override is active.

**AA environment**: All phones use the same AA configuration (resolution, codec, DPI, FPS). Per-phone AA settings are not supported and would add complexity without clear benefit.

**Current state**: ✅ Implemented. The full flow works end-to-end with graceful disconnect, override file, and BT gating.

---

## Protocol Messages

### App → Bridge

| Type | Fields | Description |
|------|--------|-------------|
| `list_paired_phones` | (none) | Request BT paired device list |
| `switch_phone` | `mac: string` | Disconnect current phone, connect target |
| `forget_phone` | `mac: string` | Unpair + remove device from BlueZ |
| `set_pairing_mode` | `enabled: bool` | Toggle BT discoverable/pairable |
| `config_update` | `default_phone_mac: string` | Set preferred phone for auto-reconnect |

### Bridge → App

| Type | Fields | Description |
|------|--------|-------------|
| `paired_phones` | `phones: [{mac, name, connected}]` | All paired devices with status |
| `pairing_mode_status` | `enabled: bool` | Current pairing mode |
| `phone_connected` | `phone_name, phone_type` | AA session started |
| `phone_disconnected` | `reason: string` | AA session ended |
| `config_echo` | `default_phone_mac: string` | Current default phone setting |

---

## State & Storage

| Data | Location | Read By | Written By |
|------|----------|---------|------------|
| Default phone MAC | `/etc/openautolink.env` (`OAL_DEFAULT_PHONE_MAC`) | BT script, bridge C++ | Bridge C++ (via `config_update`) |
| Paired device list | BlueZ internal (`bluetoothctl devices Paired`) | Bridge C++ (shells out) | BlueZ (on pair/remove) |
| Switch override | `/run/openautolink/switch_override` (tmpfs) | BT script | Bridge C++ |
| Pairing mode | `/var/lib/openautolink/pairing_mode` | BT script | Bridge C++ |
| Default phone (mirror) | App DataStore (`DEFAULT_PHONE_MAC`) | App UI | App (synced from bridge echo) |
| Startup grace | In-memory (`DEFAULT_GRACE_SEC = 10`) | BT script | Hardcoded constant |

---

## Edge Cases & Scenarios

### Scenario 1: Car Power Cycle (Hard Power Cut)

```
Car turns off → SBC loses power immediately
  → BT script, bridge die mid-stream
  → Phone A's AA session drops
  → App enters "Connecting..." state (TCP lost)

Car turns on → SBC boots (~15-30s)
  → BT script starts, reads OAL_DEFAULT_PHONE_MAC
  → Starts preferred-phone probe
  → Reconnect worker starts after initial delay
  → Phone A (default) auto-reconnects via BT → RFCOMM → WiFi → AA
  → App auto-reconnects TCP → receives phone_connected → streaming
```

**Multi-phone concern**: Phone B (non-default, also paired) may also try to connect during boot. The startup grace period + probe ensures Phone A gets priority. If Phone B's RFCOMM arrives first during the grace period, it's rejected. If the probe confirms Phone A is reachable, Phone B stays rejected until Phone A connects.

### Scenario 2: Default Phone Not Present at Boot

```
Car turns on → SBC boots
  → BT script reads OAL_DEFAULT_PHONE_MAC = Phone A
  → Preferred probe: Phone A UNREACHABLE (not in car)
  → Grace period bypassed immediately
  → Phone B (non-default, in car) connects via RFCOMM
  → Phone B gets WiFi credentials → AA session
```

**No 10s penalty**: The async probe resolves the reachability question in ~5-8s via BT page timeout, and non-default phones can proceed as soon as the probe reports UNREACHABLE. In practice, if Phone B's RFCOMM arrives before the probe completes but within the grace window, it waits. Once the probe finishes, the BT script allows it through on the next attempt.

### Scenario 3: Phone Switch While Streaming

```
Phone A streaming → User taps Phone B in overlay
  → Bridge: writes switch override, sends ByeByeRequest to A
  → Bridge: disconnects A's BT, initiates connect to B
  → BT script: rejects A's reconnect attempts (override active)
  → Phone B: RFCOMM → WiFi → AA session → streaming
  → Override cleared on successful RFCOMM exchange
```

### Scenario 4: Switch Target Not Available

```
Phone A streaming → User taps Phone B in overlay
  → Bridge: writes switch override (90s expiry)
  → Bridge: disconnects A, initiates connect to B
  → Phone B unreachable (not nearby)
  → Reconnect worker: tries only Phone B (override active)
  → 90s passes → override expires
  → Reconnect worker: resumes normal preference logic
  → Phone A (if default and nearby) reconnects
```

**UX concern**: 90s of no connection is a long time. The app should show a "Switching to Phone B..." status during this window. If the switch fails, the user sees the status revert to "Connecting..." as normal reconnect resumes.

### Scenario 5: Forget Active Phone

```
Phone A streaming → User taps "Forget" on Phone A in settings
  → Bridge: ByeByeRequest to A, then bluetoothctl remove
  → AA session ends → app shows "Disconnected"
  → Phone A is removed from paired list
  → If Phone A was default: OAL_DEFAULT_PHONE_MAC should be cleared
  → Reconnect worker: no devices (or tries remaining paired phones)
```

**Important**: Forgetting the default phone MUST clear `OAL_DEFAULT_PHONE_MAC`. Otherwise the startup grace period and reconnect logic reference a MAC that no longer exists in BlueZ, causing weird fallback behavior.

### Scenario 6: New Phone Pairs While Another Is Streaming

```
Phone A streaming → User enables pairing mode
  → Phone B discovers bridge BLE advertisement, pairs
  → BlueZ accepts pairing (Agent auto-confirm)
  → Phone B tries RFCOMM → rejected (Phone A is connected + default)
  → Phone B appears in paired_phones list
  → User can switch to Phone B later
  → Phone A session continues uninterrupted
```

### Scenario 7: Both Phones in Car, Default Phone Is Phone A

```
Car boots → both phones nearby
  → BT script: preferred probe for Phone A
  → Phone A answers first → RFCOMM → WiFi → AA
  → Phone B tries RFCOMM → rejected (default connected)
  → User can switch to B via overlay if desired
```

### Scenario 8: Both Phones in Car, No Default Set

```
Car boots → both phones nearby, OAL_DEFAULT_PHONE_MAC empty
  → No grace period, no probe
  → First phone to complete RFCOMM wins
  → Non-deterministic — depends on BT timing
```

**This is why R1 (auto-set default) matters**: Without a default, the behavior is a race. The first-paired phone should become the default to prevent this.

### Scenario 9: App Reinstalled, Bridge Has Default

```
App reinstalled → no DataStore state
  → App connects to bridge → receives config_echo
  → config_echo contains default_phone_mac
  → App writes to DataStore → Settings shows correct default
```

---

## Implementation Status

| Requirement | Status | Notes |
|-------------|--------|-------|
| R1 — Auto-set first phone as default | ✅ Implemented | BT script auto-writes on first RFCOMM when env is empty |
| R2 — Default on bridge | ✅ Implemented | `/etc/openautolink.env`, `config_update`, `config_echo` |
| R3 — Pairing during streaming | ✅ Implemented | RFCOMM gate active both with and without default set |
| R4 — Single-phone reliability | ✅ Implemented | All multi-phone paths are no-ops for single phone |
| R5 — Startup selection + fallback | ✅ Implemented | Probe + grace + reconnect fallback |
| R6 — Active phone switching | ✅ Implemented | ByeByeRequest + override + BT gating + status feedback + cancel |

---

## Gaps & Proposed Improvements

### Gap 1: Auto-Set Default Phone (R1)

**Problem**: First-time users must manually go to Settings → Phones → set default. Until they do, the startup grace period and RFCOMM gating for multi-phone scenarios don't work properly.

**Fix**: In `aa_bt_all.py` `handle_aa_rfcomm()`, after successful WiFi credential exchange, check if `OAL_DEFAULT_PHONE_MAC` is empty. If so, write this phone's MAC to the env file. Log it clearly: `"Auto-set default phone: XX:XX:XX:XX:XX:XX (first paired)"`.

**Risk**: Low. Only fires when the env var is empty, which is only the first-ever pairing.

### Gap 2: Clear Default When Forgetting Default Phone

**Problem**: If the user forgets the default phone, `OAL_DEFAULT_PHONE_MAC` still points to the removed MAC. The BT script's grace period and reconnect logic will waste time trying to connect a device that no longer exists.

**Fix**: In `handle_forget_phone()`, if the forgotten MAC equals the current `OAL_DEFAULT_PHONE_MAC`, clear the env var (set to empty). If other phones are paired, optionally auto-promote the next one.

### Gap 3: Switch Timeout UX

**Problem**: If a switch target is unreachable, the user waits up to 90s with no AA session. No feedback is shown in the app about the switch status.

**Fix options**:
- Add a `switch_phone_status` bridge→app message: `{"type":"switch_phone_status","target_mac":"...","status":"connecting"|"connected"|"failed"}`
- Reduce the override expiry to 45-60s
- Allow the user to cancel a pending switch (clear the override)

### Gap 4: RFCOMM Gate Without Default Set (R3 edge case)

**Problem**: When `OAL_DEFAULT_PHONE_MAC` is empty and a phone is actively connected, a second phone can complete RFCOMM and get WiFi credentials. This doesn't break the AA session (the bridge's TCP-level guard prevents a second AA session), but the second phone will pointlessly try to TCP-connect and fail.

**Fix**: In `AAProfile.NewConnection`, regardless of default phone setting, reject RFCOMM if ANY phone currently has an active BT connection AND an AA session is active. This can be done by checking if the bridge process has an active TCP client on port 5277.

### Gap 5: Phone Switch From Projection Overlay — No Default-Set Shortcut

**Problem**: The phone switcher popup in the projection screen shows switch/forget buttons but no "set as default" option. Users must navigate to Settings to change the default.

**Fix**: Add a "Set as Default" option to the phone switcher popup when viewing non-default phones.

### Gap 6: No Connection History or Last-Seen

**Problem**: When multiple phones are paired, the list shows only name, MAC, and current connection status. There's no information about when each phone was last connected, making it harder to manage stale pairings.

**Fix (low priority)**: Track `last_rfcomm_at` per MAC in persistent storage on the bridge. Include in `paired_phones` response. Display in the app's phone list.

### Gap 7: No Feedback When Non-Default Phone Is Rejected

**Problem**: When a non-default phone tries to connect and is rejected by the RFCOMM gate, the phone user gets no indication — the phone silently fails to start AA projection. This is expected behavior, but confusing for passengers with their own phone paired.

**Fix (low priority)**: Not actionable on the bridge side (can't send messages to rejected phones). Document this behavior for users. The phone's AA app will show its own generic "connection failed" message.

### Gap 8: Race Between Phone Probe and RFCOMM

**Problem**: During the 10s grace period at startup, if the preferred phone probe is still running (hasn't resolved yet), non-default phones are held. If the probe takes the full 8s D-Bus timeout and the non-default phone's RFCOMM arrives at second 2, it waits 8 more seconds unnecessarily.

**Current mitigation**: The probe resolves in 5-8s. The grace period is 10s. In the worst case, the non-default phone waits the full 10s. This is acceptable because:
- The default phone is the user's chosen preference
- 10s is not perceptibly "broken" — it feels like normal boot delay
- The probe usually resolves before the grace expires

**No fix needed** — current behavior is correct. Document as expected.

---

## Design Decisions & Rationale

### Why Not Per-Phone AA Settings?

All phones share the same AA configuration (resolution, codec, DPI, FPS). Reasons:
1. The AA configuration is determined by the head unit's display capabilities, not the phone
2. Per-phone settings would require the bridge to identify the phone before configuring AA — the configuration is sent during the AA handshake before phone identity is confirmed
3. The bridge's AA session is a single process with one set of service handlers — runtime reconfiguration would add complexity for no user benefit
4. Different SDR/HDR settings per phone could cause unexpected quality changes when switching

### Why 90s Switch Override Expiry?

The switch override prevents the old phone from auto-reconnecting during the switch window. 90s is a safety ceiling, not an expected duration. Normal flow:
1. ByeByeRequest takes <1s
2. BT disconnect takes <2s  
3. BT connect to new phone takes 2-5s
4. RFCOMM exchange takes <2s
5. WiFi connect takes 2-5s
6. AA handshake takes 1-3s

Total: ~10-15s. The override is cleared when the target phone completes RFCOMM, so in practice it's active for ~5-10s. The 90s ceiling only matters if the target phone is unreachable.

### Why Grace Period Instead of Exclusive-Lock?

An exclusive lock on the default phone would mean non-default phones can NEVER connect without explicit switching. The grace period approach is better because:
1. If the default phone is genuinely not present (at home, dead battery), other phones can still auto-connect after a short wait
2. The async probe further reduces this wait when the default is clearly offline
3. Users don't have to manually switch every time they drive a different car with a different phone

### Why Default on Bridge Instead of App?

1. **Multiple head units**: A user might have two vehicles with different AAOS head units connecting to the same bridge. The default phone setting applies to the bridge's BT behavior, not the app's rendering.
2. **App reinstall**: If the app is reinstalled, the bridge's setting persists. The app re-syncs via `config_echo`.
3. **BT script needs it**: The BT script runs independently of the app and bridge binary. It reads the env file directly — no IPC needed.
4. **Power-on behavior**: The BT script starts before the app connects. The default phone preference must be available immediately at boot, from a file the BT script can read without waiting for anything.

---

## Implementation Plan

This section defines the concrete changes needed to close the gaps identified above. Each task is self-contained and ordered by dependency — later tasks may depend on earlier ones, but no circular dependencies exist.

All changes must preserve single-phone reliability (R4). Every task includes a "single-phone check" to verify this.

---

### Task 1: Auto-Set Default Phone on First Pairing (R1)

**Problem**: First-time users must manually set a default phone. Without it, multi-phone RFCOMM gating doesn't work, and two-phone boot is non-deterministic (Scenario 8).

**Files**: `bridge/openautolink/scripts/aa_bt_all.py`

**Changes**:

In `handle_aa_rfcomm()`, after the successful WiFi credential exchange (after `"WiFi credential exchange complete!"` log line), add:

```python
# Auto-set this phone as the default if no default is configured yet.
# Ensures single-phone users get proper RFCOMM gating automatically,
# and the first phone paired always wins in multi-phone boot races.
if connecting_mac:
    current_default = _preferred_bt_mac()
    if not current_default:
        _auto_set_default_phone(connecting_mac)
```

New helper function `_auto_set_default_phone(mac)`:

```python
def _auto_set_default_phone(mac):
    """Persist this MAC as OAL_DEFAULT_PHONE_MAC in the env file.
    Only called when no default is set (first-ever pairing)."""
    env_path = "/etc/openautolink.env"
    try:
        import subprocess
        # Use the same upsert pattern as the C++ bridge's handle_config_update
        subprocess.run([
            "bash", "-c",
            f"grep -q '^OAL_DEFAULT_PHONE_MAC=' {env_path} 2>/dev/null && "
            f"sed -i 's/^OAL_DEFAULT_PHONE_MAC=.*/OAL_DEFAULT_PHONE_MAC={mac}/' {env_path} || "
            f"echo 'OAL_DEFAULT_PHONE_MAC={mac}' >> {env_path}"
        ], timeout=5)
        oal_print(f"Auto-set default phone: {mac} (first paired)", flush=True)
    except Exception as e:
        oal_print(f"Failed to auto-set default phone: {e}", flush=True)
```

**Single-phone check**: Only fires when `OAL_DEFAULT_PHONE_MAC` is empty. After first pairing, the phone IS the default — all existing logic continues unchanged. Net effect for single-phone: the default gets set automatically instead of requiring Settings navigation.

---

### Task 2: Clear Default When Forgetting Default Phone

**Problem**: `handle_forget_phone()` removes the device from BlueZ but doesn't clear `OAL_DEFAULT_PHONE_MAC` if it matches. The BT script then chases a ghost MAC at every boot.

**Files**: `bridge/openautolink/headless/src/oal_session.cpp`

**Changes**:

In `handle_forget_phone()`, after the MAC validation and before `do_forget`, add a check:

```cpp
// If forgetting the default phone, clear the default setting
const char* default_mac_env = std::getenv("OAL_DEFAULT_PHONE_MAC");
std::string default_mac = default_mac_env ? default_mac_env : "";
// Normalize both to uppercase for comparison
std::transform(default_mac.begin(), default_mac.end(), default_mac.begin(), ::toupper);
std::string mac_upper = mac;
std::transform(mac_upper.begin(), mac_upper.end(), mac_upper.begin(), ::toupper);

if (!default_mac.empty() && default_mac == mac_upper) {
    BLOG << "[OAL] forgetting default phone — clearing OAL_DEFAULT_PHONE_MAC" << std::endl;
    // Clear from env file
    std::string clear_cmd = "sed -i 's/^OAL_DEFAULT_PHONE_MAC=.*/OAL_DEFAULT_PHONE_MAC=/' /etc/openautolink.env";
    system(clear_cmd.c_str());
    // Clear from runtime environment so config_echo reflects immediately
    setenv("OAL_DEFAULT_PHONE_MAC", "", 1);
}
```

After `do_forget()` completes and `send_paired_phones()` is called, also send a `config_echo` so the app gets the cleared default:

```cpp
// In do_forget lambda, after send_paired_phones():
send_config_echo();
```

**Single-phone check**: If there's only one phone and the user forgets it, the default is cleared. When they pair again, Task 1 auto-sets the new phone as default. No behavioral change if the forgotten phone wasn't the default.

---

### Task 3: RFCOMM Gate Without Default Set (R3 hardening)

**Problem**: When `OAL_DEFAULT_PHONE_MAC` is empty and a phone is actively connected with an AA session, a second phone can complete RFCOMM and get WiFi credentials. It can't establish a second AA session (TCP guard handles that), but it wastes WiFi resources and confuses the phone.

**Files**: `bridge/openautolink/scripts/aa_bt_all.py`

**Changes**:

In `AAProfile.NewConnection()`, add a fourth rejection condition after the existing three. This fires when no default is set but another phone IS connected:

```python
# After the existing "if not reject_reason:" block that checks preferred
# phone connection status, add:
if not reject_reason and not preferred:
    # No default phone set — but if ANY phone is currently BT-connected,
    # reject others. Prevents credential exchange waste.
    try:
        objects = _get_managed_objects()
        for path, ifaces in objects.items():
            dev_props = ifaces.get("org.bluez.Device1")
            if not dev_props:
                continue
            dev_mac = _normalize_mac(str(dev_props.get("Address", "")))
            if dev_mac != connecting_mac and bool(dev_props.get("Connected", False)):
                reject_reason = f"another phone {dev_mac} is already connected (no default set)"
                break
    except Exception as e:
        oal_print(f"AA RFCOMM: error checking connected phones: {e}", flush=True)
```

**Single-phone check**: With one phone, the `dev_mac != connecting_mac` check ensures the connecting phone is never compared against itself. Only fires when a DIFFERENT phone is already connected. No effect on single-phone.

---

### Task 4: `switch_phone_status` Bridge→App Message

**Problem**: When the user triggers a phone switch, there's no feedback in the app about whether it's working. If the target is unreachable, the user stares at a blank screen for up to 90s.

**Files**:
- `bridge/openautolink/headless/src/oal_session.cpp` — send status messages
- `bridge/openautolink/headless/include/openautolink/oal_session.hpp` — new method declaration
- `app/src/main/java/com/openautolink/app/transport/ControlMessage.kt` — new message type
- `app/src/main/java/com/openautolink/app/transport/ControlMessageSerializer.kt` — deserialize
- `app/src/main/java/com/openautolink/app/session/SessionManager.kt` — dispatch to UI
- `app/src/main/java/com/openautolink/app/ui/projection/ProjectionViewModel.kt` — expose state
- `app/src/main/java/com/openautolink/app/ui/projection/ProjectionScreen.kt` — display status

**Protocol addition**:

```
Bridge → App: switch_phone_status
{"type":"switch_phone_status","target_mac":"BB:BB:BB:BB:BB:BB","target_name":"Pixel 10","status":"switching"}
{"type":"switch_phone_status","target_mac":"BB:BB:BB:BB:BB:BB","target_name":"Pixel 10","status":"connected"}
{"type":"switch_phone_status","target_mac":"","target_name":"","status":"idle"}
```

| Field | Type | Description |
|-------|------|-------------|
| `target_mac` | string | MAC of the phone being switched to (empty when idle) |
| `target_name` | string | Name of the target phone (for display) |
| `status` | string | `"switching"` (in progress), `"connected"` (success), `"idle"` (no switch pending — timeout/cancel) |

**Bridge changes**:

1. In `handle_switch_phone()`: after writing the switch override file, send `switch_phone_status` with `status: "switching"` and the target's name (look up from `bluetoothctl devices Paired`).

2. In `on_phone_connected()`: if the newly connected phone's MAC matches the switch override target, send `switch_phone_status` with `status: "connected"`, then clear to `"idle"` after 3s (or on next status event).

3. Add a periodic check (or piggyback on reconnect state): if the switch override expires (90s timeout), send `switch_phone_status` with `status: "idle"` to tell the app the switch failed/timed out.

**App changes**:

1. Add `SwitchPhoneStatus(targetMac: String, targetName: String, status: String)` to `ControlMessage`.
2. Deserialize in `ControlMessageSerializer`.
3. In `SessionManager`, forward to a new `_switchStatus: StateFlow<SwitchPhoneStatus?>`.
4. In `ProjectionViewModel`, expose `switchStatus: StateFlow`.
5. In `ProjectionScreen`, when `switchStatus.status == "switching"`, show a toast/banner: "Switching to {targetName}..." in the ConnectionHud area.

**Single-phone check**: `switch_phone_status` is never sent unless `handle_switch_phone()` is called. Single-phone users never trigger this path.

---

### Task 5: Cancel Pending Phone Switch

**Problem**: If the user triggers a switch to an unreachable phone, there's no way to cancel. They wait 90s with no AA session.

**Files**:
- `app/src/main/java/com/openautolink/app/transport/ControlMessage.kt` — new message type
- `app/src/main/java/com/openautolink/app/transport/ControlMessageSerializer.kt` — serialize
- `bridge/openautolink/headless/src/oal_session.cpp` — handle the cancel
- `app/src/main/java/com/openautolink/app/ui/projection/ProjectionScreen.kt` — cancel button

**Protocol addition**:

```
App → Bridge: cancel_switch_phone
{"type":"cancel_switch_phone"}
```

No fields. Clears the switch override and resumes normal reconnect behavior.

**Bridge changes**:

In `oal_session.cpp`, add `handle_cancel_switch_phone()`:

```cpp
void OalSession::handle_cancel_switch_phone() {
    BLOG << "[OAL] cancel_switch_phone requested" << std::endl;
    // Remove the override file — reconnect worker resumes normal logic
    ::unlink("/run/openautolink/switch_override");
    // Tell the app the switch is no longer pending
    send_control_line(R"({"type":"switch_phone_status","target_mac":"","target_name":"","status":"idle"})");
}
```

Wire it up in the control message dispatcher (the `if/else` chain that handles `type` strings).

**App changes**:

1. Add `CancelSwitchPhone` to `ControlMessage`.
2. Serialize in `ControlMessageSerializer`.
3. In `ProjectionScreen`, when `switchStatus.status == "switching"`, show a "Cancel" button alongside the "Switching to..." banner. Tapping it sends `CancelSwitchPhone`.
4. In `ProjectionViewModel`, add `cancelSwitchPhone()` method.

**Single-phone check**: The cancel button only appears when a switch is active. Single-phone users never see it.

---

### Task 6: Reduce Switch Override Expiry to 45s

**Problem**: 90s is too long if the target is unreachable. Normal switch completes in ~10-15s.

**Files**: `bridge/openautolink/headless/src/oal_session.cpp`

**Changes**:

In `handle_switch_phone()`, change the expiry from 90s to 45s:

```cpp
// Before:
time_t expiry = ::time(nullptr) + 90;
// After:
time_t expiry = ::time(nullptr) + 45;
```

Update the comment to match. This is still 3x the expected completion time, but halves the worst-case wait.

**Single-phone check**: No effect — switch override is never written for single-phone users.

---

### Task 7: Include `default_mac` in `paired_phones` Response

**Problem**: The app must know which phone is the bridge's default to display the "Default" badge and "Set Default" button correctly. Currently it relies on the locally-mirrored DataStore value, which can be stale after app reinstall until `config_echo` arrives. Including it in `paired_phones` makes the UI self-consistent.

**Files**:
- `bridge/openautolink/headless/src/oal_session.cpp` — add field to `send_paired_phones()`
- `app/src/main/java/com/openautolink/app/transport/ControlMessageSerializer.kt` — parse new field
- `app/src/main/java/com/openautolink/app/transport/ControlMessage.kt` — add field to `PairedPhones`
- `app/src/main/java/com/openautolink/app/session/SessionManager.kt` — sync default on receive

**Bridge changes**:

In `send_paired_phones()`, read `OAL_DEFAULT_PHONE_MAC` and include it:

```cpp
// After the phones array close "]", before the object close "}"
const char* default_mac_env = std::getenv("OAL_DEFAULT_PHONE_MAC");
std::string default_mac = default_mac_env ? default_mac_env : "";
oss << R"(],"default_mac":")" << oal_json_escape(default_mac) << R"("})";
```

**App changes**:

1. Add `defaultMac: String` to `PairedPhones` data class.
2. In `ControlMessageSerializer`, parse `default_mac` from the `paired_phones` JSON.
3. In `SessionManager`, when `PairedPhones` is received, update `preferences.setDefaultPhoneMac(defaultMac)` to keep the local mirror in sync.

**Single-phone check**: The field is always present (may be empty string). No behavioral change.

---

### Task 8: "Set as Default" in Projection Overlay Phone Switcher

**Problem**: The projection overlay popup shows phones with switch capability but no way to set a default. Users must navigate to Settings.

**Files**:
- `app/src/main/java/com/openautolink/app/ui/projection/ProjectionScreen.kt` — add button
- `app/src/main/java/com/openautolink/app/ui/projection/ProjectionViewModel.kt` — add method

**Changes**:

In `PhoneSwitcherPopup`, for each non-current, non-default phone, add a small "★" or "Set Default" tap target. Wire it to `ProjectionViewModel.setDefaultPhone(mac)`:

```kotlin
fun setDefaultPhone(mac: String) {
    viewModelScope.launch {
        com.openautolink.app.transport.ConfigUpdateSender.sendConfigUpdate(
            mapOf("default_phone_mac" to mac)
        )
    }
}
```

The phone switcher popup also needs to know the current default MAC. Pass it via the ViewModel (already available from `config_echo` → DataStore → preferences flow).

**Single-phone check**: With one phone in the list, the default star/button has no negative effect. Tapping it is a no-op if the phone is already default.

---

### Task Summary & Dependency Order

```
Task 1: Auto-set default (BT script only)           ← No dependencies
Task 2: Clear default on forget (bridge C++)         ← No dependencies
Task 3: RFCOMM gate without default (BT script)      ← No dependencies
Task 6: Reduce override expiry (bridge C++)          ← No dependencies

Task 7: default_mac in paired_phones (bridge + app)  ← No dependencies
Task 8: Set default in overlay (app only)            ← Depends on Task 7

Task 4: switch_phone_status message (bridge + app)   ← No dependencies
Task 5: Cancel switch (bridge + app)                 ← Depends on Task 4
```

**Recommended implementation order**:

1. **Tasks 1, 2, 3, 6** — Bridge-side fixes, independent, can be done in one commit. These close the functional gaps in the multi-phone logic without touching the app.
2. **Task 7** — Adds `default_mac` to protocol. Small bridge+app change.
3. **Task 8** — App-only UI enhancement, depends on Task 7.
4. **Task 4** — Switch status feedback, bridge+app protocol addition.
5. **Task 5** — Cancel switch, depends on Task 4.

Tasks 1-3+6 are the critical reliability fixes. Tasks 4-5 are UX improvements. Tasks 7-8 are convenience enhancements.

---

## File Reference

| File | Role |
|------|------|
| `bridge/openautolink/scripts/aa_bt_all.py` | BT pairing, RFCOMM gating, reconnect worker, probe |
| `bridge/openautolink/headless/src/oal_session.cpp` | `handle_switch_phone`, `handle_forget_phone`, `handle_list_paired_phones`, `config_echo` |
| `bridge/sbc/openautolink.env` | `OAL_DEFAULT_PHONE_MAC` template |
| `app/.../transport/ControlMessage.kt` | `SwitchPhone`, `ListPairedPhones`, `ForgetPhone`, `SetPairingMode` |
| `app/.../transport/ControlMessageSerializer.kt` | Serialization/deserialization of phone messages |
| `app/.../session/SessionManager.kt` | Phone state flows, message dispatch |
| `app/.../ui/settings/SettingsViewModel.kt` | Default phone selection, paired phone management |
| `app/.../ui/settings/SettingsScreen.kt` | Phone list UI, switch/forget/pairing mode |
| `app/.../ui/projection/ProjectionScreen.kt` | Phone switcher overlay popup |
| `app/.../ui/projection/ProjectionViewModel.kt` | Quick switch logic |
| `app/.../data/AppPreferences.kt` | `DEFAULT_PHONE_MAC` DataStore key |
