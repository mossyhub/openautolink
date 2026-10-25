# Phone calls in Android Auto and USB CarPlay on GM vehicles

## USB CarPlay: two current audio setups

Connect using the [README USB steps](../README.md#usb-1), accepting the GM USB access, OAL local VPN and any iPhone Trust prompts. USB CarPlay does not require vehicle Bluetooth pairing or car Wi-Fi configuration. Keep **OAL selected as the car's media source** for either option below.

### Option 1 — Leave vehicle Bluetooth unpaired

Leave the iPhone unpaired from the vehicle and use USB CarPlay for normal music and navigation. This avoids the competing native Bluetooth media connection, but is **not a cabin-microphone fix**. **Car microphone input for calls/Siri is inconsistent: it works for some users, fails for others, and may work in one session but not the next—even on the same car. We are investigating; do not rely on it yet.** The maintainer has experienced both success and failure. This appears to be GM AAOS-specific microphone/routing weirdness that I’m still trying to pin down. Calls may instead use the iPhone; this is not a claim that every Bluetooth-free call uses the handset.

### Option 2 — Keep native calls with Phone ON / Media OFF

The maintainer confirmed separate CarPlay music/navigation volume and working native hands-free calls during this setup's tested interval on a 2024 Chevrolet Blazer EV:

1. Keep the iPhone paired with the vehicle and **Phone calls ON**.
2. In OAL, open **Settings → Connection → CarPlay → Android Bluetooth settings** using the button we added. It opens an alternate system Bluetooth screen on supported vehicles; OAL itself does not change profiles.
3. For the connected iPhone, disable **Media audio** (the **music-note icon**). Leave **Phone calls ON**.
4. Press the iPhone's **Bluetooth** button to disconnect, then reconnect it again.
5. Return to OAL and keep **OAL selected** as the media source, not Bluetooth.
6. Open the vehicle's native **Phone** app, tap its **Settings** gear, set **Active Call OFF** (“Show active call view when answering call”) and **Privacy ON** (“Only show call alerts in cluster”). These are display settings, separate from Bluetooth profiles.

CarPlay carries music and navigation on separate volume paths; native Bluetooth HFP carries the call microphone and speakers. The owner-reported successful calls are real, but do not prove every vehicle, reconnect or Siri path works.

**GM may turn Media back on after you leave/wake the car.** If it does, or pausing returns, reopen **OAL → Settings → Connection → CarPlay → Android Bluetooth settings**, turn Media OFF again, disconnect/reconnect using the Bluetooth button, then return to OAL. Repeat when needed: this is a manual workaround, not automatic or proven durable policy preservation.

### Older Bluetooth-selected fallback is not equivalent

Keeping Media ON and selecting Bluetooth with the steering-wheel audio-source button in the instrument cluster produced some working intervals, but later owner testing reproduced pauses. That route also shares music/navigation volume rather than preserving separate CarPlay volume. It is **not one of the two current recommendations** above. OAL cannot independently amplify guidance already mixed into the native Bluetooth media stream.

### Experimental microphone and native-call integration — not ready

These Diagnostics tools are attempts to make CarPlay better and more native, **not required for normal CarPlay or native HFP calls**:

| Area | Current status |
|---|---|
| Cabin microphone / Siri | Car microphone input works for some users, fails for others and can change between sessions on the same car, including the maintainer's. We are investigating; do not rely on it yet. In a previously tested setup, “Hey Siri” used the iPhone microphone for wake detection and responses played through the car; accessory-button/in-app Siri is a separate input path, not proved by wake-phrase success. |
| **Diagnostics → Microphone** | Optional local capture and Java/native AAudio comparisons; numeric results are not proof of caller audibility and do not repair routing. No audio recordings are saved or uploaded. |
| **Diagnostics → Native CarPlay calls** | Experimental **Native call integration** and separate **Native Answer/End** controls. Not ready; native GM/cluster acceptance remains unverified. **Reject is unsupported**; decline on the iPhone or in CarPlay. |
| Native account permission | Enabling that experiment may request Android's **READ_PHONE_NUMBERS** permission for OAL's own PhoneAccount readback and require Android account enablement. This is **not required for regular Bluetooth Phone calls/HFP** and does not grant microphone routing or cluster acceptance. |
| Media-session experiments | Playing-status suppression and **INACTIVE** did not stop pausing in owner testing. INACTIVE can remove title/art; neither is a normal setup requirement. |

Native-call integration choices last until **Off or app restart**; established choices survive leaving Diagnostics. Reconnect CarPlay after changing controls. The experiment does not replace the iPhone's cellular service or fix cabin audio. For local microphone collection, see the [microphone diagnostic guide](microphone-diagnostic.md).

## Android Auto: native Bluetooth hands-free audio

OpenAutoLink can keep Android Auto's incoming-call and in-call interface on the center display while the vehicle's native Bluetooth hands-free system carries microphone and speaker audio.

These are two separate paths:

```text
Call UI/control: Android Auto → OpenAutoLink input → phone call action
Voice audio:     phone ↔ vehicle Bluetooth HFP/SCO ↔ microphone and speakers
```

## GM call privacy settings

Configure this once in the vehicle's native **Phone** app:

> **Phone → Settings → Active Call OFF → Privacy ON**

1. Open the native **Phone** app.
2. Tap the **Settings** gear.
3. Turn **Active Call** **OFF**. Its description is **“Show active call view when answering call.”**
4. Turn **Privacy** **ON**. Its description is **“Only show call alerts in cluster.”**

These settings keep native incoming-call alerts in the cluster and stop the native active-call screen from replacing Android Auto on the center display.

## Bluetooth setting that must remain enabled

Keep the Bluetooth profile named **Phone calls** enabled for the phone.

Do not confuse it with the two display settings above. The Bluetooth profile:

- carries the vehicle's hands-free call audio;
- uses the vehicle microphone and speakers;
- keeps the Bluetooth link required to start Wireless WPP on Android Auto 17.4+.

Disabling **Phone calls** can break both voice audio and wireless projection startup.

Bluetooth **Media audio** is separate and may be disabled when it competes with Android Auto media playback.

## End-to-end verification

A complete call test must show all of these:

1. Incoming-call UI appears inside Android Auto while projection stays foreground.
2. Android Auto's **Answer** control changes the phone from ringing to active.
3. Projected mute/keypad/end controls remain usable.
4. Caller audio is heard through the vehicle speakers.
5. The vehicle microphone reaches the caller.
6. The native Phone app does not take over the center display.
7. Ending the call returns cleanly to the previous projected screen.

Seeing Android Auto answer the call does not by itself prove which path carries voice audio. The expected GM/OpenAutoLink topology uses native Bluetooth HFP/SCO for voice.

## If the native Phone app still takes over

- Recheck **Active Call OFF** and **Privacy ON** inside the native Phone app.
- Confirm you changed the Phone-app display settings, not the phone pairing's Bluetooth profiles.
- Restart the native Phone app or the head unit after changing the settings.
- Record the exact vehicle, AAOS version, incoming/answered state, and which screen appeared.

## If controls work but voice does not

- Confirm Bluetooth **Phone calls** is enabled.
- Confirm the phone shows the vehicle as the current call-audio route.
- Test both directions of audio.
- Re-pair Bluetooth if the HFP profile is absent or unstable.
- Do not diagnose the projected UI as the audio transport; they are independent.
