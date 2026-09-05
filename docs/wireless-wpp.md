# Wireless WPP setup

Android Auto 17.4 disabled the exported startup receiver used by older third-party wireless receivers. OpenAutoLink restored cold-start projection by implementing Google's factory Bluetooth Wi-Fi Projection Protocol (WPP) path.

On the validated GM topology, the head unit cannot accept the phone's inbound projection socket directly. OpenAutoLink therefore advertises a loopback proxy owned by the companion app; the companion holds that phone-side endpoint and relays the session to the car. **Wireless WPP requires the companion.**

## Requirements

- matching current OpenAutoLink releases on the car and phone;
- Android phone paired to the vehicle over Bluetooth;
- Bluetooth **Phone calls** profile enabled;
- vehicle Wi-Fi access point enabled;
- access point SSID, password, and BSSID;
- OpenAutoLink transport set to **Wireless (WPP)**;
- companion auto-start associated with the vehicle's Bluetooth connection.

## Fresh setup

### 1. Install both components

- Install the car app through the supported vehicle path.
- Install `openautolink-companion-vX.Y.Z.apk` from the official GitHub release on the phone.

### 2. Enable the vehicle access point

Open the vehicle's network/hotspot settings and enable its Wi-Fi access point. Record:

- network name (SSID);
- password;
- access point BSSID—the AP's MAC address, not the phone's or the vehicle Bluetooth address.

If the vehicle UI does not display the BSSID, connect the phone to the access point and read the BSSID from the phone's detailed Wi-Fi connection information or a Wi-Fi analyzer. It must have the form `aa:bb:cc:dd:ee:ff`; zero and broadcast addresses are invalid.

No paid vehicle data plan is required for projection. OpenAutoLink uses the access point as a local network.

### 3. Configure the car app

In OpenAutoLink, open **Settings → Connection**:

1. Under **Transport**, select **Wireless (WPP)**.
2. Under **Wi-Fi details sent to the phone**, enter:
   - **Network name (SSID)**;
   - **Password**;
   - **Access point BSSID**.
3. Leave **Hotspot frequency in MHz** blank unless logs show `WiFi channels not supported`.
4. Under **WPP network interface**, select the interface serving the vehicle access point. The GM default is preselected, but verify it against the active IPv4 interfaces if projection cannot connect.
5. Tap **Save & Reconnect**.

OpenAutoLink restricts WPP advertisement, companion discovery, and session traffic to this one interface. It does not fall back across other Wi-Fi or Ethernet interfaces.

#### Send SSID/BSSID from the phone instead of typing them

With both components updated and the phone paired to the car:

1. On the phone, select the vehicle under **Auto-Start → Select Devices**.
2. In the car's WPP settings, tap **Start WPP config listener**.
3. On the phone, open **Send WPP WiFi to Car → Scan & Send to Car** and select the car's access point.
4. Wait for the phone's confirmation, then check the SSID and BSSID shown in the car settings.
5. Enter the Wi-Fi password manually, verify the WPP interface, and tap **Save & Reconnect**.

Only SSID and BSSID are transferred; the password and interface selection are unchanged. The listener is opt-in and stops after a successful transfer. Tap **Stop WPP config listener** to cancel it. If sending fails, restart the listener and retry, or use the manual fields above. The phone needs Wi-Fi scan/location permissions and Bluetooth enabled.

### 4. Configure the companion

1. Open the companion.
2. Under **Auto-Start**, keep **Bluetooth + WiFi Scan (recommended)**.
3. Tap **Select Devices** and select the vehicle's Bluetooth device.
4. On Android Auto 17.4+, remove any entries under the companion's legacy **Car WiFi** section. Android Auto now owns the Wi-Fi association; the companion must not compete for it.
5. Allow the companion to run in the background without battery optimization stopping it.

The companion's legacy **Car WiFi Setup Guide** describes the Android Auto 17.3-and-older flow. Do not use that flow for WPP on 17.4+.

### 5. Re-pair Bluetooth

After installing or upgrading to WPP:

1. Forget the vehicle in the phone's Bluetooth settings.
2. Forget the phone in the vehicle if necessary.
3. Pair again.
4. Keep **Phone calls** enabled for that pairing.
5. **Media audio** may be disabled to prevent the vehicle's native media player from competing with Android Auto.

Re-pairing matters because the phone caches the services advertised by a bonded device. It may not discover OpenAutoLink's Android Auto Wireless service until the bond is recreated.

### 6. Connect

Open OpenAutoLink in the car. The normal sequence is:

```text
vehicle Bluetooth connects
→ companion starts
→ WPP Bluetooth handshake completes
→ Android Auto joins the vehicle access point
→ phone connects to its companion loopback proxy
→ companion and car establish the projection bridge
→ video, audio, and input begin
```

## Migrating from Android Auto 17.3 or older

1. Update both OpenAutoLink components.
2. Select **Wireless (WPP)** in the car app.
3. Enter the access point SSID, password, and BSSID in the car app.
4. Remove legacy **Car WiFi** entries from the companion.
5. Re-pair the phone and vehicle over Bluetooth.
6. Keep **Phone calls** enabled.
7. Save and reconnect.

The older **Wi-Fi / Car Hotspot** mode cannot cold-start current Android Auto releases because its phone-side startup entry point is disabled.

## Saved Wi-Fi profile and internet behavior

A normal saved Wi-Fi profile and WPP's local-only request can both refer to the same vehicle access point:

- **Vehicle access point has internet:** keeping the normal saved profile may let the phone use it for general internet while Android Auto binds projection to the same network.
- **Vehicle access point has no internet:** letting WPP create the local-only association is usually cleaner. General internet remains on cellular or another primary network where the phone supports it.
- **Repeated handoff or duplicate-network trouble:** forget the manually saved copy and let WPP request the network itself.

Android decides whether it can reuse an existing association or maintain concurrent Wi-Fi links. OpenAutoLink cannot force dual-STA support on a phone.

## Multiple phones

On Android Auto 17.4+, WPP projects to the phone currently connected through the vehicle's Bluetooth stack. Switch drivers in the vehicle's own Bluetooth settings.

Give each phone a unique Bluetooth device name. The companion uses that identity to bind the active Bluetooth phone to the correct stable companion ID.

The direct in-app phone switcher remains useful for the legacy Car Hotspot mode on Android Auto 17.3 and older; it cannot replace the privileged vehicle Bluetooth switch on current WPP.

## Quick diagnosis

### Nothing happens after selecting Wireless (WPP)

Check all of these:

- SSID is not blank;
- BSSID is present and correctly formatted;
- password matches the security mode;
- vehicle and phone were re-paired after WPP was installed;
- Bluetooth **Phone calls** is enabled;
- companion is installed, allowed to run, and assigned to the vehicle under **Select Devices**.

### Phone joins Wi-Fi but projection does not start

- Confirm **WPP network interface** is the active interface serving the vehicle access point.
- Do not expect discovery to fall back to another interface; WPP is deliberately restricted to the selected one.
- If logs report unsupported channels, enter the live hotspot frequency.
- Confirm the companion is alive; Wi-Fi association alone does not establish the relay.

### Connection drops repeatedly

- Remove legacy companion **Car WiFi** entries.
- Re-pair Bluetooth.
- Keep **Phone calls** enabled.
- Remove duplicate saved versions of the vehicle Wi-Fi network if it has no internet.
- Use unique Bluetooth device names when multiple phones are present.

For more, see [Troubleshooting](troubleshooting.md).
