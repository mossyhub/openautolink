# Compressed CarPlay microphone handoff

## Scope and provenance

Experimental `carplay-lab` / app `carplayBench` only. The capture, platform-encoder and sealed RTP contract is adapted from DiPlay commit `e2fd8eadf5a6b705666f292055e72aa93387533d`:

- `shared/src/main/java/com/shilapi/xcertplay/airplay/MicrophonePacketizer.kt` (the actual packetizer is in `airplay`, not `media`).
- `shared/src/main/java/com/shilapi/xcertplay/media/{MicrophoneUplink,OpusEncoder}.kt`.
- `shared/src/main/java/com/shilapi/xcertplay/airplay/CarPlayMediaEngine.kt:537-590`.

Default input is implemented as actual MIC capture; unlike the pinned engine's default omission it is not an advertised-but-discarded input. Normal calls now use owner-vehicle-verified VOICE_RECOGNITION at 48k two-channel AVERAGE, converted to the unchanged negotiated mono format without arming. Siri retains its existing VOICE_RECOGNITION behavior. Manual frozen experiments override normal capture; disarm/reconnect restores the production call profile. This is not universal GM proof or echo/noise acceptance. Exact two-channel initialization failure removes call inputs; no mono/runtime-silence fallback is added. See `docs/microphone-diagnostic.md` for current policy; the remaining sections record the original compressed-uplink integration. There is no microphone-capture requirement for establishing video/output projection and no AA production connection-loop change.

## Exact supported device contract

- RECORD_AUDIO must be explicitly granted using the optional bench button. It is not included in the mandatory Bluetooth/location permission list. The Android microphone privacy switch, physical route, OS focus/capture concurrency and permissions can still prevent actual recording.
- Input hardware inventory must expose an actual microphone route. Each advertised source must initialize an AudioRecord at 48kHz, mono, PCM16. Capability probing does **not** start recording.
- The named codec must be an encoder from the real `MediaCodecList`, expose `audio/opus`, accept the exact media format and successfully configure/start/release. Decoder availability is not encoder evidence. Configure/start is not proof of encoded samples or audibility.
- 48kHz mono capture always uses 960 samples / 1920 PCM bytes / 20ms. The 24k Siri format `0x20000000` uses a 480-sample RTP clock step and 64kbps encoding; `0x40000000` uses 960 and 96kbps. No 16k input, LPCM input, AAC input or buffered103 input is offered.
- Encoded Opus TOC duration must actually equal 960 samples at 48kHz before it is transmitted. Codec config buffers are never sent as microphone access units; access-unit size and drain count are bounded. Original output PTS preserves RTP gaps when delayed frames are dropped.
- Input formats are intersected with the same type100 output row and that row's independently probed source capability. They augment default/telephony/speechRecognition rows rather than add redundant input-only rows. AAC-ELD-only output rows do not acquire false Opus input capabilities.
- `vocoderInfo` remains unadvertised: compressed input alone does not implement a sourced vocoder control endpoint. Pinned DiPlay's unconditional extended-feature string is not endpoint evidence.

## Ownership / transport

Capture starts only on the first authenticated downlink RTP packet of the exact negotiated type100 stream, matching the pinned capture activation contract. No standalone input-only stream is claimed. The engine qualifies callbacks against the exact current receiver; microphone ownership includes session/type/audioType and a replacement generation. Sink and global physical ownership fences retain retiring workers, including across sink replacements. AudioRecord.stop unblocks recording reads; codec/release stays with its owning worker. At most four encoded frames are pending. Phone feedback/call mode and human audibility are separate validation gates.

The destination address comes exclusively from PairVerify's authenticated session, and `dataPort` must be an exact integer 1..65535. HKDF-SHA512 uses `DataStream-Salt` + unsigned decimal streamConnectionID and `DataStream-Input-Encryption-Key`. Wire layout is clear 12-byte RTP header with payload type100, timestamp+SSRC bytes4..11 as AAD, raw Opus encrypted by ChaCha20-Poly1305, 16-byte tag, trailing 8-byte little-endian nonce counter. Randomized initial counters avoid knowingly restarting nonce0 under an unchanged connection key. Raw audio, payload prefixes, keys and unsanitized exception messages are not logged.

## Verification boundaries

Tests were written before these helpers. The runnable Python source guard was observed RED for the missing encoder and GREEN after implementation/reconciliation. **This is source checking, not Kotlin execution or device proof.**

