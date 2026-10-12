# CarPlay HEVC receiver handoff

## Outcome and outstanding integration boundary

Recovered surviving codec work without replacing it. Focused receiver tests pass (55 tests, zero failures/errors); `:app:compileDebugKotlin` succeeds. **This is not yet full-app HEVC functionality or physical-phone proof.**

Codec worker owns only ScreenStream, MediaCodecSupport, AndroidMediaSink, AirPlaySession, AirPlayInfoPlist, CarPlayVideoQualification and codec tests. No app UI/display policy changes, devices, uploads or commits.

**Integration worker must remove the surviving AVC-only guard in `CarPlayMediaEngine.kt` onCodec:** replace `require(codec == VideoCodec.H264)` with `require(session.acceptsVideoCodec(codec))` before `sink.onVideoCodec(type, codec)`. Without this change the actual engine rejects HEVC even though sink tests pass. This file is outside codec worker ownership.

Pass the **same immutable** `CarPlayVideoSelection` to `AndroidMediaSink(videoSelection=...)` and every `AirPlaySession(videoSelection=...)`; forwarding through ProjectionReceiver/AirPlayReceiver is integration-owned. As last inspected these production call sites do not yet forward a HEVC receipt. Keep `AirPlayConfig.hevc` false until the actual engine guard and receipt forwarding are installed together. Explicit prefer-HEVC-with-AVC-fallback is then allowed; default/null receipt remains AVC. A qualified decoder name is capability evidence, not successful physical decode.

## Stable APIs

Package `com.shilapi.xcertplay.media`:
- `VideoDecoderRequest(codec: VideoCodec, width: Int, height: Int, fps: Int)`.
- `fun interface VideoDecoderProbe { fun decoderFor(request: VideoDecoderRequest): String? }`.
- `CarPlayVideoQualification.select(width, height, fps, preferHevc=false, probe=androidProbe()): CarPlayVideoSelection`.
- `CarPlayVideoSelection(width, height, fps, avcDecoderName, hevcDecoderName: String?=null, usedFallback=false)`; `.hevcOffered`, `.decoderName(codec)`, `.matches(AirPlayConfig)`.
- Appended named `AndroidMediaSink.videoSelection` defaults to the existing display AVC receipt; `videoCodecFactory` is an injectable exact-name Android codec factory for JVM tests.
- Appended named nullable `AirPlaySession.videoSelection` and `AirPlayInfoPlist.build(..., videoSelection=...)` default null; no accidental HEVC offer.
- `AirPlaySession.acceptsVideoCodec(codec)` accepts AVC while live, HEVC only after qualified proposed/enabled SETUP `hevc`; closed sessions reject both.

Exact qualification accepts positive even dimensions and integer maximum FPS 1–60 (product ceiling, not a universal protocol limit), retains exact AVC decoder name and optionally a HEVC Main decoder at that same size/rate. Requested AVC failure probes only 1280×720@30 AVC; absence of that baseline fails closed. No HEVC-only mode. Physical millimeters/autoscale stay in integration-owned display policy and remain independent of codec selection/coded crop.

`hevcInfo:{}` requires config.hevc plus matching dimensions/FPS/no cluster plus qualified optional HEVC name. SETUP enables `hevc` only when additionally proposed by the phone; it is not an extendedFeatures token or bitmap bit. Actual in-band avcC/hvcC selects decoder MIME, not preference. AVC remains admitted after HEVC negotiation.

## Receiver behavior and fixes

- Bounded hvcC parser retains complete VPS/SPS/PPS only and emits Android Annex-B `csd-0`; records missing a chain, truncations, trailing bytes or mismatched NAL headers fail closed. Real generated SPS RBSP parsing checks dimensions/conformance crop/profile/tier/level/chroma/bit depths against the advertised selection and hvcC record. Standard Main/Main10/MainStill configurations are mapped; exact incoming profile/level/size/rate capability checks run before configure. Range extensions are rejected. The initial optional offer probe requires Main, not a Main10-only inventory.
- Preserved real ffmpeg/libx265 1280×720@30 generated hvcC and libx264 avcC fixtures. These contain no phone capture/identity/credentials. Generated CSD is parser/configuration evidence, not Android hardware decoded pixels.
- Screen transport preserves AEAD/header/counter behavior and validates declared 1/2/4-byte NAL lengths. Config box size excludes following sibling boxes. Fixed raw valid hvcC being mistaken for a container when legal SEI bytes happen to spell `avcC`; raw validated records now precede fourcc search.
- Sink creates the exact qualified decoder name, configures actual incoming MIME/profile/level/CSD, starts/releases on the video owner, supports HEVC→AVC replacement, rejects stale render callbacks, and labels numeric output diagnostics with actual H265 rather than the display policy's legacy H264 default.
- Fixed unbounded output drain starving queued AVC replacement: each drain services at most 32 outputs. A deterministic busy-output test failed before this change and passed afterward.
- Prior interrupted failure was a stale host XML expecting H265 output diagnostics but receiving H264; the surviving local actual-codec label fix passed after selective sync. No weakening of that test.

