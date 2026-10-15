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

You do not need to find or type the BSSID yourself: the companion's **Send WPP WiFi to Car** control can scan the hotspot and fill in the car's SSID/BSSID fields over Bluetooth. Follow the [phone-to-car walkthrough below](#send-ssidbssid-from-the-phone-instead-of-typing-them). You still enter the hotspot password in the car app.

For manual setup, if the vehicle UI does not display the BSSID, connect the phone to the access point and read the BSSID from the phone's detailed Wi-Fi connection information or a Wi-Fi analyzer. It must have the form `aa:bb:cc:dd:ee:ff`; zero and broadcast addresses are invalid.

No paid vehicle data plan is required for projection. OpenAutoLink uses the access point as a local network.

### 3. Configure the car app

In OpenAutoLink, open **Settings → Connection**:

1. Under **Android Auto connection**, select **Wireless** (WPP). Older builds label this **Transport → Wireless (WPP)**.
2. Under **Wi-Fi details sent to the phone**, enter:
   - **Network name (SSID)**;
   - **Password**;
   - **Access point BSSID**.
3. Leave **Hotspot frequency in MHz** blank unless logs show `WiFi channels not supported`.
4. Under **WPP network interface**, select the interface serving the vehicle access point. The GM default is preselected, but verify it against the active IPv4 interfaces if projection cannot connect.
5. Tap **Save & Reconnect**.

OpenAutoLink restricts WPP advertisement, companion discovery, and session traffic to this one interface. It does not fall back across other Wi-Fi or Ethernet interfaces.

#### Send SSID/BSSID from the phone instead of typing them

Use **Send WPP WiFi to Car** in the Android companion to avoid typing the hotspot name and its MAC address on the car screen. This is a setup helper, not a projection-start button. It sends the selected hotspot's **SSID and BSSID over Bluetooth**; the phone does not need to join the hotspot or have projection working first.

Do this while parked, near the car, with current car and companion releases installed, the car hotspot enabled, and the phone paired to the vehicle. Turn on phone Wi-Fi and Bluetooth, grant the companion's requested nearby-device and Wi-Fi scan/location permissions, and enable phone Location if Android requires it for Wi-Fi scans.

1. **Phone:** open the companion. Under **Auto-Start → Select Devices**, select the vehicle's Bluetooth device. For this setup, select only the car you intend to configure; the send control uses that target list.
2. **Car:** in OpenAutoLink, open **Settings → Connection**, select **Wireless** under **Android Auto connection**, then scroll through the WPP fields to **Start WPP config listener**. Tap it and wait for **Listening for companion SSID/BSSID config**. Leave these settings open.
3. **Phone:** on the companion's main screen, scroll below **Auto-Start** to **Send WPP WiFi to Car**. Tap **Scan & Send to Car**.
4. In **Select visible WiFi**, tap the **car hotspot's network name**. Each row also shows its BSSID, channel, band and signal strength. Choose the car's hotspot—not home Wi-Fi or the phone's own hotspot. If the car advertises both bands under the same name, use its 5 GHz entry where available; the list puts 5 GHz entries first. **Tapping the row sends it immediately**; there is no separate Send button in the picker.
5. Wait for the phone to show **Sent … to … selected car Bluetooth device(s).** Then check that **Network name (SSID)** and **Access point BSSID** in the car settings match the selected row. The listener stops after applying the transfer; this does not mean projection has started.
6. **Car:** enter or verify **Password** separately, and verify **WPP network interface**. On GM EVs the normal AP interface is `ap_br_swlan0`; use the interface actually serving your car hotspot. Leave **Hotspot frequency in MHz** blank unless the channel troubleshooting above applies. Tap **Save & Reconnect** to apply the completed WPP settings, then continue with companion setup below.

**What this does not change:** the Wi-Fi password, WPP network interface and optional frequency are not transferred. It does not configure the companion's legacy **Car WiFi** auto-join list; leave that list empty on Android Auto 17.4+ so Android Auto owns Wi-Fi association. You do not need to repeat the transfer on every drive—use it for initial setup or if the hotspot's SSID/BSSID changes.

**If the helper does not work:**

- **Scan & Send to Car is disabled:** select the paired vehicle under **Auto-Start → Select Devices** first.
- **No visible WiFi networks found, or the car hotspot is missing:** check that the hotspot and phone Wi-Fi are on, and scan/location permissions are granted. The picker uses Android's available scan results. Open the phone's system Wi-Fi list to let it refresh, then return to the companion and reopen **Scan & Send to Car**. Joining the hotspot is not required for the transfer.
- **Send failed / no car acknowledged:** verify Bluetooth is enabled, the intended car is paired and selected, and the car shows **Listening for companion SSID/BSSID config** before retrying. Restart the listener if needed. Check the car's fields before retrying in case it applied the update but the acknowledgement was lost.
- **Cancel:** tap **Close** in the phone's picker without choosing a row, or **Stop WPP config listener** on the car while it is listening.

Manual SSID/BSSID entry remains available if the Bluetooth transfer cannot complete.

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
