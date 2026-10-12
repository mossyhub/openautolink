# Changelog

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
