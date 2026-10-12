# Recovered audio audit: startup comparison

The read-only audio worker exited without a terminal verdict. Parent recovered its transcript, which contains inspections but no validated root cause. No completion claim is accepted.

Parent privately parsed complete plist command bodies from the successful owner-confirmed DiPlay capture build/diplay-control-20261009-122418.log. Recovered command names: setNightMode, modesChanged, disableBluetooth, iAPSendMessage, forceKeyFrame. No requestUI command recovered; one incomplete plist body remains, so absence in parsed capture alone is not universal proof.

Cloned DiPlay CarPlayController.kt:287-305 makes the session active without sending requestUI. CarPlayHostActivity.kt onSessionActive and night-mode handling likewise need comparison with OAL's added CarPlayStartup coordinator. OAL AirPlaySession currently sends requestUI immediately after RECORD reply/event readiness, a bench addition originally based on independent receiver examples rather than the working DiPlay lifecycle.

This is an actual startup-policy difference worth discriminating after the current display/settings writers settle. It is not established as the reason for MainAudio remaining accessory-owned, and no startup change or device test has been performed for this hypothesis yet. Preserve real output/microphone capability gates and existing authenticated identity.

Display/settings workers retain exclusive file ownership. Parent owns subsequent combined integration, unfiltered builds and tablet testing. No production or Play upload.
