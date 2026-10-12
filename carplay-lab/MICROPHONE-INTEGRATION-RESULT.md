# Experimental microphone integration

## Verified
- Partial delegated source writes were recovered after the worker's terminal-result failure; no completion claim was trusted.
- Platform Opus encoder and capture-source probes, permission-filtered input offers, RTP/AEAD packetizer, session/type/generation capture ownership, and bounded uplink retirement are integrated into the bench receiver.
- Receiver config integration test reproduced disabled microphone despite a successful capability record (3 tests, 1 assertion failure), then passed after wiring the capability gate.
- Full unfiltered receiver suite: 95 tests, 0 failures/errors/skips. Receiver lint: 0 errors. Real carplayBench APK assembles; existing SDK XML compatibility warnings remain.
- Installed and launched updated experimental com.openautolink.app on TCL; package readback confirms RECORD_AUDIO exists and is not yet granted.
- Android's microphone permission dialog is displayed for user consent. No permission granted via adb or automation.

## Not yet verified
- Phone receipt of actual microphone uplink; capability probes alone do not prove capture transport.
- CarPlay audio SETUP and tablet playback: still failing after consent and truthful input offers.

## Post-consent physical test
- RECORD_AUDIO readback granted=true.
- Capture media-attempt-20261009-121948.log confirms microphoneCapability=true after real encoder/source probes, successful phone authentication, runtime handoff and rendered video.
- Still no audio SETUP; only stream130/110. MainAudio resource2 entity2/permanent2 observed in modesChanged.
- Owner confirms music remains on iPhone speaker while official DiPlay played through TCL speaker.
- Missing input capability alone does not explain the failure; remaining catalog/ownership differences require a sourced discriminator.
- Phone receipt or human-audible Siri/call uplink; GM routing/reachability; production integration.

Input catalog omission remains a hypothesis, not a proven cause of phone-speaker playback. No production/Play upload occurred.
