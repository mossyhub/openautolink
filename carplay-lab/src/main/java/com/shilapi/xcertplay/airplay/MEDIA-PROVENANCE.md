# Media receiver handoff

> Audio closure update: the PCM-only audio statements below describe the initial handoff, not the current implementation. See `carplay-lab/AUDIO-OUTPUT.md` for platform Opus/AAC-LC/AAC-ELD output, device-filtered wireless offers, parent-owned startup/timing integration, runnable tests and the explicitly unexecuted JVM/device gates. Microphone and buffered103 remain unimplemented.

Pinned source: DiPlay / shilapi/xcertplay commit `e2fd8eadf5a6b705666f292055e72aa93387533d` (local `/hermes_work/research/DiPlay`). GPL-3.0-only; existing `UPSTREAM-LICENSE` and airplay `LICENSE` apply. No keys/certificates/device files imported.

## Public API and integration order

`com.openautolink.carplaylab.media.ProjectionReceiver(context, btMac, surface, mfi, onStatus): Closeable` exposes `start(peerIp)`, `port`, `pairingId`, `publicKeyHex`, and `touch(action,x,y,width,height)`.

1. Stop/close the pairing probe before constructing the receiver. It opens **exactly** `PairingPersistence(File(context.noBackupFilesDir, "pairing-probe"))`, with the same identity and single controller. An absent state file is rejected **before** opening persistence; a locked/corrupt state fails closed. No replacement identity or second media state store.
2. Construct after `SurfaceHolder.surface.isValid`. Receiver borrows the Surface and MFi authenticator; parent owns them. Configure `setRuntimeIdentification(exactPhoneActivationIdentification)` **before** start. It checks BT MAC and strips unsupported location/vehicle capabilities. Default identity strings match the standalone bench and use only an actually readable current Wi-Fi SSID; unknown SSID is not replaced with a fake SSID. Explicit configuration is preferable.
3. `start(selectedNumericPhoneIp)` binds an OS-selected port. There is no advertised endpoint before this succeeds. Repeated start permits only the same selected peer.
4. Construct the existing `EndpointOffer` using the real bound `port` and exact `pairingId/publicKeyHex`; install `receiver.setRuntimeReply(offer::replyTo)` before triggering PhoneActivation. This responds to actual runtime iAP2 0x4300/0x5702 requests using the parent's real endpoint configuration. Nothing logs credentials or frame bodies.
5. Parent exposes 1280x720 H264/30fps SurfaceView; remove opaque settings covering it. Touch coordinates must be relative to the **actual video render rectangle**, with width/height of that rectangle (remove letterbox offsets in UI). Single-pointer DOWN/MOVE/UP/CANCEL maps to encrypted upstream HID event commands. Keep touch forwarding off the UI thread if network backpressure matters; the upstream event write is synchronous.
6. Stop button and `surfaceDestroyed` call `close()`. Receiver also detects invalid Surface at decoder-worker polling. Terminal peer transport/protocol errors still retire the exact session. There is **no projection-duration deadline**; the only 30s control deadline applies to unverified handshakes. Close releases pairing storage asynchronously only after every admitted control worker has exited; immediate reopen may briefly fail with the existing exclusive state lock.

## Implemented closure

- Sanitized upstream AirPlaySession: PairSetup/PairVerify/MFi SAP; encrypted RTSP control; event channel/HID; stream setup/teardown; NTP feedback; bounded incomplete plaintext/ciphertext. All raw debug/trace/Log exports removed rather than filtered after logging.
- ScreenStream: upstream 128-byte screen header, ChaCha20-Poly1305, avcC, length-prefixed NAL conversion. Main screen only; HEVC and cluster rejected. Bounded 8MiB access units and 24 video queue entries; overflow drops reference chain and requests the real upstream `forceKeyFrame` event.
- AndroidMediaSink: platform H264 MediaCodec onto supplied Surface; first-frame proof uses **MediaCodec.OnFrameRenderedListener**, not network receipt or successful configure. 16-bit network-endian LPCM -> little-endian AudioTrack on bounded per-stream workers; partial writes handled. Focus requested/abandoned without microphone permission or audio-mode changes.
- AudioStream: upstream AEAD RTP framing; exact negotiated PCM bits validated **before** fallback codec mapping can run. Only types 100/101 offered/accepted. AAC, Opus, buffered 103, microphone, cluster, and video-in-car are not advertised by receiver config.
- Real type-130 encrypted iAP2 DataStream + full-duplex AirPlayIapTunnelStream -> existing Iap2Session.openTunnel, identification/MFi authentication and actual five upstream subscriptions. Runtime polling persists without timeout after successful startup. Actual endpoint requests may be answered via the parent's EndpointOffer callback.
- Control admission: exact selected numeric peer, two live sockets and at most 32 accepted sockets per receiver lifetime. Media engine permits one exact session owner. Screen/event/tunnel TCP and audio/NTP UDP filter the same phone IP. IP is not authentication: successful PairVerify required before SETUP/RECORD/TEARDOWN.

## Build dependency handoff (parent owns changes)

No custom codec JNI is needed for this **H264 + LPCM** closure. Android supplies MediaCodec/AudioTrack. Existing BouncyCastle pairing/ChaCha fallback dependency and existing iAP2/MFi/transport sources suffice; no entire DiPlay controller, GM, BYD, native microphone or video-player dependencies imported. Requires platform APIs already covered by OAL minSdk (AudioFocusRequest >=26; OnFrameRenderedListener >=23).

Do not turn on AAC/Opus/buffered/microphone/HEVC offers merely because upstream enums/helpers retain them. AAC and Opus renderer/native availability were deliberately not claimed. MFi external service/coprocessor remains the real parent-provided authenticator, not a new endpoint.

## Verification and remaining runtime caveats

No Android builds/device operations performed, as requested. `python carplay-lab/src/test/media_contracts.py` was RED before files/capability adaptation, GREEN afterward: five source/privacy/integration guards. These are **not** decoder or protocol execution proof.

Parent should run unfiltered module JVM tests plus app `carplayBench` compile/lint: new `MediaPolicyTest` (3 tests), `LabMediaFormatsTest` (5 tests), and `LabMediaTransportTest` (2 real loopback AEAD/peer-filter tests) are executable JUnit tests. Those JVM tests were not executed here (no local JDK, no child builds).

Runtime acceptance still needed: phone enters CarPlay; actual rendered frame counter advances; touch affects phone UI; `audiotrack-written` increases and human confirms audibility. AudioTrack writes are not proof speakers are audible. First-frame callback confirms platform rendering but not panel visibility beneath overlays. No reconnect automation, clock-scheduled PCM jitter-buffer, mic/Siri/telephony uplink, artwork/UI metadata, VHAL/location, cluster or video-in-car support. runtime `0x4e0e` is forwarded to the endpoint reply callback without interpreting or logging transport identifiers; the default `EndpointOffer::replyTo` does not respond to it. If this phone requires the upstream Wi-Fi-credential resend on the tunnel, parent must return the same real `Iap2WirelessMessages.accessoryWiFiConfiguration(...)` reply from that callback for `0x4e0e` (no fictitious endpoint). This narrow runtime loop is not the entire DiPlay wireless controller.
