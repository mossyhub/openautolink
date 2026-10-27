# Phone calls and microphones

## Android Auto — USB and wireless

- **USB:** select **Settings → Connection → Android Auto connection → USB**, save, and connect a data-capable cable. No companion is required. On GM, accept both USB access prompts on every connection.
- **Wireless:** install the matching Android companion, select **Wireless (WPP)** in the car, configure the car hotspot and select the paired car in companion Auto-Start. See [WPP setup](wireless-wpp.md).
- For **both**, pair the Android phone with the vehicle and keep Bluetooth **Phone calls ON**. USB carries projection, not cellular voice; native Bluetooth hands-free audio carries calls through the car microphone/speakers. Wireless Bluetooth also bootstraps the projection connection; that is separate from carrying call audio. Disabling Phone calls can therefore break calls and wireless startup.
- **Settings → Audio → Android Auto → Assistant microphone → Car / Phone** controls the assistant only. Car is the fresh-install default; a saved Phone choice is preserved. Changes save automatically for the next microphone stream, without changing an active recorder. This selector does not route cellular calls or change CarPlay.

## CarPlay — recommended unpaired USB

1. Install OAL with your own [authorized authentication identity](carplay-private-mfi-assets.md). Select **Connection → CarPlay → USB**, then tap **Save changes** if changing the connection. If already plugged in, unplug and reconnect the iPhone to apply changes.
2. Leave the iPhone **unpaired from vehicle Bluetooth**. Connect a data-capable USB cable; approve both GM access prompts each time, OAL's separate local VPN consent and any iPhone Trust prompt. USB needs no car Wi-Fi configuration.
3. Keep **OpenAutoLink selected in vehicle Audio**; the shared **Settings → Audio → Both → Open OpenAutoLink in vehicle Audio** action requests that view on supported cars. A launch request is not proof the car selected the source.
4. Open **Settings → Audio → CarPlay calls**. Leave **Call handling → CarPlay**, the fresh-install default, and **Extra car ringtone → Off** for CarPlay ringing without a second native ring.
5. Tap **Set up calls**, allow Android's phone permission, turn on **OpenAutoLink** in **Calling accounts**, then return to OAL. The page rereads actual approval automatically. **Ready** means Android call setup is complete for the selected CarPlay configuration—not that OAL proved Bluetooth absent. Keep the iPhone disconnected from vehicle Bluetooth. OAL cannot enable its own Android account for you.

No diagnostic Arm, profile, Apply or Save is required for normal calls or button-initiated Siri. Cabin capture still needs microphone permission. If permission was missing when input offers were frozen, grant it and explicitly reconnect when not in a call; OAL does not interrupt calls automatically. “Hey Siri” wake detection on the iPhone is a separate path from accessory-button/Maps Siri.

**Upgrade:** an already approved OAL account keeps its identity; no repeated setup on every reconnect or mandatory visit to Audio. Audio/call preferences save independently of connection/video/display changes. Changing handler during an admitted call applies to the next call and does not hang up the current one. A failed preference write remains visible rather than reporting Saved.

<img src="screenshots/GM-Calling-Accounts-Redacted.jpg" alt="Actual GM Calling accounts page with independent HFP and OAL account switches; Bluetooth address redacted" width="900">

*Actual owner GM photograph, cropped and address-redacted. It shows the historical “OAL CarPlay incoming experiment” label; the production label is OpenAutoLink. Independent account switches are approval, not proof of same-phone audio ownership.*

### Alternative — Car Bluetooth calls

Select **Call handling → Car Bluetooth** in Audio. Pair the iPhone to the vehicle, keep **Phone calls ON / Media audio OFF**, then disconnect/reconnect that pairing using **Connection → CarPlay → Android Bluetooth settings** and return to OAL. Keep OAL as the media source for CarPlay music/navigation. The selector controls OAL's native-call integration; it does not itself reroute iPhone audio or alter Bluetooth profiles. OAL does not announce/control calls in this mode; Siri cabin capture remains independent. OAL account approval is not needed for this Bluetooth call path.

GM may restore Media audio after leaving/waking the car. Repeat the manual Media OFF + disconnect/reconnect procedure if needed. Leaving Media ON and selecting Bluetooth in the cluster is not equivalent: it shares media/navigation volume and previously reproduced pauses.

Wireless CarPlay remains a development option, not working in the maintainer's vehicle. Its Bluetooth enrollment/bootstrap is not a requirement for USB and does not itself prove a hands-free call route.

## Audio settings illustrations

<img src="screenshots/Audio-Settings-Landscape.png" alt="Implemented OAL Audio settings with separate Android Auto and CarPlay microphone scopes and no global Save action" width="800">

<img src="screenshots/Audio-Settings-CarPlay-Calls.png" alt="Implemented CarPlay call handling, setup, extra ringtone Off and optional Android settings actions" width="800">

*Actual app Compose renders, not GM photographs; the setup state is a test fixture. [Narrow / large-text render and image provenance](screenshots/README.md#current-audio-settings-and-actual-gm-approval-page).*

## GM display preferences and limitations

In the vehicle's native **Phone → Settings**, **Active Call OFF** (Show active call view when answering call) and **Privacy ON** (Only show call alerts in cluster) can keep the projected center screen visible. These are display preferences, not Bluetooth or OAL permission switches. Optional **Android call settings** and **Android sound settings** actions in Audio open system pages only; OAL does not change global call/sound settings.

The owner accepted build 548 calls, CarPlay Maps Siri, wheel Answer/Reject/End and native call presentation. Five released capture owners contained 3095/3095 nonzero raw blocks; those are not five independently qualified calls or a guarantee of every vehicle/restart/ignition cycle. Native presentation/control requires Android approval and the selected call configuration. Unknown Bluetooth status is not proof of disconnection: explicit CarPlay mode relies on keeping the iPhone disconnected, warns on detected conflict, and does not promise automatic duplicate prevention. Supported controls concern known single incoming calls, not outgoing/held/conference features. Keep **Extra car ringtone Off** for the accepted single-ring setup; turn it On only as a deliberate compatibility fallback if alerts disappear.

**Cluster caller names:** some GM consumers use the number/phonebook and discard the display name OAL supplies. Account approval does not repair that limitation.

## Verify while parked

Test incoming Answer/Reject/End, both directions of speech, one ringtone, button-initiated Siri and return to music/navigation. Seeing call UI or nonzero samples alone does not prove the far-end caller hears the cabin microphone. Test a later reconnect separately. If calls lack audio, check the selected handler and the iPhone's actual audio route; for Bluetooth calls verify Phone calls remains enabled. If setup is unavailable, follow the one visible action or use the Bluetooth alternative rather than repeatedly toggling diagnostics.

Advanced repair/status and numeric capture tools remain in [Diagnostics](microphone-diagnostic.md); they are not normal setup prerequisites.
