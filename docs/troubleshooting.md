# Troubleshooting OpenAutoLink

Start with the outcome, not the status label. `CONNECTED`, `READY`, a successful build, or the absence of an error does not prove projection works. The positive signal is a rendered frame followed by continued video, input, and the feature being tested.

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
- Keep Bluetooth **Phone calls** enabled for WPP and native hands-free call audio.
- Confirm media metadata and audible audio refer to the same current track.
- If audio continues after Stop or falls seconds behind the UI, capture logs showing the native audio event and the Kotlin `Audio stop applied`/pending-audio evidence.

## The native Phone app replaces Android Auto during calls

Follow [Phone calls](phone-calls.md). Turn the native Phone app's **Active Call** display off and **Privacy** on, while leaving the Bluetooth **Phone calls** profile enabled.

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

When reporting a fix, identify the positive signal—for example, `First frame rendered` followed by sustained frames and working touch—not merely that an old error disappeared.
