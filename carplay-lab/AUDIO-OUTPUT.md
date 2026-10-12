# Experimental wireless audio OUTPUT handoff

Scope: `carplay-lab` only. No production variant, microphone/uplink, video, startup/session/timing edits, persistent state, builds, device commands or CI actions. No new dependency or JNI library.

## Sources and implementation

- Pinned DiPlay `e2fd8eadf5a6b705666f292055e72aa93387533d`, `shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt:830–1004,1182–1269`: platform decoder -> PCM -> AudioTrack, raw one-access-unit payload after the fixed authenticated 12-byte header, ASC/OpusHead. Narrow adaptation retains GPL-3.0-only headers and existing licenses. No raw upstream diagnostics imported.
- Pinned independent Showcase review, `/hermes_work/research/carplay-media-source-review/showcase/source/carplay_services.m:1057–1140,3033–3059,4565–4569`: wireless Opus interactive 100/101, AAC-LC stereo media 102, precise bit masks and negotiated clock rates, `spf` defaults (AAC-LC 1024, AAC-ELD 512). Type 103 intentionally omitted here.
- Pinned OCBM review, `/hermes_work/research/carplay-media-source-review/ocbm/crates/vendor/receiver/src/info.rs:1332–1343,1400–1429`: exact AAC-ELD 16/24 kHz mono and 44.1/48 kHz stereo bits; ELD interactive and LC media contracts. Rows combine these supported alternatives, not either reference's microphone claims.
- Android MediaCodec API: https://developer.android.com/reference/android/media/MediaCodec . Platform capability/profile/rate/channel enumeration precedes offers; creation/configuration is confined to the audio worker. PCM output format changes determine actual AudioTrack rate/channel count; PCM16 is required.
- AndroidX Media3 `OpusUtil` (API/source corroboration, not transplanted code): https://raw.githubusercontent.com/androidx/media/release/libraries/container/src/main/java/androidx/media3/container/OpusUtil.java . Opus decoder PCM rate 48000, native-endian eight-byte delay/pre-roll CSD, 80ms pre-roll. Live RTP has no container-supplied preskip; this receiver uses zero, not an invented 312-sample Ogg preskip.
- ELD ASC syntax corroborated against FFmpeg `libavcodec/mpeg4audio.c` and `libavcodec/aac/aacdec.c::decode_eld_specific_config` at https://github.com/FFmpeg/FFmpeg/tree/master/libavcodec . Escaped AOT39, sampling-frequency index, channels, 512/480 frame flag, resilience/SBR disabled, ELD extension terminator, epConfig0. No FFmpeg decoder or code imported. AAC is raw AU + ASC, `is-adts=0`; an LC ADTS wrapper cannot describe ELD.

## Available closure

`AudioOutputSupport` is Android-free: exact single-bit mapping, codec/stream/audioType admission, frame-size validation, CSD construction, fixed-header payload extraction/PCM swapping, ordered output-only wireless rows. No permissive PCM fallback remains in `AudioStreamCodec`.

`AndroidAudioDecoderSupport` caches one immutable device inventory: decoder (not encoder), explicit AAC profile2/39 support, exact MediaFormat sample/channel support. /info and Android sink admission use the same inventory; each worker creates that exact decoder name. Missing enumeration/profile/format support produces no offer, never a MIME-only assumption. Discovery is not a successful decoder configuration or audible-playback claim.

Supported compressed selections:
- AAC-LC: 44.1/48 kHz stereo, type102 `media`, 1024 samples/frame.
- Opus: mono type100/default/telephony (16/24/48 kHz clocks), type101/default, type100/alert (48 only), speechRecognition (24 only). Decoder PCM is 48 kHz; `AudioFormat.rtpClockRate` retains the negotiated clock separately.
- AAC-ELD: 16/24 kHz mono default/telephony/speechRecognition; 44.1/48 kHz stereo alert and type101/default. 512 samples default; `spf=480` supported; any other specified AAC frame size or nonintegral value is rejected. No ELD SBR/resilience, mono44/48/32, HE-AAC, ALAC or unspecified codecs.
- Explicit `lpcmOnly=true` preserves the old output-only wired/compatibility offer. The wireless table has no PCM or compatibility rows. Both LC sample rates are offered when actually available rather than forcing the entertainment preference.

Workers: maximum eight live/retiring audio owners per sink; queue64, payload bound4096 bytes including RTP header, one input AU per packet. Decoder create/configure/start/feed/drain/stop/release and AudioTrack create/write/stop/release stay on the same worker. Codec input wait50ms, nonblocking PCM backpressure250ms, retirement join500ms per owner. A platform create/configure/release stall cannot be forcibly cancelled; that owner remains counted and blocks replacement until its finally retires. Close stops admission immediately. No RTP reordering, retransmission/PLC, jitter scheduling or long-session clock synchronization is added. Output latency0 is the reference table placeholder, not a measured physical latency. Positive AudioTrack bytes remain transport proof only; human audibility must be checked.

Microphone is never offered, even if an unrelated caller sets `microphone=true`; no audioInputFormats or vocoderInfo, capture permission or uplink exists. No buffered103 offer.

## Parent integration and verification

1. Parent changes `ProjectionReceiver.kt` construction from `lpcmOnly=true` to `false`. Child deliberately did not touch startup. Without that change the bench still advertises PCM-only.
2. Parent timing owner should use `AudioFormat.rtpClockRate`, not decoder `sampleRate`, for negotiated feedback/sampleTime. Child deliberately preserved feedback/timing code.
3. Run focused JVM tests and then the unfiltered module/app gates on the host (no concurrent child build):

   `./gradlew :carplay-lab:testDebugUnitTest --tests com.shilapi.xcertplay.media.AudioOutputSupportTest --tests com.openautolink.carplaylab.media.MediaPolicyTest --tests com.shilapi.xcertplay.airplay.LabMediaFormatsTest`

   Existing JUnit4.13.2 dependency suffices. `src/test/media_contracts.py` remains a source/privacy guard, not JVM/device proof, and was updated for nonblocking AudioTrack output.
4. Physical acceptance: actual negotiated codec/type; `audio-decoder-configured`; decoded positive `audiotrack-written`; human-confirmed sound from TCL; teardown/reconnect does not leak owners. Do not infer microphone or bidirectional Siri/call support.

Tests were authored before their implementation. The initial existing-API RED handoff was the changed `MediaPolicyTest.acceptsOnlyExactSupportedPcmNegotiation` assertion: type102/0x800000 must be accepted (old implementation returned false). A parent RED request was issued before implementation, but no parent RED/GREEN output was received here. No Gradle/JVM/build/device/CI execution was performed by the child, so this handoff is **not a witnessed RED/GREEN claim**. Pure Python bit packing independently checked ASC golden values (`f8f02000`, `f8f03000`) and expected offer masks; that is not execution of Kotlin tests. The existing no-build source/privacy guard `python carplay-lab/src/test/media_contracts.py` was executed after adaptation: 5 tests passed. It does not compile Kotlin or exercise MediaCodec.
