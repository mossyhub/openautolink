# Troubleshooting OpenAutoLink

Find the symptom below. After changing a setting, check the screen, touch controls and the feature you were trying to use.

## Record the exact environment

Before changing settings, record:

- vehicle, model year, AAOS version, and head-unit/radio information;
- phone and Android version;
- Android Auto version;
- OpenAutoLink car and companion versions;
- selected transport;
- whether this is a fresh install, upgrade, or ignition reconnect.

Change one variable at a time.

## The car app cannot be installed on a GM vehicle

A production GM head unit does not permit ADB or APK sideloading. The GitHub car APK is not an installation path for that hardware. Follow [GM installation](install-gm.md) and publish a signed AAB through Google Play testing.

## Wireless WPP does nothing

For Android Auto 17.4+ confirm:

1. Car app transport is **Wireless (WPP)**.
2. Access point SSID, password, and BSSID are entered in the car app.
3. BSSID is the access point MAC in `aa:bb:cc:dd:ee:ff` form—not Bluetooth MAC, phone MAC, zero, or broadcast.
4. Vehicle and phone were forgotten and paired again after WPP was installed.
5. Bluetooth **Phone calls** is enabled.
6. Companion is installed, permitted to run in the background, and assigned to the vehicle under **Auto-Start → Select Devices**.
7. Legacy companion **Car WiFi** entries are removed.
8. Both components are from matching current releases.

See [Wireless WPP](wireless-wpp.md).

## Phone joins vehicle Wi-Fi but projection never renders

Wi-Fi association proves only the network handoff.

- Confirm the companion service remains alive.
- Confirm **WPP network interface** is the active interface serving the vehicle access point.
- Do not expect discovery or transport to fall back to another interface; all WPP traffic is deliberately restricted to the selected one.
- Set **Hotspot frequency in MHz** only if logs show channel mismatch.
- Check for a car socket, Android Auto socket, and bridge establishment in the same attempt.
- Continue until the log or screen shows an actual first rendered frame.

## Wireless disconnects or loops

- Remove legacy companion **Car WiFi** entries on Android Auto 17.4+.
- Forget and re-pair Bluetooth.
- Keep **Phone calls** enabled; WPP depends on the hands-free profile remaining up.
- Disable Bluetooth **Media audio** if the native vehicle media path competes with Android Auto.
- Remove duplicate saved vehicle Wi-Fi profiles if the access point has no internet.
- Give each phone a unique Bluetooth device name.
- Confirm battery optimization is not killing the companion.

## Internet stops while connected to the vehicle

Projection does not require a paid vehicle data plan.

- If the vehicle access point has internet, a normal saved profile may remain useful.
- If it has no internet, forget the normal saved profile and let WPP request a local-only association.
- Android decides whether cellular or another Wi-Fi network remains the general internet path; behavior depends on the phone's concurrent-Wi-Fi support.

## USB asks every time

**Both USB Android Auto and USB CarPlay show two system USB access prompts on every connection on GM head units**, even if you check **Always open OpenAutoLink**, **Always allow** or **Use by default**, or save the choice. **Tap OK on both prompts each time.** This is a longstanding GM infotainment bug; OpenAutoLink cannot fix or bypass it.

