# Native OAL CarPlay first rendering — 2026-10-09

Experimental `com.openautolink.app` carplayBench branch; no production/Play mutation.

## Controlled change and physical result
- Real DiPlay control succeeded on same iPhone/TCL/home Wi-Fi. Owner additionally confirmed sound through the TCL.
- OAL failed type130 pre-TCP while /info sourceVersion was `1.0`. Changed ONLY ProjectionReceiver /info config to reference `950.7.1`; capture `build/media-attempt-20261009-114807.log` then shows tunnel TCP, runtime identification/authentication, screen110 SETUP, H264 config and first frame at11:48:12.659. Screenshot shows real CarPlay app grid inside OAL.
- This establishes a causal candidate by a single-change positive test, not the exact iOS version-check mechanism or threshold. Crypto/tunnel derivation was unchanged.
- Extracted real receiver config and shared compatibility constant; a regression exposed StartSession still advertising1.0, corrected shared bootstrap/probe/info declaration. Final unfiltered suite77 tests, zero failures/errors/skips; lint0errors; bench assembly passes. This final consistency refactor has not yet been redeployed; installed preceding build contains the successful /info version and touch fix.

## Touch and session
- First attempt disconnected when input sent from Android main; close caller was sendCommandLocked. Moved touch socket writes to a bounded64-entry single worker, guarded by exact captured active session. Contained terminal close errors at ownership boundaries.
- `build/media-attempt-20261009-115655.log` has real touch TX and response; `build/media-screen.png` shows Now Playing replacing app grid after touch. Session alive through subsequent playback input beyond60seconds; no touch-triggered crash in that build.
- Removed speculative timing gate: phone successfully projects with one matched timing reply and synced=false at RECORD. Previously requiring2 within4s produced500 and prevented startup. Retained sanitized timing diagnostics/loopback tests.
- Removed30minute media bench timer; explicit stop/lifecycle ownership remains.

## Audio limitation
- TCL exposes all implemented compressed decoder formats and OAL offers6 output rows, but Play input has not produced any audio SETUP100/101/102 or AudioTrack write evidence.
- Owner explicitly observed music playing on iPhone speaker, not TCL. This is a routing/negotiation gap; do not call OAL audio working from changing Now Playing UI.
- Source-based audit confirms initial MainAudio resource declaration matches working reference; output-only catalog differs from complete bidirectional default/telephony/Siri contract. Mandatory microphone gate for media remains unproven.
- Implemented requested bootstrap handoff: actual `disableBluetooth` observed at12:03:50.346; runtime authenticated12:03:51.148; exact bootstrap retired12:03:51.238. Video/touch survive, but no audio SETUP in `build/media-attempt-20261009-120346.log`. This does not resolve phone-speaker routing.
- Genuine permission/capability-gated Opus microphone implementation dispatched for complete audio contract and eventual Siri/calls. Do not advertise input until actual capture/encoder path exists; causality remains unproven. No false microphone/input offers introduced.

## Product direction
- User wants automatic iPhone→CarPlay and Android→AA with WPP default, no manual protocol/mode choice in ordinary use, shared uncluttered settings with contextual protocol advanced options.
- Assess removal of legacy wireless AA modes but do not assume USB is obsolete from wireless compatibility changes.
- CarPlay protocol supports H264/HEVC; current OAL lab H2641280x720 only. Official control H2642000x1200 proven. Higher resolution/HEVC selection requires actual capability/config/renderer implementation, not advertisement alone.
- Dual-protocol product/settings design is being authored against actual source; not implemented UI or seamless discovery proof.

## Private evidence
Captures and screenshots remain under ignored build directory; screenshots may contain media metadata. No credential/crypto publication. HomeIPv4 proof does not establish GM AP scopedIPv6 ingress or car audio/policy behavior.
