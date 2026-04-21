"""OpenAutoLink Bluetooth setup — D-Bus registrations for BlueZ.

Architecture:
  This script is a D-Bus callback handler, NOT a processing daemon.
  It registers profiles/agent with BlueZ at startup, then idles on
  GLib.MainLoop waiting for D-Bus events. All work is reactive:

  SETUP (one-shot, at startup):
    - Configure BT adapter (class, SSP, discoverable)
    - Register pairing agent (auto-accept)
    - Register AA RFCOMM profile (channel 8) + SDP record
    - Register HSP profile
    - Register BLE advertisement
    - Attempt HSP connection to previously paired phone

  EVENT HANDLERS (reactive, idle 99.99% of the time):
    - Agent callbacks: auto-accept pairing requests
    - AAProfile.NewConnection: WiFi credential exchange over
      RFCOMM (~3 messages, <1 second, then close fd)
    - HSPProfile: connection logging

  WHY PYTHON:
    BlueZ's primary API is D-Bus. D-Bus object ownership requires a
    running process — if we exit, BlueZ unregisters our profiles.
    Python + dbus + GLib is the standard BlueZ integration stack.
    The GLib.MainLoop is NOT doing real-time processing — it's an
    idle event loop that wakes only for BT pairing/connect events.

  WHAT MUST STAY IN NATIVE CODE (C++ bridge):
    - SCO audio capture/playback (real-time)
    - All audio/video streaming
    - AA session management (aasdk)
"""
import dbus, dbus.service, dbus.mainloop.glib
from gi.repository import GLib
import os, time, threading, struct, fcntl, socket

# ── Version-prefixed logging ─────────────────────────────────────────
# Every log line includes the version so you never have to guess which
# code is running when reading journalctl output.
_OAL_VERSION = os.environ.get("OAL_VERSION", "dev")
_LOG_PREFIX = f"[bt {_OAL_VERSION}] "

def oal_print(*args, **kwargs):
    """print() replacement that prefixes every line with [bt <version>]."""
    import io
    buf = io.StringIO()
    kwargs_copy = dict(kwargs)
    kwargs_copy["file"] = buf
    kwargs_copy.pop("flush", None)
    print(*args, **kwargs_copy)
    for line in buf.getvalue().splitlines():
        print(f"{_LOG_PREFIX}{line}", flush=True)

AA_UUID = "4de17a00-52cb-11e6-bdf4-0800200c9a66"
HFP_HF_UUID = "0000111e-0000-1000-8000-00805f9b34fb"  # Hands-Free (our role: car kit)
HFP_AG_UUID = "0000111f-0000-1000-8000-00805f9b34fb"  # Audio Gateway (phone's role)
HSP_HS_UUID = "00001108-0000-1000-8000-00805f9b34fb"
HSP_AG_UUID = "00001112-0000-1000-8000-00805f9b34fb"
AGENT_IFACE = "org.bluez.Agent1"
PROFILE_IFACE = "org.bluez.Profile1"
LE_AD_IFACE = "org.bluez.LEAdvertisement1"
AA_CHANNEL = 8

# WiFi credentials — override via environment or edit here
WIFI_SSID = os.environ.get("OAL_WIRELESS_SSID", os.environ.get("PI_AA_WIRELESS_SSID", "")) or "OpenAutoLink"
WIFI_KEY = os.environ.get("OAL_WIRELESS_PASSWORD", os.environ.get("PI_AA_WIRELESS_PASSWORD", "")) or "openautolink"
WIFI_IP = "192.168.43.1"
WIFI_PORT = int(os.environ.get("OAL_PHONE_TCP_PORT", os.environ.get("PI_AA_BACKEND_TCP_PORT", "5277")))
WIFI_BSSID = "00:00:00:00:00:00"  # filled at runtime from wlan0 MAC
RECONNECT_INITIAL_DELAY_SEC = 5
RECONNECT_INTERVAL_SEC = 15
RECONNECT_ACTIVITY_GRACE_SEC = 10

# BT name — unique per SBC, derived from machine-id (always available on any Linux)
def _get_bt_name():
    name = os.environ.get("OAL_BT_NAME", "")
    if name:
        return name
    # Use last 4 hex chars of machine-id — unique per OS install, always present
    try:
        with open("/etc/machine-id") as f:
            suffix = f.read().strip()[-4:].upper()
    except Exception:
        suffix = "0000"
    return f"OpenAutoLink-{suffix}"

BT_NAME = _get_bt_name()
last_bt_activity_at = 0.0

# Multi-phone safety timing:
#  - DEFAULT_GRACE_SEC: at script startup, non-default phones are blocked at
#    AA RFCOMM for this many seconds. Gives the preferred phone a head start
#    when both phones power on with the car and race BT ACL setup.
#  - SWITCH_OVERRIDE_FILE: written by the C++ bridge in handle_switch_phone
#    with "<mac>\n<expiry_unix_ts>". While the override is active, the
#    target MAC bypasses the "is default connected?" check. This fixes the
#    bug where Phone A auto-reconnects during the BT disconnect/connect
#    window and blocks the user's explicit switch to Phone B.
DEFAULT_GRACE_SEC = float(os.environ.get("OAL_DEFAULT_GRACE_SEC", "10"))
SWITCH_OVERRIDE_FILE = "/run/openautolink/switch_override"
PAIRING_MODE_FILE = "/var/lib/openautolink/pairing_mode"
SCRIPT_START_AT = time.monotonic()

def _read_pairing_mode():
    """Return True if pairing is enabled (default), False if user explicitly disabled."""
    try:
        with open(PAIRING_MODE_FILE, "r") as f:
            return f.read().strip().lower() not in ("off", "false", "0", "no")
    except (FileNotFoundError, OSError):
        return True  # default: pairing allowed

# Proactive preferred-phone reachability probe. Populated asynchronously at
# script startup so the RFCOMM gate can short-circuit the startup grace when
# we've confirmed the default phone is offline (not in car). Without this,
# non-default phones incur the full DEFAULT_GRACE_SEC penalty even when the
# default isn't present.
preferred_probe = {"done": False, "reachable": False, "lock": threading.Lock()}