## Exact execution receipts

Host: `lance-host`, SSH config `/home/hermeswebui/.hermes/home/.ssh/config`, mirror `/mnt/Temp/Hermes_Work/oal-carplay-integration`. Only owned source/tests were rsynced; no assets/build trees/secrets were copied. Existing gradle.properties used `-Xmx2048m`; it was not edited. Every build used exclusive flock, one worker and bounded override:

```sh
cd /mnt/Temp/Hermes_Work/oal-carplay-integration
flock /mnt/Temp/Hermes_Work/.oal-gradle.lock env \
  JAVA_HOME=/home/lance/.local/jdks/temurin-17 \
  ANDROID_HOME=/home/lance/.local/android-sdk \
  ./gradlew :carplay-lab:testDebugUnitTest \
  --tests 'com.shilapi.xcertplay.media.CarPlay*' \
  --tests 'com.shilapi.xcertplay.airplay.LabMedia*' \
  --tests 'com.shilapi.xcertplay.airplay.CarPlayStartup*' \
  --tests 'com.shilapi.xcertplay.media.AudioOutputSupportTest' \
  --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx1536m -XX:ActiveProcessorCount=2' \
  -Pkotlin.compiler.execution.strategy=in-process
```

Final result: BUILD SUCCESSFUL; XML aggregated by Python: **55 tests, 0 failures, 0 errors**.

| Test class | Tests |
|---|---:|
| CarPlayHevcTest | 10 |
| CarPlayHevcSinkTest | 6 |
| CarPlayHevcSessionTest | 1 |
| CarPlayHevcTransportTest | 1 |
| CarPlayVideoPlatformTest | 3 |
| CarPlayVideoQualificationTest | 5 |
| LabMediaFormatsTest | 5 |
| LabMediaTransportTest | 2 |
| CarPlayStartupTest | 10 |
| AudioOutputSupportTest | 12 |

CarPlayHevcSessionTest exercises actual /info and SETUP serializers, proposed/absent feature and matching/missing/wrong-rate receipts using real loopback event/timing sockets, and closed-session rejection. It invokes dispatch reflectively, not a new PairVerify proof. Each session gets one initial SETUP: repeated initial SETUP encountered existing `NtpClock is already running` behavior; the fixture was corrected rather than expanding protocol scope. Sink codec calls use Android capability predicates under Robolectric plus mocked platform MediaCodec calls, not physical hardware decoding. Transport test uses real loopback + AEAD at all three length widths.

Compilation used the same flock/env/resource flags with `./gradlew :app:compileDebugKotlin`. First run actually compiled app/library and reported BUILD SUCCESSFUL in 1m11s with unrelated existing app warnings; final run after codec changes reported BUILD SUCCESSFUL in 13s, all 33 tasks up-to-date. No APK was generated/uploaded/installed. No full unfiltered receiver/app suite claim.

## Parent-owned acceptance gates still required

1. Install engine negotiation guard and same-receipt production forwarding, then exercise the real receiver constructor/session/sink (not only serializers); verify unqualified HEVC cannot be offered and unsupported negotiation cannot reach the sink. Add live HEVC and AVC-in-band fallback transport tests at this seam. Do not enable the UI HEVC option before these changes land together.
2. On the exact Android target create/configure/start the exact retained AVC/HEVC names using actual phone CSD, feed real access units, observe decoded output/crop and OnFrameRenderedListener callbacks; verify configure failures release their candidate and retirement fences replacement. Inventory alone is insufficient.
3. Parent owns physical iPhone runs. Record phone model/iOS build/transport and four distinct receipts: advertised `/info`/SETUP offer → actual in-band codec/SPS profile/tier/level/chroma/depth/dimensions → exact Android decoder and rendered callbacks → unobscured screenshot plus post-touch UI change and surviving session. Preserve privacy: numeric finite receipts, never raw CSD/plist/identity/key bytes in public diagnostics.
4. Compare default AVC1280×720@30; optional HEVC at the identical profile; actual phone AVC fallback when HEVC is proposed/accepted but AVC arrives; unsupported HEVC omission; integer31/59; standards, custom widescreen/portrait and higher tiers independently. Keep maximum FPS separate from measured moving-content rate; a static UI cannot prove FPS.
5. Preserve automatic physical scaling/HID space and normal existing credential/native enrollment behavior. No extra Pair button, replacement credentials or physical-success claims from JVM tests.

Research provenance: scratch `carplay-video-contract-24A446.md`; DiPlay `e2fd8eadf5a6b705666f292055e72aa93387533d`; independent OCBM `c9a718d0ca49d2f4dff458febc8eb833283a75bd`. Firmware branch and third-party receiver evidence justify conditional implementation, not universal iPhone availability.
