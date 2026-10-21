# Phone calls in Android Auto and USB CarPlay on GM vehicles

## USB CarPlay: leave the iPhone unpaired from vehicle Bluetooth

USB CarPlay carries call audio independently of vehicle Bluetooth. Music playback and call operation were reported on the maintainer's 2024 Chevrolet Blazer EV without Bluetooth, but subsequent incoming-call tests exposed silent microphone input. **Reliable two-way CarPlay call audio is not yet verified.** Do not rely on a completed call, audible caller, or working Siri as proof the caller can hear the car microphone.

**Do not pair the iPhone to the vehicle's Bluetooth for USB CarPlay.** If already paired, forget/remove that pairing so it does not reconnect. GM's native Bluetooth playback control competes with CarPlay when the iPhone is also Bluetooth-connected, causing media to constantly pause after pressing Play. Leave the car's Bluetooth adapter available for other phones; only the iPhone's vehicle pairing needs to be removed.

This advice is specific to **USB CarPlay**. It does not replace Android Auto's Bluetooth requirements below. For an explicitly agreed paired diagnostic experiment, record the chosen topology and keep it unchanged between arms rather than unpairing mid-experiment.

**Reliable two-way CarPlay call microphone audio remains unverified. Siri is not fixed.** Audible caller playback, nonzero samples or successful packet sends do not establish understood speech or far-end audibility. Bluetooth pairing alone does not prove which audio route the vehicle selected.

### Alternate Android Bluetooth settings launcher

**OAL → Settings → Connection → CarPlay → Android Bluetooth settings** opens an alternate system screen on supported vehicles. It may expose separate per-phone controls absent from the usual vehicle settings screen. Availability requires an installed, permitted exported screen, foreground OAL and fresh actual Park. OAL does not change profiles. This feature description does not recommend a paired USB CarPlay configuration or claim persistent GM settings; Media audio ON with Playing-status suppression still requires a parked experiment and owner acceptance.

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