# Rate-limit per-MAC rejection logs so a phone retrying in a tight loop
# doesn't spam journald. Key = MAC, value = last log monotonic-time.
_last_reject_log_at = {}
REJECT_LOG_INTERVAL_SEC = 10.0

# Per-MAC RFCOMM cooldown — after a successful WiFi credential exchange,
# reject further RFCOMM from the same MAC for this many seconds. This stops
# the phone's AA stack from firing repeated RFCOMM exchanges (each one triggers
# a new TCP:5277 connection that kills the previous SSL handshake in progress).
# 60s is enough for the phone to establish and maintain a stable AA session.
# The phone will retry RFCOMM after the cooldown if it genuinely lost the session.
_last_rfcomm_exchange_at = {}
RFCOMM_COOLDOWN_SEC = 60.0

def _log_rejection(mac, reason):
    now = time.monotonic()
    last = _last_reject_log_at.get(mac, 0.0)
    if now - last >= REJECT_LOG_INTERVAL_SEC:
        oal_print(f"AA RFCOMM rejected from {mac}: {reason}", flush=True)
        _last_reject_log_at[mac] = now

def _close_rejection_fd(fd):
    """Clean socket shutdown + close on a rejected RFCOMM fd.
    SHUT_RDWR sends FIN so the phone sees a clean close rather than RST,
    which discourages the aggressive sub-second retry loop seen on some
    Android versions."""
    # Duplicate the fd so the socket wrapper can close its copy cleanly
    # without affecting our close() below. socket.socket(fileno=...) takes
    # ownership and will close on __del__, so we use os.dup.
    try:
        dup_fd = os.dup(fd)
        try:
            s = socket.socket(fileno=dup_fd)
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            s.close()  # closes dup_fd
        except Exception:
            try:
                os.close(dup_fd)
            except OSError:
                pass
    except OSError:
        pass
    try:
        os.close(fd)
    except OSError:
        pass

def _disconnect_rejected_phone(mac):
    """Disconnect a rejected phone from BT entirely after a short delay.
    Runs in a daemon thread. The delay avoids racing with BlueZ's own
    connection setup (pairing may still be in flight).
    
    CRITICAL: Check the switch override before disconnecting — if the user
    initiated a switch to this phone between the rejection and the disconnect,
    we must NOT kick it off. This race happens when RFCOMM rejections pile up
    right before a switch."""
    time.sleep(2)
    # Abort if a switch override now targets this phone
    switch_target = _read_switch_override()
    if switch_target and _normalize_mac(switch_target) == _normalize_mac(mac):
        oal_print(f"Skipped disconnect of {mac} — switch override targets this phone", flush=True)
        return
    try:
        import subprocess
        subprocess.run(
            ["bluetoothctl", "disconnect", mac],
            timeout=10, capture_output=True
        )
        oal_print(f"Disconnected rejected phone {mac} from BT", flush=True)
    except Exception as e:
        oal_print(f"Failed to disconnect rejected phone {mac}: {e}", flush=True)

def _read_switch_override():
    """Return the target MAC if a valid (unexpired) switch override exists, else ""."""
    try:
        with open(SWITCH_OVERRIDE_FILE, "r") as f:
            lines = f.read().strip().split("\n")
        if len(lines) < 2:
            return ""
        mac = _normalize_mac(lines[0])
        expiry = float(lines[1])
        if time.time() > expiry:
            return ""
        return mac
    except (FileNotFoundError, ValueError, OSError):
        return ""

def _normalize_mac(mac):
    return mac.strip().upper()

def _mark_bt_activity():
    global last_bt_activity_at
    last_bt_activity_at = time.monotonic()

def _read_env_value(key):
    for env_path in ("/etc/openautolink.env", "/boot/firmware/openautolink.env"):
        try:
            with open(env_path) as env_file:
                for line in env_file:
                    if line.startswith(key + "="):
                        return line.split("=", 1)[1].strip()
        except Exception:
            pass
    return ""

def _preferred_bt_mac():
    return _normalize_mac(_read_env_value("OAL_DEFAULT_PHONE_MAC"))

def _auto_set_default_phone(mac):
    """Persist this MAC as OAL_DEFAULT_PHONE_MAC in the env file.
    Only called when no default is set (first-ever pairing)."""
    import subprocess
    env_path = "/etc/openautolink.env"
    try:
        subprocess.run([
            "bash", "-c",
            f"grep -q '^OAL_DEFAULT_PHONE_MAC=' {env_path} 2>/dev/null && "
            f"sed -i 's/^OAL_DEFAULT_PHONE_MAC=.*/OAL_DEFAULT_PHONE_MAC={mac}/' {env_path} || "
            f"echo 'OAL_DEFAULT_PHONE_MAC={mac}' >> {env_path}"
        ], timeout=5)
        oal_print(f"Auto-set default phone: {mac} (first paired)", flush=True)
    except Exception as e:
        oal_print(f"Failed to auto-set default phone: {e}", flush=True)

def _get_managed_objects():
    obj_mgr = dbus.Interface(bus.get_object("org.bluez", "/"),
                             "org.freedesktop.DBus.ObjectManager")
    return obj_mgr.GetManagedObjects()

def _wait_for_adapter_properties(timeout_sec=15):
    deadline = time.monotonic() + timeout_sec
    last_error = None
    while time.monotonic() < deadline:
        try:
            objects = _get_managed_objects()
            if "/org/bluez/hci0" in objects and "org.bluez.Adapter1" in objects["/org/bluez/hci0"]:
                return dbus.Interface(bus.get_object("org.bluez", "/org/bluez/hci0"),
                                      "org.freedesktop.DBus.Properties")
        except Exception as exc:
            last_error = exc
        time.sleep(0.5)
    if last_error is not None:
        raise last_error
    raise RuntimeError("Bluetooth adapter /org/bluez/hci0 not available")

