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
import os, time, threading, struct, fcntl

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

def handle_aa_rfcomm(fd):
    """Handle the AA wireless WiFi credential exchange over RFCOMM."""
    fl = fcntl.fcntl(fd, fcntl.F_GETFL)
    fcntl.fcntl(fd, fcntl.F_SETFL, fl & ~os.O_NONBLOCK)

    try:
        print(f"Sending WifiStartRequest ip={WIFI_IP} port={WIFI_PORT}", flush=True)
        rfcomm_send(fd, 1, pbs(1, WIFI_IP) + pbi(2, WIFI_PORT))

        mt, d = rfcomm_recv(fd)
        print(f"Got msg type={mt} len={len(d)}", flush=True)

        print(f"Sending WifiInfoResponse ssid={WIFI_SSID}", flush=True)
        rfcomm_send(fd, 3, pbs(1, WIFI_SSID) + pbs(2, WIFI_KEY) + pbs(3, WIFI_BSSID) + pbi(4, 8) + pbi(5, 1))

        mt2, d2 = rfcomm_recv(fd)
        print(f"Got msg type={mt2} len={len(d2)}", flush=True)

        mt3, d3 = rfcomm_recv(fd)
        print(f"Got msg type={mt3} len={len(d3)}", flush=True)

        print("WiFi credential exchange complete!", flush=True)
    except Exception as e:
        print(f"RFCOMM exchange error: {e}", flush=True)
    finally:
        os.close(fd)


class Agent(dbus.service.Object):
    @dbus.service.method(AGENT_IFACE, in_signature="ou", out_signature="")
    def RequestConfirmation(self, d, p):
        print(f"Auto-confirm {d} passkey={p}", flush=True)
        # Return without error = auto-accept
    @dbus.service.method(AGENT_IFACE, in_signature="o", out_signature="s")
    def RequestPinCode(self, d):
        print(f"Auto-pin {d} -> 123456", flush=True)
        return "123456"
    @dbus.service.method(AGENT_IFACE, in_signature="o", out_signature="u")
    def RequestPasskey(self, d):
        print(f"Auto-passkey {d}", flush=True)
        return dbus.UInt32(0)
    @dbus.service.method(AGENT_IFACE, in_signature="ou", out_signature="")
    def DisplayPasskey(self, d, p):
        print(f"Display passkey {d} {p}", flush=True)
    @dbus.service.method(AGENT_IFACE, in_signature="os", out_signature="")
    def DisplayPinCode(self, d, p):
        print(f"Display pin {d} {p}", flush=True)
    @dbus.service.method(AGENT_IFACE, in_signature="o", out_signature="")
    def RequestAuthorization(self, d):
        print(f"Auto-authorize {d}", flush=True)
    @dbus.service.method(AGENT_IFACE, in_signature="os", out_signature="")
    def AuthorizeService(self, d, u):
        print(f"Auto-authorize-svc {d} {u}", flush=True)
    @dbus.service.method(AGENT_IFACE, in_signature="", out_signature="")
    def Release(self): pass
    @dbus.service.method(AGENT_IFACE, in_signature="", out_signature="")
    def Cancel(self): pass

class AAProfile(dbus.service.Object):
    @dbus.service.method(PROFILE_IFACE, in_signature="oha{sv}", out_signature="")
    def NewConnection(self, device, fd, props):
        fd = fd.take()
        print(f"AA RFCOMM NewConnection from {device} fd={fd}", flush=True)
        threading.Thread(target=handle_aa_rfcomm, args=(fd,), daemon=True).start()
    @dbus.service.method(PROFILE_IFACE, in_signature="o", out_signature="")
    def RequestDisconnection(self, dev): print(f"AA disconnect {dev}", flush=True)
    @dbus.service.method(PROFILE_IFACE, in_signature="", out_signature="")
    def Release(self): print("AA Released", flush=True)

