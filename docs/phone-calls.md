# Phone calls and microphones

For music playback, source selection and disconnected-state guidance, see [Play phone media from vehicle Audio](../README.md#play-phone-media-from-vehicle-audio). Native Audio playback and call handling are separate; selecting the media source does not select a call route.

## Android Auto — USB and wireless

- **USB:** select **Settings → Connection → Android Auto connection → USB**, save, and connect a data-capable cable. No companion is required. On GM, accept both USB access prompts on every connection.
- **Wireless:** install the matching Android companion, select **Wireless (WPP)** in the car, configure the car hotspot and select the paired car in companion Auto-Start. See [WPP setup](wireless-wpp.md).
- For **both**, pair the Android phone with the vehicle and keep Bluetooth **Phone calls ON**. USB carries projection, not cellular voice; native Bluetooth hands-free audio carries calls through the car microphone/speakers. Wireless Bluetooth also bootstraps the projection connection; that is separate from carrying call audio. Disabling Phone calls can therefore break calls and wireless startup.
- **Settings → Audio → Android Auto → Assistant microphone → Car / Phone** controls the assistant only. Car is the fresh-install default; a saved Phone choice is preserved. Changes save automatically for the next microphone stream, without changing an active recorder. This selector does not route cellular calls or change CarPlay.

## CarPlay — recommended unpaired USB

1. Install OAL with your own [authorized authentication identity](carplay-private-mfi-assets.md). Select **Connection → CarPlay → USB**, then tap **Save changes** if changing the connection. If already plugged in, unplug and reconnect the iPhone to apply changes.
2. **Unpair the iPhone from the car and leave Bluetooth disconnected**. Bluetooth can cause repeated media pauses, even with Media audio off. Connect a data-capable USB cable; approve both GM access prompts each time, OAL's separate local VPN consent and any iPhone Trust prompt. USB needs no car Wi-Fi configuration.
3. Keep **OpenAutoLink selected in vehicle Audio**; the shared **Settings → Audio → Both → Open OpenAutoLink in vehicle Audio** action requests that view on supported cars. A launch request is not proof the car selected the source.
4. Open **Settings → Audio → CarPlay calls**. Leave **Call handling → CarPlay**, the fresh-install default, and **Extra car ringtone → Off** for CarPlay ringing without a second native ring.
5. Tap **Set up calls**, allow Android's phone permission, turn on **OpenAutoLink** in **Calling accounts**, then return to OAL. The page rereads actual approval automatically. **Ready** means Android call setup is complete for the selected CarPlay configuration—not that OAL proved Bluetooth absent. Keep the iPhone disconnected from vehicle Bluetooth. OAL cannot enable its own Android account for you.

No diagnostic Arm, profile, Apply or Save is required for normal calls or button-initiated Siri. Cabin capture still needs microphone permission. If permission was missing when input offers were frozen, grant it and explicitly reconnect when not in a call; OAL does not interrupt calls automatically. “Hey Siri” wake detection on the iPhone is a separate path from accessory-button/Maps Siri.

**Upgrade:** an already approved OAL account keeps its identity; no repeated setup on every reconnect or mandatory visit to Audio. Audio/call preferences save independently of connection/video/display changes. Changing handler during an admitted call applies to the next call and does not hang up the current one. A failed preference write remains visible rather than reporting Saved.

<img src="screenshots/GM-Calling-Accounts-Redacted.jpg" alt="Actual GM Calling accounts page with independent HFP and OAL account switches; Bluetooth address redacted" width="900">

*Actual owner GM photograph, cropped and address-redacted. It shows the historical “OAL CarPlay incoming experiment” label; the production label is OpenAutoLink. Independent account switches are approval, not proof of same-phone audio ownership.*

### Alternative — Car Bluetooth calls

> [!WARNING]
> **Bluetooth can cause repeated media pauses, even with Media audio off.** CarPlay is the recommended/default handler. Audio settings mark Car Bluetooth with a red warning; unpair the iPhone and leave vehicle Bluetooth disconnected for the recommended USB setup. This is not a Bluetooth pause-loop fix.

If you deliberately need this risk-bearing alternative, select **Call handling → Car Bluetooth** in Audio. Pair the iPhone to the vehicle, keep **Phone calls ON / Media audio OFF**, then disconnect/reconnect that pairing using **Connection → CarPlay → Android Bluetooth settings** and return to OAL. Keep OAL as the media source for CarPlay music/navigation. The selector controls OAL's native-call integration; it does not itself reroute iPhone audio or alter Bluetooth profiles. OAL does not announce/control calls in this mode; Siri cabin capture remains independent. OAL account approval is not needed for this Bluetooth call path.

GM may restore Media audio after leaving/waking the car. Turning Media OFF and disconnecting/reconnecting may change the observed behavior, but it is not a reliable or durable pause-loop workaround. Leaving Media ON and selecting Bluetooth in the cluster is not equivalent: it shares media/navigation volume and also reproduced pauses.

Wireless CarPlay remains a development option, not working in the maintainer's vehicle. Its Bluetooth enrollment/bootstrap is not a requirement for USB and does not itself prove a hands-free call route.

## Audio settings illustrations

<img src="screenshots/Audio-Settings-Landscape.png" alt="Implemented OAL Audio settings with separate Android Auto and CarPlay microphone scopes and no global Save action" width="800">

<img src="screenshots/Audio-Settings-CarPlay-Calls.png" alt="Implemented CarPlay call handling, setup, extra ringtone Off and optional Android settings actions" width="800">

*Prior actual app Compose renders, not GM photographs; the setup state is a test fixture. They predate the new red Bluetooth-pause warning and CarPlay Recommended label and have not been refreshed; follow the current written guidance. [Narrow / large-text render and image provenance](screenshots/README.md#current-audio-settings-and-actual-gm-approval-page).*

## GM display preferences and limitations

In the vehicle's native **Phone → Settings**, **Active Call OFF** (Show active call view when answering call) and **Privacy ON** (Only show call alerts in cluster) can keep the projected center screen visible. These are display preferences, not Bluetooth or OAL permission switches. Optional **Android call settings** and **Android sound settings** actions in Audio open system pages only; OAL does not change global call/sound settings.

The owner accepted build 548 calls, CarPlay Maps Siri, wheel Answer/Reject/End and native call presentation. Five released capture owners contained 3095/3095 nonzero raw blocks; those are not five independently qualified calls or a guarantee of every vehicle/restart/ignition cycle. Native presentation/control requires Android approval and the selected call configuration. Unknown Bluetooth status is not proof of disconnection: explicit CarPlay mode relies on keeping the iPhone disconnected, warns on detected conflict, and does not promise automatic duplicate prevention. Supported controls concern known single incoming calls, not outgoing/held/conference features. Keep **Extra car ringtone Off** for the accepted single-ring setup; turn it On only as a deliberate compatibility fallback if alerts disappear.

**CarPlay call controls and caller names:** CarPlay mode still supports the car microphone, steering-wheel Answer/Reject/End and native driver display/cluster on supported vehicles. The cluster can show **Unknown** while the main CarPlay UI shows the contact; some GM consumers use the number/phonebook and discard the supplied display name. Account approval does not fix that limitation, and choosing Bluetooth is not required to retain supported cluster controls.

## Verify while parked

Test incoming Answer/Reject/End, both directions of speech, one ringtone, button-initiated Siri and return to music/navigation. Seeing call UI or nonzero samples alone does not prove the far-end caller hears the cabin microphone. Test a later reconnect separately. If calls lack audio, check the selected handler and the iPhone's actual audio route; for Bluetooth calls verify Phone calls remains enabled. If setup is unavailable, follow the one visible action rather than repeatedly toggling diagnostics. The Bluetooth alternative carries the repeated-pause warning above; it is not an equivalent recommended fallback.

For native source return, select OpenAutoLink in vehicle Audio and use ordinary **Play** if needed; there is no separate Resume audio button. The current automatic recovery candidate still needs physical music-and-navigation acceptance and is not a Bluetooth loop repair. See [source-return recovery](carplay-source-return-recovery.md).

Advanced repair/status and numeric capture tools remain in [Diagnostics](microphone-diagnostic.md); they are not normal setup prerequisites.