def _paired_devices(objects):
    devices = []
    for path, ifaces in objects.items():
        props = ifaces.get("org.bluez.Device1")
        if not props or not props.get("Paired", False):
            continue
        devices.append((path, props, _normalize_mac(str(props.get("Address", "")))))
    return devices

def _sort_paired_devices(devices, preferred_mac):
    if not preferred_mac:
        return devices
    preferred = []
    fallback = []
    for device in devices:
        if device[2] == preferred_mac:
            preferred.append(device)
        else:
            fallback.append(device)
    return preferred + fallback

def _connect_device(path):
    oal_print(f"Reconnect candidate: {path}", flush=True)
    dev = dbus.Interface(bus.get_object("org.bluez", path),
                         "org.bluez.Device1")
    dp = dbus.Interface(bus.get_object("org.bluez", path),
                        "org.freedesktop.DBus.Properties")

    # Check if already connected — skip redundant profile connections that
    # can disrupt an existing HFP SLC (causes "NoReply" errors and breaks
    # SCO audio routing for VoIP apps like Teams).
    try:
        already_connected = bool(dp.Get("org.bluez.Device1", "Connected"))
        if already_connected:
            oal_print(f"Reconnect: {path} already connected, skipping", flush=True)
            return
    except Exception:
        pass

    # Only force-trust the preferred phone. Trusted devices skip the
    # Agent.AuthorizeService hook, which is our last-resort BlueZ-level
    # gate against non-default phones. For non-preferred devices, leave
    # BlueZ's existing Trusted state alone — the auto-accept Agent still
    # handles any authorization prompts.
    preferred = _preferred_bt_mac()
    try:
        addr = _normalize_mac(str(dp.Get("org.bluez.Device1", "Address")))
        if preferred and addr == preferred:
            already = bool(dp.Get("org.bluez.Device1", "Trusted"))
            if not already:
                dp.Set("org.bluez.Device1", "Trusted", dbus.Boolean(True))
    except Exception as e:
        oal_print(f"Trust check {path}: {e}", flush=True)

    try:
        dev.ConnectProfile(HFP_AG_UUID)
        oal_print(f"Connected HFP AG to {path}", flush=True)
    except Exception as e2:
        oal_print(f"HFP AG connect {path}: {e2}", flush=True)
        try:
            dev.ConnectProfile(HSP_AG_UUID)
            oal_print(f"Connected HSP AG to {path}", flush=True)
        except Exception as e3:
            oal_print(f"HSP AG connect {path}: {e3}", flush=True)

    try:
        dev.Connect()
        oal_print(f"Generic Connect to {path}", flush=True)
    except Exception:
        pass

def _probe_preferred_async(path, mac):
    """Proactively attempt to connect the preferred phone so the RFCOMM gate
    can short-circuit the startup grace. Runs in a daemon thread.
    Sets preferred_probe["reachable"] True on success, False on unreachable.
    
    On success, also calls _connect_device to establish HFP/BR/EDR profiles.
    The generic dev.Connect() may connect via LE, which makes BlueZ show
    'Connected' but doesn't trigger the phone's AA RFCOMM flow. Without
    the _connect_device follow-up, the reconnect worker sees 'Connected'
    and backs off, leaving the phone in BT limbo with no AA session."""
    try:
        dev = dbus.Interface(bus.get_object("org.bluez", path), "org.bluez.Device1")
        oal_print(f"Preferred probe: attempting Connect on {mac}", flush=True)
        try:
            dev.Connect(timeout=8)  # D-Bus timeout; BlueZ page timeout ~5-10s
            with preferred_probe["lock"]:
                preferred_probe["reachable"] = True
                preferred_probe["done"] = True
            oal_print(f"Preferred probe: {mac} REACHABLE", flush=True)
            # Follow up with proper HFP/BR/EDR profile connection
            _connect_device(path)
        except dbus.DBusException as e:
            name = e.get_dbus_name() if hasattr(e, "get_dbus_name") else str(e)
            # Errors that imply the phone IS reachable:
            #  - AlreadyConnected: phone already linked
            #  - InProgress: BlueZ is already negotiating a connect from the
            #    phone's side (very common — both sides race at boot).
            # Everything else (Failed, ConnectionAttemptFailed, NotConnected,
            # Page Timeout) = unreachable.
            reachable = ("AlreadyConnected" in name) or ("InProgress" in name)
            with preferred_probe["lock"]:
                preferred_probe["reachable"] = reachable
                preferred_probe["done"] = True
            state = "REACHABLE" if reachable else "UNREACHABLE"
            oal_print(f"Preferred probe: {mac} {state} ({name})", flush=True)
    except Exception as e:
        with preferred_probe["lock"]:
            preferred_probe["done"] = True
        oal_print(f"Preferred probe error: {e}", flush=True)

def _reconnect_worker():
    time.sleep(RECONNECT_INITIAL_DELAY_SEC)
    # Tracks consecutive failures on the preferred phone so we can fall
    # back to other paired devices when the default is truly offline.
    preferred_miss_count = 0
    PREFERRED_FALLBACK_AFTER = 3  # cycles (~45 s) before trying non-default
    while True:
        try:
            objects = _get_managed_objects()
            devices = _paired_devices(objects)
            if not devices:
                time.sleep(RECONNECT_INTERVAL_SEC)
                continue

            if any(bool(props.get("Connected", False)) for _, props, _ in devices):
                preferred_miss_count = 0
                time.sleep(RECONNECT_INTERVAL_SEC)
                continue

            if time.monotonic() - last_bt_activity_at < RECONNECT_ACTIVITY_GRACE_SEC:
                time.sleep(RECONNECT_INTERVAL_SEC)
                continue

            preferred_mac = _preferred_bt_mac()
            switch_target = _read_switch_override()

            if switch_target:
                # Explicit user switch — only try the target
                for path, _, mac in devices:
                    if mac == switch_target:
                        oal_print(f"Reconnect switch target: {switch_target}", flush=True)
                        _connect_device(path)
                        break
            elif preferred_mac:
                preferred_path = next((p for p, _, m in devices if m == preferred_mac), None)
                if preferred_path:
                    oal_print(f"Reconnect preferred MAC: {preferred_mac} "
                          f"(miss={preferred_miss_count})", flush=True)
                    _connect_device(preferred_path)
                    preferred_miss_count += 1
                    # After enough misses, allow fallback to other paired devices
                    if preferred_miss_count >= PREFERRED_FALLBACK_AFTER:
                        for path, _, mac in devices:
                            if mac != preferred_mac:
                                _connect_device(path)
                else:
                    # Preferred not in paired list — treat like no preference
                    for path, _, _ in devices:
                        _connect_device(path)
            else:
                for path, _, _ in _sort_paired_devices(devices, ""):
                    _connect_device(path)
        except Exception as e:
            oal_print(f"Phone connect: {e}", flush=True)
        # Poll faster during an active switch (3s vs 15s) for responsive handoff
        if _read_switch_override():
            time.sleep(3)
        else:
            time.sleep(RECONNECT_INTERVAL_SEC)

