# DiPlay control test — 2026-10-09

## Exact control
- Official locally retained DiPlay 0.2.13 APK; package `com.shihab.diplay`, versionCode 32.
- APK SHA-256 `aed9eac786e7c0e80a2929dc2f0c2e1d0df318ca8fd83ae7c2988a98bb4cd7e6`.
- Same TCL 9466X, Android 13/API33, selected iPhone and existing home-LAN Wi-Fi as OAL bench. Same-LAN mode; Wi-Fi details entered privately by owner.
- OAL force-stopped; no OAL storage clearing, private-state migration or uninstall. DiPlay installed alongside OAL.
- DiPlay defaults not forced to match OAL's 1280x720 declarations. Working decode was H264 at 2000x1200; configuration differences remain part of the comparison.

## Observed success
- Wireless session established at tablet timestamp 11:43:15.702; authenticated=true, one inbound control TCP connection, sessionActive=true.
- Type130 tunnel TCP connected at 11:43:15.781; wireless iAP2 tunnel ready at 11:43:16.247.
- H264 decoder `c2.mtk.avc.decoder` configured at 2000x1200 at 11:43:17.911.
- First-frame-rendered diagnostic at 11:43:18.186, supported by screenshot showing real CarPlay Music privacy UI.
- Injected touchscreen taps followed by screenshot show CarPlay app grid replacing the privacy panel: touch-response/UI-navigation evidence.
- No human-audible audio, microphone, calls, Siri, full reconnection or GM-vehicle proof obtained.
- USB iPhone not enumerated at host check; phone-side logs not captured.

## Preserved private artifacts (ignored build directory)
- `build/diplay-control-20261009-114304.log`: successful connection capture. Upstream raw diagnostics may include identifiers/crypto; owner-private only, do not publish/export raw contents.
- `build/diplay-control-screen.png`: visible rendered CarPlay privacy panel.
- `build/diplay-touch-after.png`: rendered app grid after touch commands.
- Initial `build/diplay-control-20261009-114212.log` did not start connection because viewport shifted; not protocol failure evidence. Capture automation was corrected to locate the button from current UI.

## Implication
The phone/tablet/home-LAN path supports native wireless CarPlay with this APK. Immediate OAL type130 teardown is not an inherent failure of this device pair/network. Compare exact APK behavior and configuration with OAL; do not assume checked-out upstream sources exactly equal the release binary. Keep DiPlay and OAL receivers mutually exclusive. Source agreement or shared identity alone does not isolate the causal difference.
