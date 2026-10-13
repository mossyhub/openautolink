# Changelog

## 0.1.520

### Android Auto USB
- Repair Android USB access-result delivery after the unified CarPlay USB integration; permission callbacks now match the registered receiver.
- Clear stale permission requests when a phone disconnects or an exact USB admission fails, preserving current-owner and stale-generation checks.
- Reset failed USB admission state so deliberate Retry USB can request access again.
- Cover permission delivery and denial, equal-but-distinct device-name cleanup, retry and stale callback protection on Android API 32 and 34.
- Applies to saved Android Auto USB mode and wireless-to-USB Switch. Software regression tests pass; physical permission prompts and sustained projection still require vehicle confirmation.
- No companion update is required for this car-side correction. CarPlay permission handling and saved connection modes are unchanged.

### CarPlay vehicle data
- Discover each EV property's actual HAL change mode instead of inferring it from the requested sensor rate.
- Permit stable STATIC/ONCHANGE event timestamps only while real status reads or events keep the exact registration live; retain existing age limits for continuous or unknown modes.
- Revalidate supported stable-source properties on the existing serialized vehicle-data lane and promptly revoke unavailable/null observations. Retain actual usable capacity requirements and do not substitute nominal capacity.
- Vehicle change-mode confirmation and Apple Maps EV recognition remain unverified.

### CarPlay microphone diagnostics
- Add bounded exact-recorder input-route, client-silenced, permission and global-mute receipts to distinguish capture policy from uplink delivery.
- Keep OS queries on one zero-backlog process worker with at most three attempts per capture and fence late results at close.
- Export only finite sanitized metadata, not raw audio or device identities. No microphone source/rate change or claim that Siri silence is fixed.

## 0.1.518

### CarPlay audio negotiation
- Add permission-gated, independently qualified 24 kHz mono PCM microphone input for default, phone and voice-recognition streams, alongside existing Opus support.
- Use the negotiated capture rate, sealed RTP transmission and exact microphone retirement; reject malformed input formats and stream declarations.
- Validate authenticated audio packet headers and payloads before allowing microphone activation.
- Export bounded microphone format, capture and transmission diagnostics in uploaded logs.
- Addresses a wired input-format incompatibility identified in representative iOS firmware. Actual phone audio routing, navigation-volume selection, calls and Siri still require vehicle confirmation; this release does not claim those issues resolved.

### Video defaults and guidance
- Fresh CarPlay settings use Automatic codec (H.264 plus decoder-qualified HEVC) and automatic 1440p-class detail, retaining bounded lower-detail fallbacks.
- Preserve existing saved codec, resolution and manual-mode choices; invalid codec data falls back to H.264.
- Recommend Automatic codec with 1440p for manual setup and clarify protocol-specific video and curved-screen guidance.

### Distribution
- Deliver the same runtime revision and version to OpenAutoLink and OpenAutoLink Test Group 2, with separate package identities and tester lists.
- No Android companion update is required for these receiver changes.

## 0.1.517

### Display controls
- Keep floating controls inside the available projection area when switching between fullscreen and visible system UI.
- Recover previously saved off-screen positions automatically, without clearing settings or phone trust.
- Preserve dragged positions across layout changes while keeping untouched controls in their normal stack.

### CarPlay audio
- Correct output-purpose attributes for admitted alternate/navigation, phone, assistant and alert audio; preserve the existing media path.
- Add bounded audio-owner and device-wide playback-route diagnostics to distinguish OAL playback from other vehicle audio sources.
- These corrections do not yet establish the cause or resolution of the reported GM navigation-volume behavior. Vehicle confirmation is still required.

### Documentation
- Showcase CarPlay Dashboard in fullscreen and with the vehicle system bar visible.

## 0.1.516

### Phone projection
- Add native USB Apple CarPlay alongside wireless/WPP and USB Android Auto in the existing OAL application.
- Provide independent normal connection modes. USB phone attachment during projection offers Switch / Stay here; a takeover is temporary and does not change saved modes.
- Provide Retry USB recovery and guarded, awaited retirement of the previous projection owner.
- Support qualified H.264/HEVC video, two-contact gestures, audio and media/artwork metadata through the existing vehicle host.
- Add window-aspect fitting for standard detail tiers and independent manual control-safe calibration in the actual projection rectangle.
- Prefer H.265 and 1440p-class Android Auto Auto tiers while retaining saved/manual choices.

### Reliability and security
- Validate binary-plist trailer dimensions and object bounds before allocation; bound decoding and reject cyclic references.
- Retain exact resources across cancellation and teardown failure; acknowledge receiver retirement only after media owners exit.
- Preserve independent Android Auto preferences, explicit saved CarPlay disable choices and malformed-storage fail-closed behavior.
- Keep private accessory credentials out of Git and public GitHub artifacts. Maintainer Play builds inject them locally; other builders provide their own compatible identity.
- Include the CarPlay receiver in CI and verify public APKs contain no private accessory assets.

### Availability and limitations
- USB CarPlay is supported. Wireless CarPlay remains a development option and is not working in the maintainer's vehicle.
- Music playback quality and cluster route guidance remain under refinement; vehicle behavior varies by platform and phone.
- USB handoff, safe calibration and system-bars-visible behavior require continued vehicle validation. Frame-rate settings are caps, not sustained-rate guarantees.
- OAL is not an Apple-certified product. See the README and authentication/build policy for requirements and limitations.