# ---- Minimal protobuf encoding (no library needed) ----
def _varint(v):
    r = []
    while v > 0x7f:
        r.append((v & 0x7f) | 0x80); v >>= 7
    r.append(v & 0x7f)
    return bytes(r)

def pbs(field_num, value):
    e = value.encode("utf-8")
    return bytes([(field_num << 3) | 2]) + _varint(len(e)) + e

def pbi(field_num, value):
    return bytes([(field_num << 3) | 0]) + _varint(value)

def rfcomm_send(fd, msg_type, payload):
    h = struct.pack(">HH", len(payload), msg_type)
    os.write(fd, h + payload)

def rfcomm_recv(fd):
    h = b""
    while len(h) < 4:
        h += os.read(fd, 4 - len(h))
    sz, mt = struct.unpack(">HH", h)
    d = b""
    while len(d) < sz:
        d += os.read(fd, sz - len(d))
    return mt, d

def handle_aa_rfcomm(fd, connecting_mac=""):
    """Handle the AA wireless WiFi credential exchange over RFCOMM."""
    fl = fcntl.fcntl(fd, fcntl.F_GETFL)
    fcntl.fcntl(fd, fcntl.F_SETFL, fl & ~os.O_NONBLOCK)

    try:
        oal_print(f"Sending WifiStartRequest ip={WIFI_IP} port={WIFI_PORT}", flush=True)
        rfcomm_send(fd, 1, pbs(1, WIFI_IP) + pbi(2, WIFI_PORT))

        mt, d = rfcomm_recv(fd)
        oal_print(f"Got msg type={mt} len={len(d)}", flush=True)

        oal_print(f"Sending WifiInfoResponse ssid={WIFI_SSID}", flush=True)
        rfcomm_send(fd, 3, pbs(1, WIFI_SSID) + pbs(2, WIFI_KEY) + pbs(3, WIFI_BSSID) + pbi(4, 8) + pbi(5, 1))

        mt2, d2 = rfcomm_recv(fd)
        oal_print(f"Got msg type={mt2} len={len(d2)}", flush=True)

        mt3, d3 = rfcomm_recv(fd)
        oal_print(f"Got msg type={mt3} len={len(d3)}", flush=True)

        oal_print("WiFi credential exchange complete!", flush=True)

        # Record successful exchange time (kept for potential future use)
        if connecting_mac:
            _last_rfcomm_exchange_at[connecting_mac] = time.monotonic()

        # Record the active phone MAC so the C++ bridge can mark aa_active
        # in the paired_phones response. Written to tmpfs — lost on reboot,
        # which is correct (no AA session survives reboot).
        if connecting_mac:
            try:
                os.makedirs("/run/openautolink", exist_ok=True)
                with open("/run/openautolink/active_phone_mac", "w") as f:
                    f.write(connecting_mac + "\n")
            except OSError:
                pass

        # Auto-set this phone as the default if no default is configured yet.
        # Ensures single-phone users get proper RFCOMM gating automatically,
        # and the first phone paired always wins in multi-phone boot races.
        if connecting_mac:
            current_default = _preferred_bt_mac()
            if not current_default:
                _auto_set_default_phone(connecting_mac)

        # If this was the switch-override target, clear the override now that
        # credentials are delivered. Eliminates the need for a wall-clock timer
        # guess — the target has everything it needs to come up on WiFi.
        if connecting_mac:
            switch_target = _read_switch_override()
            if switch_target and switch_target == connecting_mac:
                try:
                    os.unlink(SWITCH_OVERRIDE_FILE)
                    oal_print(f"Switch override cleared (target {connecting_mac} got credentials)", flush=True)
                except OSError:
                    pass
    except Exception as e:
        oal_print(f"RFCOMM exchange error: {e}", flush=True)
        try:
            os.close(fd)
        except OSError:
            pass


class Agent(dbus.service.Object):
    @dbus.service.method(AGENT_IFACE, in_signature="ou", out_signature="")
    def RequestConfirmation(self, d, p):
        oal_print(f"Auto-confirm {d} passkey={p}", flush=True)
        # Return without error = auto-accept
    @dbus.service.method(AGENT_IFACE, in_signature="o", out_signature="s")
    def RequestPinCode(self, d):
        oal_print(f"Auto-pin {d} -> 123456", flush=True)
        return "123456"
    @dbus.service.method(AGENT_IFACE, in_signature="o", out_signature="u")
    def RequestPasskey(self, d):
        oal_print(f"Auto-passkey {d}", flush=True)
        return dbus.UInt32(0)
    @dbus.service.method(AGENT_IFACE, in_signature="ou", out_signature="")
    def DisplayPasskey(self, d, p):
        oal_print(f"Display passkey {d} {p}", flush=True)
    @dbus.service.method(AGENT_IFACE, in_signature="os", out_signature="")
    def DisplayPinCode(self, d, p):
        oal_print(f"Display pin {d} {p}", flush=True)
    @dbus.service.method(AGENT_IFACE, in_signature="o", out_signature="")
    def RequestAuthorization(self, d):
        oal_print(f"Auto-authorize {d}", flush=True)
    @dbus.service.method(AGENT_IFACE, in_signature="os", out_signature="")
    def AuthorizeService(self, d, u):
        oal_print(f"Auto-authorize-svc {d} {u}", flush=True)
    @dbus.service.method(AGENT_IFACE, in_signature="", out_signature="")
    def Release(self): pass
    @dbus.service.method(AGENT_IFACE, in_signature="", out_signature="")
    def Cancel(self): pass

