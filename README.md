<p align="center">
  <img src="assets/play_store_512.png" alt="OpenAutoLink" width="128">
  <br><br>
  <a href="https://buymeacoffee.com/mossyhub">
    <img src="https://img.shields.io/badge/Buy%20Me%20A%20Coffee-Support%20OpenAutoLink-FFDD00?style=for-the-badge&logo=buymeacoffee&logoColor=000000" alt="Buy Me a Coffee" width="280">
  </a>
</p>

# OpenAutoLink — Android Auto & Apple CarPlay

> Bring Android Auto and Apple CarPlay to Android Automotive OS head units.<br>
> Phone projection on the car's existing screen. No SBC, dongle, or additional projection hardware.

OpenAutoLink runs on the vehicle's **Android Automotive OS (AAOS)** head unit. Android Automotive is the car's operating system; **Android Auto and Apple CarPlay are the phone-projection experiences** that OAL receives. OAL does not replace the car's operating system.

Android Auto and CarPlay share one app, with independent connection settings. **USB CarPlay is supported alongside wireless and USB Android Auto.** Wireless CarPlay remains available for development but is not working in the maintainer's vehicle.

[Releases](https://github.com/mossyhub/openautolink/releases/latest) · [Installation](#installation) · [Android Auto setup](#android-auto-setup) · [CarPlay setup](#carplay-setup) · [Compatibility](docs/compatibility.md) · [Troubleshooting](docs/troubleshooting.md) · [XDA discussion](https://xdaforums.com/t/open-source-openautolink-wireless-android-auto-bridge-for-aaos-gm-evs.4785192/)

[![CI](https://github.com/mossyhub/openautolink/actions/workflows/ci.yml/badge.svg)](https://github.com/mossyhub/openautolink/actions/workflows/ci.yml)
[![Release](https://github.com/mossyhub/openautolink/actions/workflows/release-apk.yml/badge.svg?event=release)](https://github.com/mossyhub/openautolink/releases/latest)

> [!IMPORTANT]
> Install on a locked GM vehicle through your own Google Play Console app's **dedicated Android Automotive OS Internal Test**. APK sideloading is not supported on these head units.
>
> **Create your personal AAB at [oal.mossyg.com](https://oal.mossyg.com)** in a desktop Chrome or Edge browser—no source editing, desktop download or Android SDK setup.
>
> Save the required encrypted web identity backup for updates. CarPlay also needs your own compatible, authorized authentication pair; the builder does not supply it.
>
> [Create your AAB](docs/build-aab.md) · [Internal Test installation](docs/install-gm.md) · [CarPlay authentication](docs/carplay-private-mfi-assets.md)

## Choose your phone and connection

| Phone / mode | Connection | Companion needed? | Notes |
|---|---|---|---|
| Android Auto, wireless | Bluetooth + car Wi-Fi (WPP) | **Yes**, for GM wireless | Use WPP for Android Auto 17.4+ |
| Android Auto, USB | USB cable | **No** | GM shows [two USB access prompts on every connection](#gm-usb-prompts--android-auto-and-carplay), even with the choice saved |
| CarPlay, USB | USB cable; leave iPhone unpaired from car Bluetooth | **No iPhone companion** | Car microphone for calls/Siri; one-time call setup. Requires your authentication files and USB/VPN approval. [GM USB prompts](#gm-usb-prompts--android-auto-and-carplay) |
| CarPlay, wireless | Bluetooth + Wi-Fi | **No iPhone companion** | Available for development; not working in the maintainer's vehicle |
| CarPlay, shared Wi-Fi | Existing Wi-Fi network | **No iPhone companion** | Advanced wireless network configuration; not a USB requirement |

Check the source revision and release notes for your installed build. USB hot-plug handoff is implemented, but current vehicle validation is still pending.

## What OAL provides

### Shared application

- One car app for Android Auto and CarPlay.
- Draggable Settings/Stats controls. Audio/call preferences save automatically; connection, video and display changes use **Save changes**. Wireless reconnects automatically when safe. For an actual USB connection, saving persists the next-session settings without stopping projection; unplug and reconnect physically to apply.
- Open the vehicle’s media view with **Settings → Audio → Both → Open OpenAutoLink in vehicle Audio**.
- Configurable display layout, safe areas and video sizing for wide AAOS screens.
- Built-in diagnostics, optional log upload and network/vehicle inspection tools.
- Separate Android Auto and CarPlay settings; one phone projects at a time.
- Temporary USB hot-plug: when a phone arrives during active or connecting projection, choose **Switch** or **Stay here** without changing either protocol's saved connection mode.

### Android Auto

- **Wireless WPP and direct USB**, without additional projection hardware.
- **EV data in Google Maps**: vehicle battery, range, charge-port data and a configurable energy model. See [EV range estimates](docs/ev-range.md).
- **Instrument-cluster navigation and media metadata** on supported vehicles. Navigation-app support varies.
- **Phone calls**, microphone, steering-wheel controls and ignition reconnects.
- **H.264, H.265 and VP9 video**, with manual 1440p (2560×1440) and 4K (3840×2160) tiers and portrait equivalents, subject to phone/decoder support.
- **Automatic video sizing prefers 1440p** where supported; 4K can be selected manually.

### CarPlay

- No iPhone companion app or additional projection hardware.
- **USB projection**, with wireless connection retained separately for development.
- **USB CarPlay music, navigation and touch controls**. Leave the iPhone unpaired from car Bluetooth for the recommended setup.
- **Car microphone for calls and button-initiated Siri**, enabled in normal operation.
- **Native incoming-call display and steering-wheel Answer/Reject/End** on supported vehicles after one-time [call setup](docs/phone-calls.md).
- **Multi-touch gestures**, including map pinch-to-zoom.
- **Adjustable video quality, Fit to window and safe-control calibration** for wide or curved screens.
- **Music information and artwork on supported vehicle displays**. Cluster route guidance is still being refined.
- Night-mode and vehicle-data integration.

## Installation

### Car app on a locked GM vehicle

1. [Create your AAB on the website](docs/build-aab.md#create-your-aab) and save the required encrypted identity backup.
2. Follow the [Internal Test installation guide](docs/install-gm.md): register with Google, create a free app, select **dedicated Android Automotive OS → Internal testing**, upload, enroll the car's Google account, and select the **car** in Google's web install picker.

Check Google's **US$25 one-time registration fee and Android-device verification requirement for new personal accounts before paying**, especially if you only own an iPhone. Check that your car is eligible for installation before uploading.

For CarPlay, include your own [authorized authentication pair](docs/carplay-private-mfi-assets.md). For updates, restore your latest web backup, keep the **same application ID and upload key**, and upload a higher-version AAB to the same Internal Test. [Desktop builder documentation](docs/desktop-builder.md) remains available, but desktop downloads are not published yet.

The car itself needs internet-connected Wi-Fi or its data service; [an OnStar data plan is not required](#car-internet-no-onstar-data-plan-required).

The car APK on [GitHub Releases](https://github.com/mossyhub/openautolink/releases/latest) is for AAOS systems/emulators that permit APK installation. **It cannot be sideloaded onto a locked GM production head unit.**

### Car internet: no OnStar data plan required

**You do not need an OnStar data subscription to install or use OAL.** Without a vehicle data plan, connect **the car itself** to a Wi-Fi network with working internet, such as your home Wi-Fi, to download OAL through Google Play. A local car hotspot used for wireless Android Auto is not, by itself, an internet connection for the car.

**Keep your car's apps updated—not just OAL.** Like your phone, the car has a Play Store and apps that receive regular updates. Without an OnStar data plan, give **the car itself** internet access regularly:

- **At home:** save your home Wi-Fi in the car's network settings. When parked within range, leave the infotainment system on for a while so Google Play has time to download app updates.
- **Away from home:** connect the car to your phone's Wi-Fi hotspot **at least once a week, or more often**. I turn on my phone's hotspot on most drives to give the car frequent opportunities to stay up to date. Hotspot use draws on your phone's data allowance.

Enable automatic app updates in the car's Play Store and check for pending updates occasionally. Downloads depend on Play Store settings, connection quality and vehicle software; simply saving a Wi-Fi network does not guarantee updates while the car is off. This is routine care for all the car's Play Store apps, not an OAL-only requirement.

OAL's phone-projection connection does not require a paid OnStar data plan. Online navigation, music and other phone-app services still need their own internet connection, normally your phone's mobile data.

### Android phone

For wireless Android Auto, install `openautolink-companion-vX.Y.Z.apk` from the matching [release](https://github.com/mossyhub/openautolink/releases/latest), grant its requested permissions, and configure the paired car. USB Android Auto does not need the companion.

### iPhone

No OAL iPhone companion is required. Use a data-capable USB cable and your own [authorized authentication pair](docs/carplay-private-mfi-assets.md), then follow [CarPlay USB setup](#usb-1).

### Other AAOS systems

Install the car APK only if the platform permits it. Bluetooth, networking, audio and vehicle integration vary by platform. Reports from Polestar and other non-GM AAOS vehicles without built-in phone projection are welcome.

## Android Auto setup

### Current wireless path: WPP

For Android Auto 17.4 or newer:

1. Install matching current car and companion releases; enable the car's Wi-Fi access point.
2. In **OAL → Settings → Connection → Android Auto connection**, select **Wireless** (WPP).
3. To avoid typing the AP name/BSSID, select the paired car under companion **Auto-Start → Select Devices**, tap **Start WPP config listener** in the car's WPP settings, then use companion **Send WPP WiFi to Car → Scan & Send to Car** and tap the car hotspot. [Full walkthrough and troubleshooting](docs/wireless-wpp.md#send-ssidbssid-from-the-phone-instead-of-typing-them). **Only SSID/BSSID are sent**: enter the hotspot password in the car app, verify the interface serving that AP, then tap **Save changes**. Wireless reconnects automatically; if using USB, unplug and reconnect the phone. Manual SSID/BSSID entry is also available.
4. For the WPP migration, pair the phone to the car again after updating. Keep Bluetooth **Phone calls** enabled; **Media audio** may be disabled.
5. Remove any old **Car WiFi** entry in the companion so it does not compete with Android Auto for the radio.
6. Select the car under companion **Auto-Start → Select Devices**, then tap **Save changes** for automatic wireless reconnection.

Bluetooth pairing bootstraps wireless projection; Bluetooth **Phone calls** also carries cellular call audio, a separate role. The **Audio → Android Auto → Assistant microphone** choice does not change call routing.

**The companion is required for wireless Android Auto.** GM chooses which nearby paired phone connects first. See the [WPP guide](docs/wireless-wpp.md) for detailed setup and migration instructions.

### USB

1. Open **Settings → Connection → Android Auto connection**, select **USB** and tap **Save changes**.
2. Pair the Android phone with the car and keep Bluetooth **Phone calls** enabled. Calls use the car’s Bluetooth hands-free system and microphone, even with USB projection.
3. Connect a data-capable USB cable and [accept both GM USB prompts](#gm-usb-prompts--android-auto-and-carplay). No companion is needed.
4. In **Settings → Audio → Microphone → Android Auto**, leave **Assistant microphone → Car** to use the car microphone for the assistant. **Phone** changes the assistant only, not calls.

You can also leave Android Auto set to Wireless and use the temporary USB switch below.

### GM USB prompts — Android Auto and CarPlay

**Every USB connection shows two system access prompts, even if you check “Always open OpenAutoLink” or save the choice. Tap OK on both prompts each time.** This applies to both **USB Android Auto** and **USB CarPlay** on GM head units. It is a longstanding GM infotainment bug; **OAL cannot fix or bypass it**.

<p align="center">
  <img src="docs/screenshots/GM-USB-Access-Prompt.jpg" alt="GM USB access dialog asking to allow OpenAutoLink to access an iPhone, with Always open OpenAutoLink already checked and OK and Cancel buttons" width="720">
  <br><em>Example of one GM USB access prompt, shown for an iPhone with “Always open OpenAutoLink” already checked. Expect two prompts on every USB connection; USB Android Auto has the same limitation.</em>
</p>

These GM USB access prompts are separate from OAL's **Switch / Stay here** choice, the iPhone's **Trust** prompt and CarPlay's local VPN consent. This warning is for GM wired connections, not wireless projection.

### Temporary USB switch between phones

For example, keep **Android Auto connection → Wireless** and **CarPlay connection → USB**. Both phone protocols are available in the same app; using CarPlay does not disable wireless Android Auto or change its saved mode.

- Plug in an Android phone or iPhone while projection is connected or connecting. OAL offers **Switch / Stay here** before starting USB setup or stopping the current projection.
- **Stay here** (or dismissing the prompt) leaves the current projection alone. Replug the phone to offer it again.
- **Switch** attempts to stop the current receiver, then starts the attached phone over USB. Accept the applicable system permission/Trust prompts.
- The selection lasts for that cable connection only. Unplugging returns OAL to its saved normal connection policy; it does not rewrite either mode. Wireless reconnection still depends on the phone and vehicle.
- If stopping the old projection fails, use **Retry USB** or **Dismiss**. Dismiss hides the guidance; it does not bypass an incomplete shutdown.

This handoff is implemented for both phone protocols; successful end-to-end switching in the vehicle is still being validated.

### Android Auto video quality

If text or maps look soft, try **1440p** as the recommended manual quality setting: in **OAL → Settings → Video → Android Auto**, turn **Auto (Recommended)** off so it reads **Manual**, select **Video Codec → H.265 / HEVC**, then **AA Resolution → 1440p (2560×1440)** and tap **Save changes**, then reconnect your phone (wireless reconnects automatically; for USB, unplug and reconnect). Keep **GAL / PDK protocol version → 6.0** for the verified modern HEVC path.

**4K (3840×2160)** is an optional higher-detail setting if your phone and head-unit decoder support it. It costs more bandwidth and processing; return to 1440p if it stutters or fails to connect. Auto chooses a supported resolution. These codec controls apply to Android Auto, not CarPlay.

## CarPlay setup

Keep the vehicle parked while configuring. Disconnect the iPhone from any other OAL receiver before connecting it to the car.

### USB

1. Install OAL with your own [authorized authentication files](docs/carplay-private-mfi-assets.md).
2. **Unpair the iPhone from the car and leave car Bluetooth disconnected.** USB CarPlay does not need Bluetooth pairing or car Wi-Fi settings.
3. In **Settings → Connection → CarPlay**, select **CarPlay connection → USB** and tap **Save changes**. Unplug first if already connected, then connect a data-capable USB cable.
4. [Tap **OK** on both GM USB prompts](#gm-usb-prompts--android-auto-and-carplay) on every connection. Accept OAL’s local VPN consent when requested and approve **Trust** on the unlocked iPhone if asked. Another active VPN can conflict with USB CarPlay.
5. Select **OpenAutoLink** in the vehicle’s **Audio** app for music and navigation.
6. In **Settings → Audio → CarPlay calls**, leave **Call handling → CarPlay** (Recommended) and **Extra car ringtone → Off**.
7. Tap **Set up calls**, allow Android’s phone permission, turn on **OpenAutoLink** in **Calling accounts**, then return to OAL. **Setup → Ready** updates automatically. This is a one-time setup; no Save or Inspect step is needed.
8. Allow microphone access for calls and button-initiated Siri. If Audio shows **Enable microphone**, tap it. If it then asks to reconnect, reconnect USB after the call ends. Test a call and Siri while parked.

> [!WARNING]
> **Bluetooth can cause repeated media pauses, even with Media audio off.** Keep the iPhone unpaired from car Bluetooth when using the recommended CarPlay handler. Do not use OAL and Bluetooth as simultaneous native call providers.

**Already set up?** Your call-handler choice and Android approval are retained across reconnects. A handler change during a call applies to the next call without ending the current one. There is no diagnostic Arm or Apply step for normal calls or Siri.

**Need to keep the iPhone paired?** Select **Call handling → Car Bluetooth** and follow the [Bluetooth alternative](docs/phone-calls.md#alternative--car-bluetooth-calls), including **Phone calls ON / Media audio OFF** and reconnecting the pairing. Bluetooth calls do not require OAL calling-account approval; repeated media pauses can still occur.

OAL remembers trust for multiple iPhones, but only one phone projects at a time. Connect the phone you want and approve its own prompts. If you previously disabled **Enable iPhone projection**, turn it on again and save.

### Wireless CarPlay

Wireless CarPlay is still in development and is not working in the maintainer's vehicle. Use USB for now. Advanced wireless configuration is covered in the [network guide](docs/carplay-network-contract.md).

### CarPlay UI restrictions (optional)

While parked, open **Settings → Connection → Normal connections → CarPlay → CarPlay UI restrictions**.
**Vehicle-based UI restrictions** is saved independently of Android Auto and defaults **OFF**.
Enable it to declare keyboard/list restrictions and allow the main-screen UI only with fresh,
valid vehicle Park information. Missing/stale gear remains restricted. This policy is not yet
validated on every vehicle, so ordinary builds do not enable it automatically.

**Always in Park** is a separate CarPlay override, also default **OFF**, available when restriction
control is enabled. It releases main-screen keyboard/list restrictions even without Park information;
use it only while safely parked. It does not change actual gear, Android Auto settings, secondary
displays or video permissions. Tap **Save changes**, then reconnect your phone to apply either choice to a fresh session. Wireless reconnects automatically; for USB, unplug and reconnect.
No special build flag is needed. See the [policy and validation limits](docs/carplay-driving-restriction-policy.md).

### Video and display controls

- New CarPlay settings default to **Automatic** resolution targeting a decoder-qualified **1440p-class** image and **Codec → Automatic (H.264 + qualified HEVC)**, with qualified lower-detail fallbacks. Saved video choices are retained. If you choose manual settings, open **OAL → Settings → Video → CarPlay**, select **Manual** under **Resolution / detail**, then choose **1440p** (recommended) or try **4K** if supported. **Fit to window (standard detail tiers)** keeps the selected detail tier at the available window aspect without stretching; custom resolutions retain their chosen aspect ratio. Tap **Save changes**, then reconnect your phone. Wireless reconnects automatically; for USB, unplug and reconnect.
- **Codec → Automatic (H.264 + qualified HEVC)** is the default for new CarPlay settings. If you choose manual video settings, we recommend this codec setting with **1440p** for the best balance of detail and performance. It offers HEVC only when the exact size/rate qualifies and retains H.264; it does **not** force the iPhone to use HEVC or change your resolution. Existing saved H.264 choices are preserved, so users with saved H.264 settings should select Automatic explicitly. If connection fails, select **H.264**, tap **Save changes**, then reconnect your phone (for USB, unplug and reconnect; wireless reconnects automatically). 4K adds bandwidth and processing demand; return to 1440p if it stutters.
- **For CarPlay on curved screens, we recommend Display → System UI Visible.** OAL starts in fullscreen mode by default, but CarPlay controls can be clipped by the physical screen curves in fullscreen.
- In **Display → Calibrate safe controls**, adjust the safe rectangle to inset CarPlay controls as far from the edges as you prefer. This is manual adjustment, not automatic curve detection; without suitable insets, fullscreen can cut off parts of the UI. Confirm the calibration, tap **Save changes**, then reconnect your phone (for USB, unplug and reconnect; wireless reconnects automatically).
- If you prefer fullscreen, **swipe down from the top edge of the screen to reveal the GM navigation bar** when you need the vehicle's system controls. This does not replace safe-control calibration.
- Available resolutions and frame rates depend on the phone and head unit. A frame-rate setting is a limit, not a guarantee. Safe-area calibration and system-bars-visible layouts still need broader vehicle testing.

### Authentication and source builds

Public GitHub APKs and ordinary source builds omit the CarPlay authentication files and require your own compatible, authorized pair. OAL is **not an Apple-certified CarPlay product**. Bundled files are extractable; never publish authentication files or signing keys. See [CarPlay authentication](docs/carplay-private-mfi-assets.md) for the exact file formats and authorization limits.

## Audio and call settings

Open **Settings → Audio**. Audio/call choices save automatically, separately from Connection, Video and Display changes.

| Setting | What it changes |
|---|---|
| **Microphone → Android Auto → Assistant microphone → Car / Phone** | Assistant input only. Car is the default; saved Phone choices are kept. Applies to the next microphone stream. Calls use Car Bluetooth. |
| **Microphone → CarPlay → Calls and Siri → Car microphone** | Normal CarPlay microphone input. Allow microphone access if prompted. |
| **CarPlay calls → Call handling → CarPlay / Car Bluetooth** | Saved native-call handler. CarPlay is recommended with the iPhone unpaired from car Bluetooth. |
| **CarPlay calls → Extra car ringtone** | Adds a vehicle ringtone alongside CarPlay. Off by default. |
| **Android Auto · Volume offsets** (**Media**, **Navigation**, **Assistant / Speech**) | Separate Android Auto volume adjustments; 0 is the default. Applies at the next Android Auto connection, not to CarPlay. |

**Android call settings** and **Android sound settings** are optional system shortcuts, not extra setup steps. See [calls and microphones](docs/phone-calls.md) for call setup and help.

<img src="docs/screenshots/Audio-Settings-Landscape.png" alt="Audio settings showing Android Auto and CarPlay microphone sections" width="720">

### Play phone media from vehicle Audio

1. Connect Android Auto or CarPlay.
2. In the vehicle’s **Audio** app, select **OpenAutoLink**. The shortcut **Settings → Audio → Both → Open OpenAutoLink in vehicle Audio** opens that view on supported cars; otherwise open Audio manually.
3. If a browse view appears, choose **Phone audio → Connected phone**. Choose music apps, playlists and tracks in Android Auto or CarPlay.
4. Use the vehicle’s Play/Pause and track controls or the projected music app. Selecting the source may start playback automatically.

**No phone connected?** Connect a phone normally, then reopen the source if needed. Opening Audio does not start projection. Selecting a media source does not change call handling.

**Returning from FM or another source?** Select OpenAutoLink and press the vehicle’s normal **Play** control if needed. There is no separate Resume audio button; automatic resume varies by vehicle. See [audio troubleshooting](docs/troubleshooting.md#carplay-is-silent-after-returning-from-fm-or-another-source).

**No sound?** Check that OpenAutoLink is selected and raise the vehicle’s media volume. For repeated USB CarPlay pauses, disconnect car Bluetooth. Leave diagnostic recovery and microphone overrides off for normal use.

### What Save changes does

Connection, Video and Display changes are saved together before any reconnect action. On an **actual USB connection**, saving leaves the current projection running and shows **Saved. Unplug and reconnect USB to apply.** Physically unplug/reconnect to negotiate the saved changes; saving alone does not restart the session. Temporary USB connections follow the same rule even if the saved normal mode is Wireless. Wireless reconnects automatically when safe; during a call, OAL instead shows **Saved. Reconnect after the call to apply.** Audio/call choices remain independently autosaved.

## Adjust text, icons and UI size

Configure these while parked in **OAL → Settings → Display**. Android Auto and CarPlay have separate scaling controls; changing one does not change the other. Tap **Save changes** after adjusting them, then reconnect your phone to apply the settings to the next projection session. Wireless reconnects automatically; for USB, unplug and reconnect.

### Android Auto — DPI scaling

Under **Android Auto → AA Display Density (DPI)**:

- Leave **Auto DPI** on for sizing based on the available projection area and the head unit's display density. It accounts for fullscreen versus system-UI-visible layouts and the negotiated video size.
- For manual control, turn **Auto DPI** off. If **Layout Width Target** is shown, select **Off** too so it does not override the DPI slider.
- Adjust **DPI**: **lower DPI** gives smaller text/icons and more content; **higher DPI** gives larger text/icons and less content at the same video resolution. DPI can also change Android Auto's card and panel layout.
- With automatic video negotiation, **Layout Width Target** offers **Compact**, **Normal** and **Wide** presets to keep a consistent logical layout width across resolution tiers. This is an alternative to choosing a fixed manual DPI.

### CarPlay — UI scaling

Under **CarPlay → CarPlay UI scale**:

- Leave **Auto sizing** on to estimate physical size from the next session's projection area and available display measurements. When measurements are unavailable or unreliable, Auto uses the saved reference calibration and viewport fit instead; this fallback is not a measured panel size.
- Turn **Auto sizing** off to unlock **Manual UI size**. Select **Show exact calibration** to enter an integer in **UI calibration (mm)**; exact entry starts collapsed and shares the same pending setting as the slider.
- Move the slider **left toward Smaller UI** to request smaller text/icons and more content, or **right toward Larger UI** to request larger text/icons and less content. The iPhone chooses the final layout and may apply steps or limits; check actual control size while parked.
- **Manual calibration** spans **100–400 mm**. A wider advertised physical width requests smaller UI; a smaller width requests larger UI. This is CarPlay calibration metadata, **not Android Auto DPI** or video resolution. The extended range introduced in 0.1.551 was reverted after black-screen reports; saved values outside this range return to the 200 mm baseline. Valid saved calibrations are preserved.

- **Last frozen offer** shows **Offered locally** or **Ended**, its basis and offered millimeters, separately from the **Next session** draft. Check the resulting layout on the iPhone’s next connection.
- Under **CarPlay status bar**, choose **Automatic**, **Bottom** or **Driver side** for the next connection. Placement does not change text/icon density.
- Use **Save changes**. Wireless reconnects automatically when safe; USB requires unplugging and reconnecting.

**UI scale is not video resolution.** Use **Video** settings for image detail and **Fit to window** for CarPlay's standard-tier canvas fit. Use **Display → Calibrate safe controls** to move CarPlay controls away from curved screen edges; changing UI size does not replace safe-area calibration. Neither protocol's scaling control changes the vehicle's native apps or OAL's own settings UI.

## Keep incoming and active calls in Android Auto or CarPlay on GM

To keep GM’s native Phone app from covering projection, open **Phone → Settings** in the vehicle:

1. Turn **Active Call OFF** (“Show active call view when answering call”).
2. Turn **Privacy ON** (“Only show call alerts in cluster”).

These change the call display, not the audio route. Android Auto still needs Bluetooth **Phone calls** enabled. Recommended USB CarPlay uses **Call handling → CarPlay** with the iPhone unpaired from car Bluetooth and one-time OAL call approval.

- The cluster may show **Unknown** or a phone number even when CarPlay shows the contact’s name.
- Native controls vary by vehicle; outgoing, held and conference-call integration is not supported by OAL’s native CarPlay call integration.

See the [call guide](docs/phone-calls.md) for setup and help.

## Vehicle compatibility

Android Auto community reports are linked below. Compatibility varies with vehicle software, phone and connection mode.

| Vehicle | AAOS | Android Auto reports |
|---|---:|---|
| 2024 Chevrolet Blazer EV | 12L | Maintainer daily driver |
| 2025–2026 Chevrolet Equinox EV | 14 reported | [Successful owner report](https://xdaforums.com/t/open-source-openautolink-wireless-android-auto-bridge-for-aaos-gm-evs.4785192/post-90708465), alongside earlier build/setup issue reports |
| 2027 Chevrolet Bolt | — | [Independent demonstration](https://www.youtube.com/watch?v=xEYOxJroplQ) and [owner report](https://www.chevybolt.org/threads/openautolink-the-cheapest-way-to-get-andriod-auto-on-the-27-bolt.63027/) |
| Other GM AAOS EVs | Varies | Additional owner reports welcome |
| Non-GM AAOS | Varies | Additional owner reports welcome; platform integration varies |

See [compatibility notes](docs/compatibility.md) for vehicle-specific information.

## Demonstrations

### Apple CarPlay

CarPlay Dashboard combines navigation and playback controls on the Blazer EV's wide display.

<p align="center">
  <img src="docs/screenshots/CarPlay-Dashboard-Fullscreen.jpg" alt="Apple CarPlay Dashboard in fullscreen mode on a Chevrolet Blazer EV" width="720">
  <br><em>Fullscreen CarPlay, with the vehicle's top system bar hidden.</em>
</p>

<p align="center">
  <img src="docs/screenshots/CarPlay-Dashboard-System-UI.jpg" alt="Apple CarPlay Dashboard with the Chevrolet Blazer EV's top system bar visible" width="720">
  <br><em>CarPlay with the vehicle's top system bar visible. Climate controls remain accessible below in both views.</em>
</p>

### Android Auto

<p align="center">
  <img src="docs/screenshots/AA-Streaming-Screenshot.jpg" alt="Android Auto streaming wirelessly on a 2024 Chevrolet Blazer EV" width="720">
  <br><em>Android Auto on the Blazer EV.</em>
</p>

<p align="center">
  <img src="docs/screenshots/AA-EV-Battery-Maps.jpg" alt="Google Maps showing vehicle battery data through OpenAutoLink" width="720">
  <br><em>Real vehicle battery data forwarded into Android Auto's Google Maps.</em>
</p>

[![OpenAutoLink walkthrough](https://img.youtube.com/vi/2KcsTZalXcc/0.jpg)](https://youtu.be/2KcsTZalXcc)

The walkthrough's original wireless setup predates Android Auto 17.4. Use the WPP guide, not the video's historical connection instructions.

[![Android Auto on a 2027 Chevrolet Bolt](https://img.youtube.com/vi/xEYOxJroplQ/0.jpg)](https://www.youtube.com/watch?v=xEYOxJroplQ)

## Microphone permission and privacy

**Why does OAL ask to “record audio”?** Android uses that permission wording for any microphone access—even live passthrough. OAL needs it to pass the car's microphone to your phone through CarPlay or Android Auto for supported voice features.

**OAL does not save microphone recordings or upload microphone audio to the developer or any external server.** Audio is passed live to your connected phone, not kept as a recording. Your phone handles voice services under its own privacy settings.

Optional diagnostic-log uploads and EV-profile downloads are separate; see the [privacy policy](docs/privacy-policy.md).

## Diagnostics and useful test reports

For a silent car microphone, first check [call setup and microphone permission](docs/phone-calls.md). If the problem continues, **Diagnostics → Microphone** offers parked local capture tests and retains up to 12 numeric comparisons for manual log upload. It never saves microphone audio. These advanced tests are optional, not part of normal setup. See the [microphone diagnostic guide](docs/microphone-diagnostic.md).

**Diagnostics → Media integration** offers read-only media comparisons and an explicitly chosen local **Save media-host APK ZIP** export. The latter saves only the fixed media host’s public APK files, not private app data; it is unencrypted and may contain vendor IP, grants no redistribution rights, and is never included in log uploads. Bounded media-history receipts can remain available for authorized manual log ZIPs even with File Logging Off.

Enable OAL file logging **before** reproducing a problem, then use the configured log-upload control. Each app uploads **only its own logs**: the car upload does not collect companion or iPhone logs. Consecutive uploads can contain overlapping sessions.

Include:

- Vehicle/model year, AAOS version and exact OAL build.
- Phone model, Android Auto or iOS version, and the chosen protocol/transport.
- Approximate attempt time, configuration changes and what appeared on both screens.
- Whether Wi-Fi joined, projection rendered, touch worked and audio was audible—these are separate outcomes.
- For cluster issues, the navigation app and what appeared on the cluster/media display.

Never post passwords, accessory credentials, upload invitation tokens or account emails. Logs/screenshots may contain private identifiers, location or media; use the configured private upload route rather than a public raw dump. See [Troubleshooting](docs/troubleshooting.md) and [Privacy policy](docs/privacy-policy.md).

## Documentation and development

User guides:

- [Create your AAB on the website](docs/build-aab.md) and [Internal Test installation on GM](docs/install-gm.md)
- [Desktop AAB builder preview](docs/desktop-builder.md) (downloads not yet available)
- [Wireless Android Auto](docs/wireless-wpp.md)
- [Compatibility](docs/compatibility.md), [EV range estimates](docs/ev-range.md) and [phone calls](docs/phone-calls.md)
- [Troubleshooting](docs/troubleshooting.md), [CarPlay source-return recovery](docs/carplay-source-return-recovery.md) and [privacy policy](docs/privacy-policy.md)

Developer guides:

- [Architecture](docs/architecture.md)
- [Advanced Docker build fallback](docs/manual-build.md)
- [CarPlay authentication/build policy](docs/carplay-private-mfi-assets.md)
- [Connection modes and USB switching](docs/usb-hotplug-independent-protocol-modes.md)
- [CarPlay networking](docs/carplay-network-contract.md), [display sizing](docs/carplay-window-safe-area-contract.md) and [cluster integration](docs/carplay-cluster-metadata-handoff.md)

## Contributing and support

Use [GitHub issues](https://github.com/mossyhub/openautolink/issues) for reproducible defects and [XDA](https://xdaforums.com/t/open-source-openautolink-wireless-android-auto-bridge-for-aaos-gm-evs.4785192/) for setup and owner reports. Include exact builds, reproduction steps and logs.

Reports from Polestar and other non-GM AAOS vehicles are especially welcome, from both Android and iPhone owners. Follow the [build and installation guidance](#car-app-on-a-locked-gm-vehicle). Public GitHub builds omit private CarPlay authentication assets; see the build policy before choosing that installation route.

## Acknowledgments

- Adam Harlow — native GM Audio discovery/browsing and background-media work that informed this integration.
- [opencardev/aasdk](https://github.com/opencardev/aasdk) — Android Auto protocol library; the OAL fork adds navigation and EV energy-model extensions.
- [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay) — CarPlay receiver source/reference adapted for OAL; upstream credits shilapi/xcertplay. See retained source notices and the private authentication policy.
- [metheos/carlink_native](https://github.com/metheos/carlink_native), [lvalen91/Carlink](https://github.com/lvalen91/Carlink), and the [XDA Carlink thread](https://xdaforums.com/t/carlink.4774308/) — proof-of-concept inspiration and technical collaboration.
- [opencardev/openauto](https://github.com/opencardev/openauto) — head-unit architecture reference.
- [nisargjhaveri/WirelessAndroidAutoDongle](https://github.com/nisargjhaveri/WirelessAndroidAutoDongle) — Bluetooth/Wi-Fi bootstrap reference.
- [andreknieriem/open-headunit](https://github.com/andreknieriem/open-headunit) — receiver/protocol reference.

## License

OpenAutoLink is licensed under the [GNU General Public License v3.0](LICENSE). Retain the attribution and license notices for included/adapted components. Source availability does not confer rights to redistribute private accessory authentication assets.