class HSPProfile(dbus.service.Object):
    @dbus.service.method(PROFILE_IFACE, in_signature="oha{sv}", out_signature="")
    def NewConnection(self, device, fd, props):
        print(f"HSP NewConnection from {device}", flush=True)
    @dbus.service.method(PROFILE_IFACE, in_signature="o", out_signature="")
    def RequestDisconnection(self, dev): print(f"HSP disconnect {dev}", flush=True)
    @dbus.service.method(PROFILE_IFACE, in_signature="", out_signature="")
    def Release(self): print("HSP Released", flush=True)

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

                print(f"[HFP] << {line}", flush=True)

                # ── SLC Setup Phase ──────────────────────────────
                if line.startswith("AT+BRSF="):
                    # Phone sends its AG features
                    try:
                        ag_features = int(line.split("=")[1])
                    except ValueError:
                        pass
                    print(f"[HFP] AG features: {ag_features}", flush=True)
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
                    print(f"[HFP] codecs: {line.split('=')[1]}", flush=True)
                    send_ok()

                elif line.startswith("+BCS:"):
                    # AG selected codec — confirm it
                    codec = line.split(":")[1].strip()
                    print(f"[HFP] codec selected: {codec}", flush=True)
                    send_at(f"AT+BCS={codec}")

                # ── Call Control ──────────────────────────────────
                elif line == "ATA":
                    # Answer incoming call
                    send_ok()
                    print("[HFP] call answered", flush=True)

                elif line == "AT+CHUP":
                    # Hang up
                    send_ok()
                    print("[HFP] call hung up", flush=True)

                elif line.startswith("ATD"):
                    # Dial
                    number = line[3:].rstrip(";")
                    print(f"[HFP] dial: {number}", flush=True)
                    send_ok()

                elif line.startswith("AT+BVRA="):
                    # Voice recognition — forward to AA via bridge
                    vr_state = line.split("=")[1]
                    print(f"[HFP] voice recognition: {vr_state}", flush=True)
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
                    print(f"[HFP] unknown AT: {line}", flush=True)
                    send_ok()

                # After SLC setup, mark connection as established
                if not hfp_slc_established and ag_features > 0:
                    hfp_slc_established = True
                    print("[HFP] SLC established", flush=True)

    except Exception as e:
        print(f"[HFP] RFCOMM error: {e}", flush=True)
    finally:
        os.close(fd)
        hfp_connected_device = None
        hfp_slc_established = False
        print("[HFP] disconnected", flush=True)


class HFPProfile(dbus.service.Object):
    """HFP Hands-Free profile — receives RFCOMM connections from phone's AG."""
    @dbus.service.method(PROFILE_IFACE, in_signature="oha{sv}", out_signature="")
    def NewConnection(self, device, fd, props):
        fd = fd.take()
        print(f"[HFP] NewConnection from {device} fd={fd}", flush=True)
        threading.Thread(target=handle_hfp_rfcomm, args=(fd, device), daemon=True).start()
    @dbus.service.method(PROFILE_IFACE, in_signature="o", out_signature="")
    def RequestDisconnection(self, dev):
        print(f"[HFP] disconnect {dev}", flush=True)
    @dbus.service.method(PROFILE_IFACE, in_signature="", out_signature="")
    def Release(self):
        print("[HFP] Released", flush=True)

class BLEAd(dbus.service.Object):
    @dbus.service.method("org.freedesktop.DBus.Properties", in_signature="ss", out_signature="v")
    def Get(self, i, p): return self.GetAll(i)[p]
    @dbus.service.method("org.freedesktop.DBus.Properties", in_signature="s", out_signature="a{sv}")
    def GetAll(self, i):
        return {"Type": dbus.String("peripheral"), "ServiceUUIDs": dbus.Array([AA_UUID], signature="s"), "LocalName": dbus.String("OpenAutoLink")}
    @dbus.service.method(LE_AD_IFACE, in_signature="", out_signature="")
    def Release(self): pass

dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
bus = dbus.SystemBus()

# Agent
agent = Agent(bus, "/pi_aa/agent")
am = dbus.Interface(bus.get_object("org.bluez", "/org/bluez"), "org.bluez.AgentManager1")
try:
    am.RegisterAgent("/pi_aa/agent", "NoInputNoOutput")
    am.RequestDefaultAgent("/pi_aa/agent")
    print("Agent registered", flush=True)
except dbus.exceptions.DBusException as e:
    print(f"Agent registration: {e} (continuing)", flush=True)

# AA Profile (channel 8)
aa = AAProfile(bus, "/pi_aa/aa")
pm = dbus.Interface(bus.get_object("org.bluez", "/org/bluez"), "org.bluez.ProfileManager1")
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
    print(f"AA profile ch={AA_CHANNEL}", flush=True)
except dbus.exceptions.DBusException as e:
    print(f"AA profile: {e} (continuing)", flush=True)

# HSP HS Profile (kept for backward compat — some phones only do HSP)
hsp = HSPProfile(bus, "/pi_aa/hsp")
try:
    pm.RegisterProfile("/pi_aa/hsp", HSP_HS_UUID, {"Name": "HSP HS"})
    print("HSP HS profile", flush=True)
except dbus.exceptions.DBusException as e:
    print(f"HSP profile: {e} (continuing)", flush=True)