class AAProfile(dbus.service.Object):
    @dbus.service.method(PROFILE_IFACE, in_signature="oha{sv}", out_signature="")
    def NewConnection(self, device, fd, props):
        fd = fd.take()
        _mark_bt_activity()

        # Extract MAC from D-Bus device path (e.g. /org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF)
        connecting_mac = _normalize_mac(
            str(device).split("/")[-1].replace("dev_", "").replace("_", ":"))
        preferred = _preferred_bt_mac()
        switch_target = _read_switch_override()

        # Multi-phone gating — reject non-default phones in these cases:
        #   1. A switch override is active and the connecting phone isn't the target.
        #      (User explicitly chose another phone; lock out racers.)
        #   2. Within the startup grace period, a preferred phone is set, and this
        #      isn't it. (Gives default a head start when both phones power on.)
        #   3. The default phone is currently BT-connected to us. (Don't let
        #      a second phone kick the active session.)
        # The switch_target branch takes priority so an explicit user action
        # always overrides the default-phone preference.
        reject_reason = None
        if switch_target:
            if connecting_mac != switch_target:
                reject_reason = f"switch override active (target {switch_target})"
        elif preferred and connecting_mac != preferred:
            grace_remaining = DEFAULT_GRACE_SEC - (time.monotonic() - SCRIPT_START_AT)
            with preferred_probe["lock"]:
                probe_done = preferred_probe["done"]
                probe_reachable = preferred_probe["reachable"]
            # Skip grace early if we've confirmed preferred is offline.
            if probe_done and not probe_reachable:
                pass  # fall through to "is default currently connected?" check
            elif grace_remaining > 0:
                reject_reason = (f"default phone {preferred} has "
                                 f"{grace_remaining:.1f}s startup grace "
                                 f"(probe_done={probe_done} reachable={probe_reachable})")
            if not reject_reason:
                try:
                    objects = _get_managed_objects()
                    for path, ifaces in objects.items():
                        dev_props = ifaces.get("org.bluez.Device1")
                        if not dev_props:
                            continue
                        dev_mac = _normalize_mac(str(dev_props.get("Address", "")))
                        if dev_mac == preferred and bool(dev_props.get("Connected", False)):
                            reject_reason = f"default phone {preferred} is connected"
                            break
                except Exception as e:
                    oal_print(f"AA RFCOMM: error checking default phone: {e}", flush=True)

        # 4. No default phone set, but another phone is already BT-connected.
        #    Prevents wasteful credential exchange when no default is configured.
        if not reject_reason and not preferred and not switch_target:
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

        if reject_reason:
            _log_rejection(connecting_mac, reject_reason)
            _close_rejection_fd(fd)
            # Disconnect the rejected phone from BT entirely so its HFP/A2DP
            # audio doesn't bleed into the bridge. Without this, a paired but
            # non-default phone stays BT-connected and routes calls/media to
            # the bridge — a dead end that confuses both phones.
            threading.Thread(
                target=_disconnect_rejected_phone,
                args=(connecting_mac,),
                daemon=True
            ).start()
            return

        # Always complete the RFCOMM exchange — the phone needs WiFi credentials
        # to trigger its TCP connection. Closing/rejecting during cooldown blocks
        # the phone from ever connecting. The bridge's entity-active TCP guard
        # handles duplicate connections at the TCP level instead.
        oal_print(f"AA RFCOMM NewConnection from {device} fd={fd} mac={connecting_mac}", flush=True)
        threading.Thread(target=handle_aa_rfcomm, args=(fd, connecting_mac), daemon=True).start()
    @dbus.service.method(PROFILE_IFACE, in_signature="o", out_signature="")
    def RequestDisconnection(self, dev): oal_print(f"AA disconnect {dev}", flush=True)
    @dbus.service.method(PROFILE_IFACE, in_signature="", out_signature="")
    def Release(self): oal_print("AA Released", flush=True)

class HSPProfile(dbus.service.Object):
    @dbus.service.method(PROFILE_IFACE, in_signature="oha{sv}", out_signature="")
    def NewConnection(self, device, fd, props):
        _mark_bt_activity()
        oal_print(f"HSP NewConnection from {device}", flush=True)
    @dbus.service.method(PROFILE_IFACE, in_signature="o", out_signature="")
    def RequestDisconnection(self, dev): oal_print(f"HSP disconnect {dev}", flush=True)
    @dbus.service.method(PROFILE_IFACE, in_signature="", out_signature="")
    def Release(self): oal_print("HSP Released", flush=True)

# ── HFP Hands-Free Profile (car kit role) ─────────────────────────────
# The bridge is the HF (Hands-Free) unit. The phone is the AG (Audio Gateway).
# When the phone connects HFP, we do an AT command handshake to establish
# the service level connection. Once established, the phone can route
# call audio via SCO. SCO audio capture/playback is handled in C++.
#
# This handler does NOT process audio — it only handles the RFCOMM control
# channel for AT commands. The SCO socket is opened separately by C++.

# HFP HF supported features bitmask (AT+BRSF):
#   Bit 0: EC/NR (echo cancel / noise reduction) — 0 (not supported, phone handles it)
#   Bit 1: Three-way calling — 0
#   Bit 2: CLI presentation — 1 (we accept caller ID)
#   Bit 3: Voice recognition activation — 1 (forward to AA)
#   Bit 4: Remote volume control — 1
#   Bit 5: Enhanced call status — 0
#   Bit 6: Enhanced call control — 0
#   Bit 7: Codec negotiation (mSBC/CVSD) — 1
#   Bit 8: HF indicators — 0
#   Bit 9: eSCO S4 settings — 0
HFP_HF_FEATURES = (1 << 2) | (1 << 3) | (1 << 4) | (1 << 7)  # = 156

