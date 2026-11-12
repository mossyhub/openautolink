# Wireless Android Auto startup recovery

## Update both ends

Install the current Ring0 car update and the matching signed Companion APK from the same GitHub release. The car update cannot update the phone. Keep existing pairings, app data, saved credentials and display settings; no reset is required for this correction.

## What changed in 0.1.583

Companion now reads a waiting Android Auto connection without consuming a car connection just to discover that the phone-side peer already closed. Read-ahead is bounded, live silent waiters remain usable, and early protocol bytes are forwarded once when a bridge is established. If a car arrival suppressed an AA invitation but that exact waiter dies before bridging, Companion can reinvite AA for that same pending car. Ticket/generation checks reject stale callbacks after replacement or stop.

On the car, WPP credential handoff no longer waits for a phone-address discovery sweep. The exact endpoint starts its discovery opportunity after the credential exchange. It expires after 30 seconds or earlier when the control connection closes, the session/phone claim/interface changes, ignition is off, or projection is streaming. Repeated callbacks do not renew it. A later identity-matched peer using the reserved proxy port can activate the original bootstrap attempt; legacy compatibility recovery remains separate.

Identity matching and selected-interface/subnet validation still apply before the endpoint is admitted. No general Wi-Fi disconnect/retention policy, active-call protection, USB behavior, CarPlay setting or secondary-display default is changed.

## What is not established

A real-socket test reproduced a dead prewarm waiter consuming and immediately closing a fresh car connection on 0.1.582. Paired vehicle logs also showed phone reachability after the previous discovery window had expired. These support the narrow corrections; they do not prove which component initiated the first observed Wi-Fi loss, nor that every Wi-Fi/projection cycle is eliminated on the vehicle.

For validation, use the matching versions on both ends and try an ordinary startup before manual Save/Reconnect/Bluetooth interventions. If startup still cycles, upload both car and Companion logs from the same attempt and note what the phone's Wi-Fi screen showed. Network-observer loss alone does not identify a particular SSID or prove an app requested disassociation. Keep software/publication verification distinct from successful car acceptance.