# HFP HF Profile (Hands-Free — car kit role, primary for phone calls)
hfp = HFPProfile(bus, "/pi_aa/hfp")
try:
    pm.RegisterProfile("/pi_aa/hfp", HFP_HF_UUID, {
        "Name": "HFP HF", "Role": "client",
        "RequireAuthentication": False, "RequireAuthorization": False,
        "Features": dbus.UInt16(HFP_HF_FEATURES),
        "Version": dbus.UInt16(0x0108)})  # HFP 1.8
    print("HFP HF profile registered", flush=True)
except dbus.exceptions.DBusException as e:
    print(f"HFP profile: {e} (continuing)", flush=True)

# BLE Advertisement
ble = BLEAd(bus, "/pi_aa/ble")
objs = dbus.Interface(bus.get_object("org.bluez", "/"), "org.freedesktop.DBus.ObjectManager").GetManagedObjects()
for p, i in objs.items():
    if "org.bluez.LEAdvertisingManager1" in i:
        dbus.Interface(bus.get_object("org.bluez", p), "org.bluez.LEAdvertisingManager1").RegisterAdvertisement(
            "/pi_aa/ble", {},
            reply_handler=lambda: print("BLE AD ok", flush=True),
            error_handler=lambda e: print(f"BLE AD err: {e}", flush=True))
        break

# Adapter settings
ap = dbus.Interface(bus.get_object("org.bluez", "/org/bluez/hci0"), "org.freedesktop.DBus.Properties")
ap.Set("org.bluez.Adapter1", "Powered", dbus.Boolean(True))
ap.Set("org.bluez.Adapter1", "Discoverable", dbus.Boolean(True))
ap.Set("org.bluez.Adapter1", "Pairable", dbus.Boolean(True))
ap.Set("org.bluez.Adapter1", "DiscoverableTimeout", dbus.UInt32(0))
ap.Set("org.bluez.Adapter1", "Alias", "OpenAutoLink")
# Set device class and SSP mode. These require raw HCI ioctls that BlueZ
# doesn't expose via D-Bus. hciconfig wraps them — one-shot, not a process.
import subprocess
subprocess.run(["hciconfig", "hci0", "class", "0x200418"],
               capture_output=True, timeout=5)
subprocess.run(["hciconfig", "hci0", "sspmode", "1"],
               capture_output=True, timeout=5)
print("Adapter set (class=0x200418 Car Audio, SSP=on)", flush=True)

# Try to read wlan0 BSSID at startup
try:
    with open("/sys/class/net/wlan0/address") as f:
        WIFI_BSSID = f.read().strip().upper()
    print(f"WiFi BSSID: {WIFI_BSSID}", flush=True)
except Exception:
    pass

# Connect to first paired phone's HFP AG (then HSP AG as fallback) after delay.
# This triggers BT reconnection on boot — the phone sees the connection and
# may auto-start Android Auto (which initiates WiFi TCP to the bridge).
def do_connect():
    time.sleep(5)
    try:
        obj_mgr = dbus.Interface(bus.get_object("org.bluez", "/"),
                                 "org.freedesktop.DBus.ObjectManager")
        objects = obj_mgr.GetManagedObjects()
        for path, ifaces in objects.items():
            if "org.bluez.Device1" in ifaces:
                props = ifaces["org.bluez.Device1"]
                if props.get("Paired", False):
                    print(f"Found paired device: {path}", flush=True)
                    dev = dbus.Interface(bus.get_object("org.bluez", path),
                                        "org.bluez.Device1")
                    dp = dbus.Interface(bus.get_object("org.bluez", path),
                                        "org.freedesktop.DBus.Properties")
                    dp.Set("org.bluez.Device1", "Trusted", dbus.Boolean(True))
                    # Try HFP AG first (primary for phone calls)
                    try:
                        dev.ConnectProfile(HFP_AG_UUID)
                        print(f"Connected HFP AG to {path}", flush=True)
                    except Exception as e2:
                        print(f"HFP AG connect {path}: {e2}", flush=True)
                        # Fall back to HSP AG
                        try:
                            dev.ConnectProfile(HSP_AG_UUID)
                            print(f"Connected HSP AG to {path}", flush=True)
                        except Exception as e3:
                            print(f"HSP AG connect {path}: {e3}", flush=True)
                    # Also try a generic Connect() to trigger AA auto-connect
                    # This tells the phone "I'm your car" which may kick off
                    # the Android Auto WiFi connection sequence automatically.
                    try:
                        dev.Connect()
                        print(f"Generic Connect to {path}", flush=True)
                    except Exception:
                        pass  # Already connected via profile, this is fine
                    break
    except Exception as e:
        print(f"Phone connect: {e}", flush=True)

threading.Thread(target=do_connect, daemon=True).start()
print("All services running", flush=True)
GLib.MainLoop().run()