# Track HFP state
hfp_connected_device = None  # D-Bus path of connected phone
hfp_slc_established = False  # Service Level Connection up

def handle_hfp_rfcomm(fd, device):
    """Handle HFP AT command exchange on RFCOMM fd.
    
    This runs the HFP Service Level Connection (SLC) setup, then enters
    an AT command response loop. The fd is an RFCOMM socket from BlueZ
    Profile1.NewConnection — it's the control channel only, NOT audio.
    SCO audio is a separate BT connection handled by the C++ bridge.
    """
    global hfp_connected_device, hfp_slc_established

    fl = fcntl.fcntl(fd, fcntl.F_GETFL)
    fcntl.fcntl(fd, fcntl.F_SETFL, fl & ~os.O_NONBLOCK)

    hfp_connected_device = device
    ag_features = 0
    ag_indicators = []

    def send_at(response):
        """Send an AT response line to the phone (AG)."""
        line = response + "\r"
        os.write(fd, line.encode("utf-8"))

    def send_ok():
        send_at("\r\nOK")

    try:
        buf = b""
        # HF initiates SLC: send AT+BRSF (our features) first.
        # In HFP, the Hands-Free (client) must speak first.
        init_cmd = f"AT+BRSF={HFP_HF_FEATURES}\r"
        os.write(fd, init_cmd.encode("utf-8"))
        oal_print(f"[HFP] >> AT+BRSF={HFP_HF_FEATURES}", flush=True)

        while True:
            data = os.read(fd, 1024)
            if not data:
                break
            buf += data

            # Process complete AT commands (terminated by \r)
            while b"\r" in buf:
                line_bytes, buf = buf.split(b"\r", 1)
                line = line_bytes.decode("utf-8", errors="replace").strip()
                if not line:
                    continue

                oal_print(f"[HFP] << {line}", flush=True)

                # ── AG Responses (to commands we sent as HF) ─────
                if line.startswith("+BRSF:"):
                    # AG's supported features response
                    try:
                        ag_features = int(line.split(":")[1].strip())
                    except ValueError:
                        pass
                    oal_print(f"[HFP] AG features: {ag_features}", flush=True)
                    # Don't set slc_established here — wait for full SLC sequence

                elif line == "OK":
                    # AG acknowledged our last command.
                    # Drive the SLC setup state machine forward.
                    # Sequence: AT+BRSF → AT+CIND=? → AT+CMER → SLC done
                    # (AT+CIND? skipped — some phones don't respond to it)
                    if not hfp_slc_established:
                        if ag_features > 0 and not getattr(handle_hfp_rfcomm, '_sent_cind_test', False):
                            handle_hfp_rfcomm._sent_cind_test = True
                            send_at("AT+CIND=?")
                            oal_print("[HFP] >> AT+CIND=?", flush=True)
                        elif getattr(handle_hfp_rfcomm, '_sent_cind_test', False) and not getattr(handle_hfp_rfcomm, '_sent_cmer', False):
                            handle_hfp_rfcomm._sent_cmer = True
                            send_at("AT+CMER=3,0,0,1")
                            oal_print("[HFP] >> AT+CMER=3,0,0,1", flush=True)
                        elif getattr(handle_hfp_rfcomm, '_sent_cmer', False):
                            hfp_slc_established = True
                            oal_print("[HFP] SLC established (full)", flush=True)

                elif line.startswith("+CIND:"):
                    # Indicator mapping or values — just log
                    oal_print(f"[HFP] indicators: {line}", flush=True)

                elif line == "ERROR":
                    oal_print("[HFP] AG returned ERROR", flush=True)

                # ── AG-initiated commands (phone asking us) ──────
                elif line.startswith("AT+BRSF="):
                    # Phone sends its AG features (alternate flow)
                    try:
                        ag_features = int(line.split("=")[1])
                    except ValueError:
                        pass
                    oal_print(f"[HFP] AG features: {ag_features}", flush=True)
                    send_at(f"\r\n+BRSF: {HFP_HF_FEATURES}")
                    send_ok()

                elif line == "AT+CIND=?":
                    # Phone asks for indicator mapping
                    send_at('\r\n+CIND: ("service",(0,1)),("call",(0,1)),'
                            '("callsetup",(0-3)),("callheld",(0-2)),'
                            '("signal",(0-5)),("roam",(0,1)),'
                            '("battchg",(0-5))')
                    send_ok()

                elif line == "AT+CIND?":
                    # Phone asks for current indicator values
                    # service=1, call=0, callsetup=0, callheld=0,
                    # signal=5, roam=0, battery=5
                    send_at("\r\n+CIND: 1,0,0,0,5,0,5")
                    send_ok()

                elif line.startswith("AT+CMER="):
                    # Phone enables indicator event reporting
                    send_ok()

                elif line.startswith("AT+CHLD=?"):
                    # Phone asks for call hold capabilities
                    send_at("\r\n+CHLD: (0,1,2,3)")
                    send_ok()

                elif line.startswith("AT+BIND=?"):
                    # HF indicators feature list query
                    send_at("\r\n+BIND: (1,2)")
                    send_ok()

                elif line.startswith("AT+BIND?"):
                    # HF indicators status query
                    send_at("\r\n+BIND: 1,1")
                    send_at("\r\n+BIND: 2,1")
                    send_ok()

                elif line.startswith("AT+BIND="):
                    # HF indicators enable
                    send_ok()

                # ── Codec Negotiation ────────────────────────────
                elif line.startswith("AT+BAC="):
                    # Phone sends available codecs (1=CVSD, 2=mSBC)
                    oal_print(f"[HFP] codecs: {line.split('=')[1]}", flush=True)
                    send_ok()

                elif line.startswith("+BCS:"):
                    # AG selected codec — confirm it
                    codec = line.split(":")[1].strip()
                    oal_print(f"[HFP] codec selected: {codec}", flush=True)
                    send_at(f"AT+BCS={codec}")

                # ── Call Control ──────────────────────────────────
                elif line == "ATA":
                    # Answer incoming call
                    send_ok()
                    oal_print("[HFP] call answered", flush=True)

                elif line == "AT+CHUP":
                    # Hang up
                    send_ok()
                    oal_print("[HFP] call hung up", flush=True)

                elif line.startswith("ATD"):
                    # Dial
                    number = line[3:].rstrip(";")
                    oal_print(f"[HFP] dial: {number}", flush=True)
                    send_ok()

                elif line.startswith("AT+BVRA="):
                    # Voice recognition — forward to AA via bridge
                    vr_state = line.split("=")[1]
                    oal_print(f"[HFP] voice recognition: {vr_state}", flush=True)
                    send_ok()

                elif line.startswith("AT+VGS="):
                    # Speaker volume
                    send_ok()

                elif line.startswith("AT+VGM="):
                    # Mic volume
                    send_ok()

                elif line.startswith("AT+NREC="):
                    # Noise reduction / echo cancel request
                    send_ok()

                elif line.startswith("AT+BTRH?"):
                    # Bluetooth Response and Hold status
                    send_ok()

                elif line.startswith("AT+CLIP="):
                    # Calling Line ID enable
                    send_ok()

                elif line.startswith("AT+CCWA="):
                    # Call waiting notification enable
                    send_ok()

                elif line.startswith("AT+CMEE="):
                    # Extended error codes enable
                    send_ok()

                elif line.startswith("AT+CLCC"):
                    # List current calls
                    send_ok()

                elif line.startswith("AT+COPS"):
                    # Operator selection
                    if "=?" in line:
                        send_ok()
                    elif "?" in line:
                        send_at('\r\n+COPS: 0,0,"Carrier"')
                        send_ok()
                    else:
                        send_ok()

                elif line.startswith("AT+CNUM"):
                    # Subscriber number
                    send_ok()

                else:
                    # Unknown AT command — OK to avoid phone disconnect
                    oal_print(f"[HFP] unknown AT: {line}", flush=True)
                    send_ok()

    except Exception as e:
        oal_print(f"[HFP] RFCOMM error: {e}", flush=True)
    finally:
        os.close(fd)
        hfp_connected_device = None
        hfp_slc_established = False
        # Clean up SLC state machine attrs
        for attr in ('_sent_cind_test', '_sent_cmer'):
            if hasattr(handle_hfp_rfcomm, attr):
                delattr(handle_hfp_rfcomm, attr)
        _mark_bt_activity()
        oal_print("[HFP] disconnected", flush=True)


