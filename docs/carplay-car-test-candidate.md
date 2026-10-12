# CarPlay car-test candidate 0.1.509

> **Historical handoff:** This records an earlier development stage, not current setup guidance. USB CarPlay has since shipped; the former default-off/manual-enable rollout is superseded by the [current shipped policy](usb-hotplug-independent-protocol-modes.md#current-shipped-carplay-policy) and [CarPlay setup](../README.md#carplay-setup). Saved disabled choices are still respected. Wireless CarPlay remains in development.

## Verified

- Signed automotive AAB: host `/mnt/Temp/Hermes_Work/oal-carplay-integration/app/build/outputs/bundle/release/app-release.aab`; versionCode 509 / versionName 0.1.509. Shared signing tool reported Signature OK. Both `base/assets/offline-mfi/identity.pk8` and `certificate.p7b` verified present by archive entry names only; no credential contents exported.
- Combined tests: app 945 tests, zero failures/errors, one skipped; receiver 222 tests, zero failures/errors. Receiver lint gate successful.
- Updated real OAL tablet APK installed and launched. Existing trusted phone reconnect passed PairSetup and PairVerify, rendered CarPlay, and a touch switched dashboard to Maps.
- Updated build received AAC media format 8388608/type102, gained audio focus, and wrote PCM to AudioTrack. Owner explicitly confirmed: "Audio worked". Capture is private at profile scratch `oal-final-audio.log`.
- Private build-time credential injection and automatic validated installation implemented. Credentials are excluded from Git/source CI, but extractable from delivered binaries. Documentation discloses non-certification, upstream-stated provenance and unresolved general distribution suitability.

## Intended first car test

This is a vehicle-test candidate, not proof of GM CarPlay compatibility. At this candidate’s development stage, CarPlay was off and required manual enablement; that rollout is now superseded. The wireless-only test procedure was to configure the real head-unit Bluetooth MAC and car AP information, enable the candidate’s CarPlay option, use GM Bluetooth pairing and iPhone CarPlay consent, then attempt projection. Native Bluetooth/iAP2 AP credentials and scoped IPv6 endpoint exchange are preferred. Manual AP join/numeric peer fallback does not bypass blocked phone-to-head-unit IPv4.

## Vehicle validation outstanding

- Ordinary-app visibility/binding/phone ingress on the actual GM AP scoped IPv6 interface and native AP join/startup.
- Fresh phone enrollment and returning-phone/ignition behavior in the vehicle; tablet validation used existing saved trust.
- Vehicle day/night, requested GNSS and sparse EV data transmission/phone consumption.
- Vehicle audio routing, microphone/Siri/calls and physical auto-scale acceptance.
- Automated GM-selected phone bootstrap versus explicit Connect action requires vehicle evidence; no seamless automatic selection claim.

No Play upload, source commit/merge or release publication is established by these verification results. Final signing artifact is a local candidate; Play state must be read back after any authorized upload before claiming availability.
