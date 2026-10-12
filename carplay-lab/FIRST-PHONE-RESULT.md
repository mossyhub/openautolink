# First physical-iPhone authentication result

TCL 9466X, Android API 33; experimental package `com.openautolink.carplaylab`.

Captured device-log timestamps: 2026-10-06 23:07:32.995–23:07:35.104. These are tablet timestamps, not a cross-device clock comparison.

Observed through host Wi-Fi ADB at user-provided `192.168.1.100:38277`:

- Local identity initialized with authentication protocol major 3.
- Outgoing Bluetooth RFCOMM connection succeeded.
- iPhone accepted iAP2 identification.
- iPhone requested certificate (`0xaa00`), then supplied a 32-byte challenge (`0xaa02`).
- Local signing produced a 64-byte response, sent as `0xaa03`.
- iPhone explicitly returned `0xaa05` authentication-succeeded.
- Terminal probe state: `identified=true authenticated=true`.

Evidence: `build/first-attempt.log` (captured status-only log; no private identity/challenge/signature contents).

This proves identity acceptance by this physical iPhone for this attempt, not merely an offline signing check. It does not prove AirPlay pairing, CarPlay availability/session start, IP transport, video, touch, audio, Siri/calls, GM networking or future iOS/revocation compatibility. This probe closes Bluetooth after authentication by design.

Before the successful attempt, Activity recreation had left the tablet Bluetooth-address field blank; configuration validation stopped that attempt before protocol exchange. Restoring the actual tablet address through ADB enabled the successful attempt. Persist/restore lab configuration before repeated rotation/recreation testing.