class HFPProfile(dbus.service.Object):
    """HFP Hands-Free profile — receives RFCOMM connections from phone's AG."""
    @dbus.service.method(PROFILE_IFACE, in_signature="oha{sv}", out_signature="")
    def NewConnection(self, device, fd, props):
        fd = fd.take()
        _mark_bt_activity()
        oal_print(f"[HFP] NewConnection from {device} fd={fd}", flush=True)
        threading.Thread(target=handle_hfp_rfcomm, args=(fd, device), daemon=True).start()
    @dbus.service.method(PROFILE_IFACE, in_signature="o", out_signature="")
    def RequestDisconnection(self, dev):
        oal_print(f"[HFP] disconnect {dev}", flush=True)
    @dbus.service.method(PROFILE_IFACE, in_signature="", out_signature="")
    def Release(self):
        oal_print("[HFP] Released", flush=True)

class BLEAd(dbus.service.Object):
    @dbus.service.method("org.freedesktop.DBus.Properties", in_signature="ss", out_signature="v")
    def Get(self, i, p): return self.GetAll(i)[p]
    @dbus.service.method("org.freedesktop.DBus.Properties", in_signature="s", out_signature="a{sv}")
    def GetAll(self, i):
        return {"Type": dbus.String("peripheral"), "ServiceUUIDs": dbus.Array([AA_UUID], signature="s"), "LocalName": dbus.String(BT_NAME)}
    @dbus.service.method(LE_AD_IFACE, in_signature="", out_signature="")
    def Release(self): pass

dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
bus = dbus.SystemBus()

# In USB mode the phone connects via AOAP (USB), not WiFi RFCOMM.
# Skip the AA profile registration and reconnect worker — they do nothing
# except fill the log with useless noise. HFP/HSP are still registered
# so call audio works regardless of connection mode.
_PHONE_MODE = os.environ.get("OAL_PHONE_MODE", "wireless").strip().lower()
_USB_MODE = (_PHONE_MODE == "usb")
if _USB_MODE:
    oal_print("Phone mode: USB — skipping AA RFCOMM profile and reconnect worker", flush=True)

# Agent
agent = Agent(bus, "/pi_aa/agent")
am = dbus.Interface(bus.get_object("org.bluez", "/org/bluez"), "org.bluez.AgentManager1")
try:
    am.RegisterAgent("/pi_aa/agent", "NoInputNoOutput")
    am.RequestDefaultAgent("/pi_aa/agent")
    oal_print("Agent registered", flush=True)
except dbus.exceptions.DBusException as e:
    oal_print(f"Agent registration: {e} (continuing)", flush=True)

