# TCL media attempt — 2026-10-09

Experimental OAL branch only; no vehicle or Play upload.

## Observed
- Physical iPhone identification and MFi authentication succeeded repeatedly.
- AirPlay encrypted control accepted SETUP, GET /info, and RECORD.
- Event TCP connection accepted.
- Phone offered one type-130 iAP2 DataStream; receiver accepted one stream.
- Phone sent TEARDOWN roughly 10 ms after the stream SETUP response, followed by control EOF.
- No `tunnelConnected` marker, screen stream offer, rendered-frame marker or audio-output proof.
- Runtime I/O error stack points to an already-closed tunnel during link flush; observed after TEARDOWN, not proven initiating cause.
- Saved Wi-Fi password survived multiple APK replacements and cold app launches; subsequent preflight/auth/endpoint attempts needed no re-entry.
- Latest combined unit-test, receiver lint and OAL bench assembly command succeeded; APK installed and activity launch read back.

## Evidence
Ignored local artifact `build/media-attempt.log` holds latest capture (tablet timestamps 11:08:21–11:08:23). Captures are overwritten by each attempt. Diagnostics contain method names, lengths, numeric stream types, exception class names and source locations only; no raw plist bodies, key values or Wi-Fi credentials.

## Follow-up review and corrected build
- Static comparison did not establish an adaptation-specific reason for the initial teardown.
- Corrected transport-notification (0x4e0e) Wi-Fi resend (0x5703); physical capture now shows that reply before StartSession.
- Replaced combined endpoint reply quotas in both bootstrap and runtime with independent pre/post-transport Wi-Fi limits (5/2) matching the pinned controller; CarPlay availability is independent.
- Runtime retirement now closes the AirPlay session even if channel close throws.
- Clean tunnel peer EOF now notifies the owner; real loopback regression failed before correction and passes afterward.
- Full receiver suite: 62 tests, zero failures/errors. Receiver lint: zero errors. OAL bench APK built, installed and cold-launched successfully.
- Retest at tablet timestamps 11:13:29–11:13:33 still reaches type-130 SETUP, then immediate phone TEARDOWN; no tunnel-connected marker or rendered/audio proof.
- Preserved this post-fix capture as ignored `build/media-reviewed-attempt.log`.
- Tablet is a non-root production build; host tcpdump cannot by itself observe switched phone-to-tablet Wi-Fi traffic. No phone USB connection or libimobiledevice tools were present in the host discovery. Phone-side logging remains a prerequisite for further direct failure evidence.

## Source-backed startup/audio integration
- Independent Apple WWDC16/722 confirms wireless output requires AAC-LC media and Opus/AAC-ELD other audio; the prior PCM-only declaration was not a supported wireless subset.
- Implemented capability-filtered Android MediaCodec output decoding and exact compressed format admission. No microphone/uplink or buffered audio is offered. Decoder enumeration is not successful codec configuration or human audibility.
- Corrected Opus feedback to use negotiated RTP clock rather than its 48 kHz decoded PCM rate.
- Added session-owned requestUI after RECORD response flush and encrypted event readiness, without an unsupported mandatory TakeScreen pair.
- A startup-only capture showed unsynchronized RECORD with one timing reply. Added a bounded wait for matched synchronization; real loopback readiness/timeout tests pass.
- Unfiltered combined receiver suite now passes 75 tests, zero failures/errors/skips; receiver lint has zero errors; OAL bench assembly succeeded. Updated APK installed and cold activity launch read back.
- Targeted physical capture `build/media-attempt-20261009-113628.log` shows auth accepted, event connection, RECORD at 11:36:32.073 with synced=true/matchedResponses=2, requestUI sent, and type130 controlType=2/dedicated=true/asIs=true with valid channel/client field types.
- The phone still sent TEARDOWN at 11:36:32.173, after the type130 success response at 11:36:32.151. No application-observed tunnel connection or screen/audio setup. Timing and requestUI corrections did not solve this failure; compressed decoder runtime has not been exercised by the phone.
- Installed host libimobiledevice-utils for USB phone-side logs; no USB iPhone currently connected. Protocol tools may still have compatibility limits on this iOS.

## Remaining
Find the phone-side reason for immediate teardown, preferably using USB airplayd/CarKit logs. Do not claim static source agreement as causal proof. Video, audible audio and touch remain unproven. Home-LAN IPv4 proof does not establish GM AP scoped-IPv6 behavior.
