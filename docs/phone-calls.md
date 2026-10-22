# Phone calls in Android Auto and USB CarPlay on GM vehicles

## USB CarPlay: Bluetooth-selected audio

The owner-tested setup on the maintainer’s 2024 Chevrolet Blazer EV keeps USB CarPlay connected for its screen and touch controls while native Bluetooth carries music, navigation voice and hands-free calls. Music, spoken directions and car microphone/speaker calls worked in the tested setup; other vehicles and firmware may differ.

1. Pair the iPhone with the vehicle and keep **Phone calls** and **Media audio** enabled.
2. Connect USB CarPlay using the [README USB steps](../README.md#usb-1), accepting the applicable GM access, OAL local VPN and iPhone Trust prompts.
3. **While USB CarPlay remains connected, press the steering-wheel audio-source button, then select Bluetooth in the instrument cluster.** This is the car’s audio input, not a per-iPhone setting or a center-display menu.
4. Apply the native **Phone → Settings → Active Call OFF → Privacy ON** display settings below. Leave Bluetooth **Phone calls** enabled.

Pairing alone does not select the Bluetooth audio input. OAL does not select it automatically; persistence across reconnects and phone switching remains unverified. Native HFP provides the call-audio path while the projected call interface remains usable. Keep Siri activation/input acceptance separate: one working Siri/HFP interval does not establish every Siri route works.

### Shared navigation/media volume

Navigation directions use the observed Bluetooth media-volume context, not a separate GM navigation-volume control. OAL cannot separately amplify guidance that it does not receive as its own audio stream. Apple’s iOS 26 guide documents **Settings → Apps → Maps → Spoken Directions → Voice Volume**; its effect on prompt-to-music balance on this Bluetooth route is untested. Match the menu to the phone’s installed iOS. See [Apple’s Maps guide](https://support.apple.com/guide/iphone/iphd3c85c193/26/ios/26).

### Bluetooth-free capture remains under investigation

USB projection can connect without Bluetooth, but reliable two-way Bluetooth-free calls and Siri capture remain unresolved. Do not remove a working pairing to follow older unpair-only advice. The Playing-status suppression experiment is not required by this setup and did not stop pausing in the owner’s test.

### Alternate Android Bluetooth settings launcher

**OAL → Settings → Connection → CarPlay → Android Bluetooth settings** opens an alternate system screen on supported vehicles while OAL is foreground with fresh actual Park. OAL does not change profiles. This launcher is separate from selecting the **Bluetooth input with the steering-wheel audio-source button and instrument cluster**.

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