# AA Profile (channel 8)
pm = dbus.Interface(bus.get_object("org.bluez", "/org/bluez"), "org.bluez.ProfileManager1")
if not _USB_MODE:
    # AA RFCOMM profile — only needed for wireless mode (WiFi credential exchange)
    aa = AAProfile(bus, "/pi_aa/aa")
    sdp = ('<?xml version="1.0" encoding="UTF-8" ?><record>'
        '<attribute id="0x0001"><sequence>'
        '<uuid value="' + AA_UUID + '" /><uuid value="0x1101" />'
        '</sequence></attribute>'
        '<attribute id="0x0004"><sequence>'
        '<sequence><uuid value="0x0100" /></sequence>'
        '<sequence><uuid value="0x0003" /><uint8 value="0x08" /></sequence>'
        '</sequence></attribute>'
        '<attribute id="0x0005"><sequence><uuid value="0x1002" /></sequence></attribute>'
        '<attribute id="0x0009"><sequence><sequence>'
        '<uuid value="0x1101" /><uint16 value="0x0102" />'
        '</sequence></sequence></attribute>'
        '<attribute id="0x0100"><text value="Android Auto Wireless" /></attribute>'
        '<attribute id="0x0101"><text value="AndroidAuto WiFi projection automatic setup" /></attribute>'
        '</record>')
    try:
        pm.RegisterProfile("/pi_aa/aa", AA_UUID, {
            "Name": "AA Wireless", "Role": "server",
            "Channel": dbus.UInt16(AA_CHANNEL), "AutoConnect": True,
            "RequireAuthentication": False, "RequireAuthorization": False,
            "ServiceRecord": sdp})
        oal_print(f"AA profile ch={AA_CHANNEL}", flush=True)
    except dbus.exceptions.DBusException as e:
        oal_print(f"AA profile: {e} (continuing)", flush=True)

# HSP HS Profile (kept for backward compat — some phones only do HSP)
hsp = HSPProfile(bus, "/pi_aa/hsp")
try:
    pm.RegisterProfile("/pi_aa/hsp", HSP_HS_UUID, {"Name": "HSP HS"})
    oal_print("HSP HS profile", flush=True)
except dbus.exceptions.DBusException as e:
    oal_print(f"HSP profile: {e} (continuing)", flush=True)

# HFP HF Profile (Hands-Free — car kit role, primary for phone calls)
hfp = HFPProfile(bus, "/pi_aa/hfp")
try:
    pm.RegisterProfile("/pi_aa/hfp", HFP_HF_UUID, {
        "Name": "HFP HF", "Role": "client",
        "RequireAuthentication": False, "RequireAuthorization": False,
        "Features": dbus.UInt16(HFP_HF_FEATURES),
        "Version": dbus.UInt16(0x0108)})  # HFP 1.8
    oal_print("HFP HF profile registered", flush=True)
except dbus.exceptions.DBusException as e:
    oal_print(f"HFP profile: {e} (continuing)", flush=True)

# BLE Advertisement
# BLE Advertisement — only useful in wireless mode (advertises AA WiFi presence)
if not _USB_MODE:
    ble = BLEAd(bus, "/pi_aa/ble")
    objs = dbus.Interface(bus.get_object("org.bluez", "/"), "org.freedesktop.DBus.ObjectManager").GetManagedObjects()
    for p, i in objs.items():
        if "org.bluez.LEAdvertisingManager1" in i:
            dbus.Interface(bus.get_object("org.bluez", p), "org.bluez.LEAdvertisingManager1").RegisterAdvertisement(
                "/pi_aa/ble", {},
                reply_handler=lambda: oal_print("BLE AD ok", flush=True),
                error_handler=lambda e: oal_print(f"BLE AD err: {e}", flush=True))
            break

# Adapter settings
ap = _wait_for_adapter_properties()
ap.Set("org.bluez.Adapter1", "Powered", dbus.Boolean(True))
# Read persisted pairing-mode from disk — survives reboots. User can disable
# new pairings via the app and the state is remembered.
_pairing_enabled = _read_pairing_mode()
ap.Set("org.bluez.Adapter1", "Discoverable", dbus.Boolean(_pairing_enabled))
ap.Set("org.bluez.Adapter1", "Pairable", dbus.Boolean(_pairing_enabled))
ap.Set("org.bluez.Adapter1", "DiscoverableTimeout", dbus.UInt32(0))
ap.Set("org.bluez.Adapter1", "Alias", BT_NAME)
# Set device class and SSP mode. These require raw HCI ioctls that BlueZ
# doesn't expose via D-Bus. hciconfig wraps them — one-shot, not a process.
import subprocess
subprocess.run(["hciconfig", "hci0", "class", "0x200418"],
               capture_output=True, timeout=5)
subprocess.run(["hciconfig", "hci0", "sspmode", "1"],
               capture_output=True, timeout=5)
oal_print("Adapter set (class=0x200418 Car Audio, SSP=on)", flush=True)

# Try to read wlan0 BSSID at startup
if not _USB_MODE:
    try:
        with open("/sys/class/net/wlan0/address") as f:
            WIFI_BSSID = f.read().strip().upper()
        oal_print(f"WiFi BSSID: {WIFI_BSSID}", flush=True)
    except Exception:
        pass

    # Periodically retry wireless AA reconnects. Prefer OAL_BT_MAC when configured,
    # but fall back to the remaining paired phones so multi-phone switching still works.
    threading.Thread(target=_reconnect_worker, daemon=True).start()

    # Proactively probe the preferred phone's reachability so the AA RFCOMM gate
    # can skip grace when default phone is confirmed offline. Concurrent with
    # phone's own BT auto-reconnect — whichever succeeds first wins.
    _preferred_mac_for_probe = _preferred_bt_mac()
    if _preferred_mac_for_probe:
        try:
            for _path, _ifaces in _get_managed_objects().items():
                _dev_props = _ifaces.get("org.bluez.Device1")
                if _dev_props and _normalize_mac(str(_dev_props.get("Address", ""))) == _preferred_mac_for_probe:
                    threading.Thread(
                        target=_probe_preferred_async,
                        args=(_path, _preferred_mac_for_probe),
                        daemon=True).start()
                    break
            else:
                # Preferred MAC configured but not in BlueZ's paired list — treat as unreachable.
                with preferred_probe["lock"]:
                    preferred_probe["done"] = True
                    preferred_probe["reachable"] = False
                oal_print(f"Preferred probe: {_preferred_mac_for_probe} NOT PAIRED, marking unreachable", flush=True)
        except Exception as e:
            oal_print(f"Preferred probe launch: {e}", flush=True)

oal_print("All services running", flush=True)
GLib.MainLoop().run()