The [example photo and setup warning](../README.md#gm-usb-prompts--android-auto-and-carplay) show an iPhone dialog with the checkbox already checked; USB Android Auto has the same limitation. These prompts are separate from OAL's **Switch / Stay here**, iPhone **Trust** and CarPlay local VPN consent. They are not evidence that your saved OAL settings were lost, and the warning does not apply to wireless projection.

## USB freezes or stalls

USB is confirmed on the maintainer's 2024 Blazer EV. Some users, including 2026 Equinox EV testers, reported freezes on earlier development builds. Those reports are useful debugging evidence but do not establish an Equinox-specific defect; app version, phone, cable, port, permissions, timing, and vehicle software may all contribute.

Capture:

- which physical USB port was used;
- cable type;
- whether the phone appeared in OpenAutoLink's picker;
- permission dialogs and their order;
- the last car log line before the freeze;
- whether the same phone works over WPP.

For the reported setup, call USB confirmed only after a current build produces a sustained rendered session. Do not generalize either a failure or success to the entire model without comparative evidence.

## Audio is doubled, silent, stale, or controlled by the wrong app

- Disable native copies of Spotify, YouTube Music, or similar apps on the head unit if both the native and projected sessions react to controls.
- Disable the phone pairing's Bluetooth **Media audio** when Android Auto should own media.
- **Android Auto:** keep Bluetooth **Phone calls** enabled for WPP and native hands-free call audio. This is not the USB CarPlay recommendation: unpair the iPhone from the car and leave Bluetooth disconnected for CarPlay calls. Bluetooth can cause repeated CarPlay media pauses even with Media audio off; see [phone calls](phone-calls.md).
- Confirm media metadata and audible audio refer to the same current track.
- If audio continues after Stop or falls seconds behind the UI, capture logs showing the native audio event and the Kotlin `Audio stop applied`/pending-audio evidence.

## CarPlay is silent after returning from FM or another source

1. Select **OpenAutoLink** in the vehicle’s **Audio** app.
2. Press the vehicle’s normal **Play** control and check media volume. There is no separate Resume audio button.
3. If USB CarPlay keeps pausing, disconnect the iPhone from car Bluetooth. Media audio OFF alone may not prevent pauses.

Normal phone pause→play recovery makes one bounded MEDIA focus attempt per fresh authenticated edge. This also permits autoplay to interrupt another selected source; there is no separate recovery toggle or Resume button. If the problem continues, upload logs with the approximate time and what you heard. [Developer source-return details](carplay-source-return-recovery.md).

## USB settings saved but the display did not change

**Save changes** persists the next-session settings without interrupting an actual USB projection. The visible notice is **Saved. Unplug and reconnect USB to apply.** Physically unplug/reconnect, approve the system prompts and check the new session. Saving alone does not renegotiate the running display. Audio/call choices save independently; see [saving and reconnecting](../README.md#what-save-changes-does).

## Calls or Siri are silent, or call setup is unavailable

1. **Android Auto:** pair the phone with the car and keep Bluetooth **Phone calls ON**. **Assistant microphone → Car / Phone** changes assistant input only.
2. **Recommended USB CarPlay:** unpair the iPhone from car Bluetooth, select **Audio → CarPlay calls → Call handling → CarPlay**, and complete **Set up calls**. Allow the phone permission, enable **OpenAutoLink** in Calling accounts, and return. **Ready** updates automatically.
3. In **Audio → Microphone → CarPlay**, follow **Enable microphone** or **Reconnect CarPlay** if shown. Reconnect USB after the call ends.
4. If using the **Car Bluetooth** alternative, check **Phone calls ON / Media audio OFF**, reconnect the pairing after profile changes, and check the iPhone’s call-audio destination.

Do not enable microphone overrides or call experiments for normal setup. For persistent microphone trouble, see the optional [parked diagnostic](microphone-diagnostic.md).

## The native Phone app replaces Android Auto or CarPlay during calls

In the vehicle’s **Phone → Settings**, turn **Active Call OFF** and **Privacy ON**. These change the display only; Android Auto still needs Bluetooth Phone calls enabled. See [phone calls](phone-calls.md).

## The cluster shows Unknown instead of the caller’s name

Some GM cluster displays use a phone number or their own contact lookup even when CarPlay shows the name. This is a known display limitation; repeating OAL account approval will not fix it.

## Steering-wheel controls affect the wrong player

- Disable duplicate native media apps.
- Open **Settings → Input** and verify the custom mapping.
- Save changes and test against the current active Android Auto session.
- Remember that the vehicle's own voice button may remain reserved for its native assistant on some platforms.

## Cluster navigation or media is missing

Treat cluster navigation and media as separate integrations.

- Confirm main-screen projection works first.
- Start an active route and check for visible turn updates, not only a binding/status message.
- Check media independently.
- On a new vehicle, report cluster support as unknown until visible output is observed.

## H.265 or high resolution behaves badly

- Use GAL 6.0, the current default.
- Return video negotiation to Auto before forcing a codec/resolution.
- Test H.264 at a lower tier as a compatibility control.
- Record decoder name, resolution, frame rate, and whether the first frame rendered.

## Collecting useful logs

Use OpenAutoLink's built-in logging/export controls. For a wireless incident, collect both:

- car app log;
- companion log.

Name the real-world action and approximate time, but do not assume car and phone clocks are identical. Include the last known good action and the visible outcome.

Never include:

- Wi-Fi passwords;
- Google or Play credentials;
- signing keys/passwords;
- invitation tokens;
- unrelated personal data.

When reporting a fix, describe what now works—for example, stable video and working touch after reconnecting.
