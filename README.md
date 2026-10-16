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
> The **desktop builder** workflow generates your package ID and upload key, manages native tools in one folder, and builds a signed AAB without source edits or a host Git/Java/Docker installation. **Desktop downloads are not published yet; packaging is a preview.**
>
> CarPlay also requires your own compatible authentication pair that you are authorized to include; the builder does not supply it. Keep your builder identity and encrypted backup for later updates.
>
> [Desktop build guide](docs/build-aab.md) · [Internal Test installation](docs/install-gm.md) · [CarPlay authentication](docs/carplay-private-mfi-assets.md)

## Choose your phone and connection

| Phone / mode | Connection | Companion needed? | Notes |
|---|---|---|---|
| Android Auto, wireless | Bluetooth + car Wi-Fi (WPP) | **Yes**, for GM wireless | Use WPP for Android Auto 17.4+ |
| Android Auto, USB | USB cable | **No** | GM shows [two USB access prompts on every connection](#gm-usb-prompts--android-auto-and-carplay), even with the choice saved |
| CarPlay, USB | USB cable; iPhone unpaired from vehicle Bluetooth | **No iPhone companion** | Music and calls use USB; authentication identity and USB/VPN consent required; [two GM USB access prompts on every connection](#gm-usb-prompts--android-auto-and-carplay) |
| CarPlay, wireless | Bluetooth + Wi-Fi | **No iPhone companion** | Available for development; not working in the maintainer's vehicle |
| CarPlay, shared Wi-Fi | Existing Wi-Fi network | **No iPhone companion** | Advanced wireless network configuration; not a USB requirement |

Check the source revision and release notes for your installed build. USB hot-plug handoff is implemented, but current vehicle validation is still pending.

## What OAL provides

### Shared application

- One car app for Android Auto and CarPlay.
- Draggable Settings/Stats controls and **Save & Reconnect**.
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
- **Video, touch, music and phone calls over USB**. Incoming and outgoing calls were confirmed on the maintainer's Blazer EV without vehicle Bluetooth. [Leave the iPhone unpaired from vehicle Bluetooth](#usb-1) to avoid repeated music pauses.
- **Multi-touch gestures**, including map pinch-to-zoom.
- **Adjustable video quality, Fit to window and safe-control calibration** for wide or curved screens.
- **Music information and artwork on supported vehicle displays**. Cluster route guidance is still being refined.
- Night-mode and vehicle-data integration.

## Installation

### Car app on a locked GM vehicle

Follow the [desktop build guide](docs/build-aab.md) to create a personal signed AAB, then the [Internal Test installation guide](docs/install-gm.md) to install it through your own Google Play Console account. **Desktop packages are a preview; release downloads are not yet published.** The [advanced Docker fallback](docs/manual-build.md) remains separate.

The installation guide covers developer registration (including Google's one-time fee and Android-device verification requirement for new personal accounts), creating a free app, selecting **dedicated Android Automotive OS → Internal testing**, enrolling the car's Google account, and choosing the **car** in Google's web install picker. An Install button alone does not prove the car is eligible.

For CarPlay, select your own [authorized authentication pair](docs/carplay-private-mfi-assets.md) in the builder; it does not generate those files. Preserve the managed folder and an encrypted identity backup. For updates, use the same builder identity to build the current stable release and upload the new AAB to the same Internal Test—no source editing or manual source pull is needed.

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

No OAL iPhone companion is required. For **USB**, connect a data-capable cable and **leave the iPhone unpaired from vehicle Bluetooth** to avoid GM Bluetooth competing with CarPlay and constantly pausing media. Music and calls work over USB; car Wi-Fi fields are not required for wired startup. For **wireless**, pair through the **car's system Bluetooth settings** and configure the wireless fields below. USB Trust approval is separate from Bluetooth pairing. Supply the [required CarPlay authentication files](docs/carplay-private-mfi-assets.md) separately.

### Other AAOS systems

Install the car APK only if the platform permits it. Bluetooth, networking, audio and vehicle integration vary by platform. Reports from Polestar and other non-GM AAOS vehicles without built-in phone projection are welcome.

## Android Auto setup

### Current wireless path: WPP

For Android Auto 17.4 or newer:

1. Install matching current car and companion releases; enable the car's Wi-Fi access point.
2. In **OAL → Settings → Connection → Android Auto connection**, select **Wireless** (WPP).
3. To avoid typing the AP name/BSSID, select the paired car under companion **Auto-Start → Select Devices**, tap **Start WPP config listener** in the car's WPP settings, then use companion **Send WPP WiFi to Car → Scan & Send to Car** and tap the car hotspot. [Full walkthrough and troubleshooting](docs/wireless-wpp.md#send-ssidbssid-from-the-phone-instead-of-typing-them). **Only SSID/BSSID are sent**: enter the hotspot password in the car app, verify the interface serving that AP, then tap **Save & Reconnect**. Manual SSID/BSSID entry is also available.
4. For the WPP migration, pair the phone to the car again after updating. Keep Bluetooth **Phone calls** enabled; **Media audio** may be disabled.
5. Remove any old **Car WiFi** entry in the companion so it does not compete with Android Auto for the radio.
6. Select the car under companion **Auto-Start → Select Devices**, then save and reconnect.

**The companion is required for wireless Android Auto.** GM chooses which nearby paired phone connects first. See the [WPP guide](docs/wireless-wpp.md) for detailed setup and migration instructions.

### USB

Select **USB** under **Android Auto connection** for normal wired use, then connect a data-capable cable. No companion is needed. [Accept both GM USB access prompts every time](#gm-usb-prompts--android-auto-and-carplay), even with the choice saved. You can also leave normal AA mode set to Wireless and use the temporary USB switch below.

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

If text or maps look soft, try **1440p** as the recommended manual quality setting: in **OAL → Settings → Video → Android Auto**, turn **Auto (Recommended)** off so it reads **Manual**, select **Video Codec → H.265 / HEVC**, then **AA Resolution → 1440p (2560×1440)** and tap **Save & Reconnect**. Keep **GAL / PDK protocol version → 6.0** for the verified modern HEVC path.

**4K (3840×2160)** is an optional higher-detail setting if your phone and head-unit decoder support it. It costs more bandwidth and processing; return to 1440p if it stutters or fails to connect. Auto does not guarantee a particular negotiated resolution, and a blurry image alone does not prove the stream is 1080p. These codec controls apply to Android Auto, not CarPlay.

## CarPlay setup

Keep the vehicle parked while configuring. Disconnect the iPhone from any other OAL receiver before connecting it to the car.

### Common prerequisites

1. Install a current OAL build with a valid accessory authentication identity. Public GitHub APKs and source builds require your own compatible files. See the [authentication/build policy](docs/carplay-private-mfi-assets.md).
2. Use **USB** under **OAL → Settings → Connection → CarPlay → CarPlay connection** for wired projection. Wireless remains a development option; CarPlay and Android Auto connection modes are independent.
3. Apply any changes with **Save & Reconnect**. Keep file logging enabled for testing and upload results, including failed attempts.

If you previously turned off iPhone projection, OAL retains that choice. Only in that case, turn **Enable iPhone projection** back on in the same settings page and save. There is no separate enablement step for normal setup.

### USB

1. **Do not pair the iPhone to the vehicle's Bluetooth for USB CarPlay.** If it is already paired, forget/remove the pairing so it does not reconnect. With the iPhone connected to vehicle Bluetooth, GM's native Bluetooth playback control competes with CarPlay and music can constantly pause after pressing Play.
2. Select **CarPlay connection → USB** and save.
3. Connect the iPhone to a data-capable head-unit USB port with a data-capable cable. If another projection is active, choose **Switch** to attempt temporary USB takeover.
4. [Tap **OK** on both GM USB access prompts every time](#gm-usb-prompts--android-auto-and-carplay), even if you saved the choice. Accept OAL's separate local VPN consent when requested. If the iPhone asks to trust the connection, unlock it and approve **Trust** on the iPhone itself.

**Music and calls work over USB without vehicle Bluetooth.** The maintainer confirmed music playback plus both outgoing and incoming CarPlay calls on the 2024 Chevrolet Blazer EV with the iPhone disconnected from vehicle Bluetooth. This is a setup correction, not a requirement to turn off the car's entire Bluetooth adapter. **Do not apply it to wireless Android Auto:** keep that Android phone's required Bluetooth pairing and **Phone calls** profile enabled.

USB CarPlay does not require Bluetooth pairing or Wi-Fi setup. The VPN permission is used locally for the USB connection—not an Internet VPN service. Another active VPN can conflict. USB access and permission support vary by head unit.

### Wireless CarPlay

Wireless CarPlay is still in development and is not working in the maintainer's vehicle. Use USB for now. Advanced wireless configuration is covered in the [network guide](docs/carplay-network-contract.md).

### Video and display

- New CarPlay settings default to **Automatic** resolution targeting a decoder-qualified **1440p-class** image and **Codec → Automatic (H.264 + qualified HEVC)**, with qualified lower-detail fallbacks. Saved video choices are retained. If you choose manual settings, open **OAL → Settings → Video → CarPlay**, select **Manual** under **Resolution / detail**, then choose **1440p** (recommended) or try **4K** if supported. **Fit to window (standard detail tiers)** keeps the selected detail tier at the available window aspect without stretching; custom resolutions retain their chosen aspect ratio. Tap **Save & Reconnect** to apply.
- **Codec → Automatic (H.264 + qualified HEVC)** is the default for new CarPlay settings. If you choose manual video settings, we recommend this codec setting with **1440p** for the best balance of detail and performance. It offers HEVC only when the exact size/rate qualifies and retains H.264; it does **not** force the iPhone to use HEVC or change your resolution. Existing saved H.264 choices are preserved, so users with saved H.264 settings should select Automatic explicitly. If connection fails, select **H.264** and **Save & Reconnect**. 4K adds bandwidth and processing demand; return to 1440p if it stutters. Blurry video alone does not establish the negotiated resolution.
- **For CarPlay on curved screens, we recommend Display → System UI Visible.** OAL starts in fullscreen mode by default, but CarPlay controls can be clipped by the physical screen curves in fullscreen.
- In **Display → Calibrate safe controls**, adjust the safe rectangle to inset CarPlay controls as far from the edges as you prefer. This is manual adjustment, not automatic curve detection; without suitable insets, fullscreen can cut off parts of the UI. Confirm the calibration, then **Save & Reconnect**.
- If you prefer fullscreen, **swipe down from the top edge of the screen to reveal the GM navigation bar** when you need the vehicle's system controls. This does not replace safe-control calibration.
- Available resolutions and frame rates depend on the phone and head unit. A frame-rate setting is a limit, not a guarantee. Safe-area calibration and system-bars-visible layouts still need broader vehicle testing.

### Authentication and source builds

Public GitHub APKs and ordinary source builds omit the CarPlay authentication files and require your own compatible, authorized pair. OAL is **not an Apple-certified CarPlay product**. Bundled files are extractable; never publish authentication files or signing keys. See [CarPlay authentication](docs/carplay-private-mfi-assets.md) for the exact file formats and authorization limits.

## Adjust text, icons and UI size

Configure these while parked in **OAL → Settings → Display**. Android Auto and CarPlay have separate scaling controls; changing one does not change the other. Select **Save & Reconnect** after adjusting them to apply the settings to the next projection session.

### Android Auto — DPI scaling

Under **Android Auto → AA Display Density (DPI)**:

- Leave **Auto DPI** on for sizing based on the available projection area and the head unit's display density. It accounts for fullscreen versus system-UI-visible layouts and the negotiated video size.
- For manual control, turn **Auto DPI** off. If **Layout Width Target** is shown, select **Off** too so it does not override the DPI slider.
- Adjust **DPI**: **lower DPI** gives smaller text/icons and more content; **higher DPI** gives larger text/icons and less content at the same video resolution. DPI can also change Android Auto's card and panel layout.
- With automatic video negotiation, **Layout Width Target** offers **Compact**, **Normal** and **Wide** presets to keep a consistent logical layout width across resolution tiers. This is an alternative to choosing a fixed manual DPI.

### CarPlay — UI scaling

Under **CarPlay → CarPlay UI scale**:

- Leave **Auto sizing** on to estimate UI scale from the next session's projection area and available display measurements. If that estimate does not suit your screen, use manual sizing.
- Turn **Auto sizing** off to unlock **Manual UI size**.
- Move the slider **left toward Smaller UI** for smaller text/icons and more content, or **right toward Larger UI** for larger text/icons and less content.
- The physical-width value in the help text is CarPlay calibration metadata, **not Android Auto DPI**. Use the slider's Smaller/Larger labels rather than copying an AA DPI value.

**UI scale is not video resolution.** Use **Video** settings for image detail and **Fit to window** for CarPlay's standard-tier canvas fit. Use **Display → Calibrate safe controls** to move CarPlay controls away from curved screen edges; changing UI size does not replace safe-area calibration. Neither protocol's scaling control changes the vehicle's native apps or OAL's own settings UI.

## Keep incoming and active calls in Android Auto or CarPlay on GM

For **USB CarPlay**, leave the iPhone unpaired from vehicle Bluetooth: incoming and outgoing calls work over USB, and avoiding the Bluetooth connection prevents repeated music pauses. The native Bluetooth Phone-app settings below are not required for that Bluetooth-free CarPlay setup.

For **Android Auto**, or when managing native GM call-screen behavior, use these vehicle settings to stop GM's native Phone app from covering the projected incoming-call and in-call controls on the center display:

1. Open the vehicle's native **Phone** app.
2. Tap the **Settings** gear.
3. Turn **Active Call** **OFF** (“Show active call view when answering call”).
4. Turn **Privacy** **ON** (“Only show call alerts in cluster”).

Incoming-call alerts stay in the instrument cluster, and the native active-call screen no longer takes over the center display. Answer and manage calls from Android Auto or CarPlay instead.

These are **Phone-app display settings**, not Bluetooth profile settings. For **Android Auto**, leave the paired phone's Bluetooth **Phone calls** profile **enabled**: the vehicle's hands-free system carries microphone and speaker audio, and disabling it can also break wireless WPP startup. See [Phone calls](docs/phone-calls.md) for Android Auto audio routing, verification and troubleshooting.

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

- [Internal Test installation on GM](docs/install-gm.md) and [desktop AAB builder](docs/build-aab.md)
- [Wireless Android Auto](docs/wireless-wpp.md)
- [Compatibility](docs/compatibility.md), [EV range estimates](docs/ev-range.md) and [phone calls](docs/phone-calls.md)
- [Troubleshooting](docs/troubleshooting.md) and [privacy policy](docs/privacy-policy.md)

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

- [opencardev/aasdk](https://github.com/opencardev/aasdk) — Android Auto protocol library; the OAL fork adds navigation and EV energy-model extensions.
- [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay) — CarPlay receiver source/reference adapted for OAL; upstream credits shilapi/xcertplay. See retained source notices and the private authentication policy.
- [metheos/carlink_native](https://github.com/metheos/carlink_native), [lvalen91/Carlink](https://github.com/lvalen91/Carlink), and the [XDA Carlink thread](https://xdaforums.com/t/carlink.4774308/) — proof-of-concept inspiration and technical collaboration.
- [opencardev/openauto](https://github.com/opencardev/openauto) — head-unit architecture reference.
- [nisargjhaveri/WirelessAndroidAutoDongle](https://github.com/nisargjhaveri/WirelessAndroidAutoDongle) — Bluetooth/Wi-Fi bootstrap reference.
- [andreknieriem/open-headunit](https://github.com/andreknieriem/open-headunit) — receiver/protocol reference.

## License

OpenAutoLink is licensed under the [GNU General Public License v3.0](LICENSE). Retain the attribution and license notices for included/adapted components. Source availability does not confer rights to redistribute private accessory authentication assets.