`MicrophoneContractTest` supplies runnable JUnit checks for exact capability/permission policy, source mapping, packet duration, port/clock rejection, sealed packet AEAD/tamper/counter behavior and real UDP loopback. Existing output-only tests explicitly pass `MicrophoneCapability.NONE` so a test cannot accidentally invoke device probing. Parent has additional capability/owner/packetizer tests.

No Gradle build, Kotlin compilation, package installation or device operation was run by this worker. Parent must compile and execute tests with the existing JUnit4/BouncyCastle dependencies, then verify real codec configure/encoded bytes, capture route/privacy switch, peer packet receipt, Siri understanding/call microphone audibility and repeated setup/teardown retirement. There is still no causal proof that missing microphone offers caused the original absence of phone media SETUP.

Parent owns ProjectionReceiver / receiver-config / Bluetooth handoff integration. The engine's microphone switch defaults false, so parent must explicitly pass the consent/capability-qualified value; `/info` and admission must use the same capability snapshot.

## Exact parent integration

1. On the existing receiver worker (not Android's UI thread), before constructing the receiver configuration, call `val micCapability = AndroidMicrophoneSupport.probe(context)`. This performs real RECORD_AUDIO, input-device/AudioRecord-source, named Opus encoder configure/start/release checks without starting recording. `initialize(context)` in AndroidMediaSink preserves this cache for the same application context.
2. Set `AirPlayConfig.microphone = micCapability.available` (also respecting any explicit user opt-out). Keep false for pairing-only, `lpcmOnly`, or disabled audio output. Do not use AndroidAudioDecoderSupport to gate this flag. Parent owns any BenchReceiverConfig signature/test change.
3. Construct `CarPlayMediaEngine(sink, microphoneEnabled = config.microphone)`. Omitting this argument deliberately disables capture even if the /info flag is true. The existing AirPlaySession calls to `AirPlayInfoPlist.build(config)` pick up the cached, permission-rechecked per-source capability; tests can pass `MicrophoneCapability.NONE` or an explicit fixture as the third argument.
4. Use the optional BenchActivity microphone consent button, then reconnect. Do not silently grant permission, include it in required Bluetooth permissions, or start capture on Activity creation.
5. Parent's serialized host Gradle gate: `:carplay-lab:testDebugUnitTest --tests '*Microphone*'`, followed by unfiltered `:carplay-lab:testDebugUnitTest` and the normal carplayBench build. JUnit4 4.13.2 and BouncyCastle 1.79 are already declared; no new dependency is required.

Primary source paths (repository-relative):

- `carplay-lab/src/main/java/com/shilapi/xcertplay/airplay/MicrophonePacketizer.kt`
- `carplay-lab/src/main/java/com/shilapi/xcertplay/airplay/CarPlayMediaEngine.kt`
- `carplay-lab/src/main/java/com/shilapi/xcertplay/airplay/AirPlayInfoPlist.kt`
- `carplay-lab/src/main/java/com/shilapi/xcertplay/media/OpusEncoder.kt`
- `carplay-lab/src/main/java/com/shilapi/xcertplay/media/MicrophoneUplink.kt`
- `carplay-lab/src/main/java/com/shilapi/xcertplay/media/MicrophoneSupport.kt`
- `carplay-lab/src/main/java/com/shilapi/xcertplay/media/MicrophoneCapability.kt`
- `carplay-lab/src/main/java/com/shilapi/xcertplay/media/MicrophoneOwnerSlot.kt`
- `carplay-lab/src/main/java/com/shilapi/xcertplay/media/AndroidMicrophoneSupport.kt`
- `carplay-lab/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt`
- `carplay-lab/src/main/java/com/openautolink/carplaylab/BenchActivity.kt` (optional consent only)
- `carplay-lab/src/main/AndroidManifest.xml` (RECORD_AUDIO and optional microphone hardware only)

Runnable tests: `carplay-lab/src/test/java/com/shilapi/xcertplay/airplay/MicrophonePacketizerTest.kt`, `carplay-lab/src/test/java/com/shilapi/xcertplay/media/{MicrophoneContractTest,MicrophoneSupportTest,MicrophoneOwnerSlotTest}.kt`; source-only guards `carplay-lab/src/test/{microphone_source_guard,media_contracts}.py`.

Reconciliation note: the earlier child continued writing during this continuation. Its valid global physical-owner fence, manifest/consent changes, source-device filter, provenance and existing output-test updates were retained. The generation/owner-slot, exact capability catalog, bounded queue, packetizer tests and no-vocoder contract were merged with those writes. Neither worker's source guards should be reported as executed Kotlin tests.
