# Capture-side microphone policy diagnostics

These are diagnostics, not a repair for Siri silence. In the 0.1.519 vehicle upload, three speech-recognition attempts completed 341, 315 and 313 frames (969 total), all zero before endian conversion, sealing or UDP. Positive recording state and sends do not establish audible capture or phone receipt.

## Historical Android Auto comparison

The owner reports successful calls and regular Gemini microphone use on this car before CarPlay-supported builds, but has not retested Android Auto on the current builds. This is historical evidence that this microphone/path has worked, not a current Android Auto pass and not evidence of inherent OEM inaccessibility.

Pre-CarPlay source `114ae144ea6eacf786fbd66b6ea116e28bede9ed` and baseline `cd9a367cd21bc7299bdb219c055642da15de98c2` have identical `MicCaptureManager.kt` and `AudioFocusManager.kt` blobs. Both Android Auto versions request `VOICE_RECOGNITION`, mono PCM16, the rate provided by mic_start (typically 16000), implicit input routing, and the common app RECORD_AUDIO permission. Android Auto additionally attaches optional NoiseSuppressor/AGC/AEC effects and uses urgent-audio thread priority. CarPlay speechrecognition requests the same Android source, mono PCM16, 24000 for LPCM (48000 for Opus), no forced route and no attached effects. Builder-versus-legacy construction, rates, effects and capture pacing differ; none is demonstrated to cause silence.

Both output focus paths use GAIN/MEDIA/MUSIC. Android Auto requests session focus with delayed-gain acceptance; CarPlay requests output focus when starting its actual downlink track. Neither microphone worker requests separate focus. No source/focus/routing switch is justified by the captured zero PCM alone.

Application initialization now additionally starts a receive-only Bluetooth profile observer and installs USB VPN consent/service plumbing. Inspected observer uses broadcasts/lifecycle callbacks, not profile connection/SCO/microphone mode mutations. `AndroidMediaSink` initializes microphone support by retaining application context; qualification creates/releases recorders without starting capture. Manifest RECORD_AUDIO and Activity request policy are unchanged across the comparison. No proven shared microphone initialization defect was found; current Android Auto capture and OEM/global resource policy remain unmeasured.

## Receipt and privacy boundary

`media stage=microphone-policy scope=own source=VOICE_RECOGNITION rate=24000 permission=granted mute=false route=builtin silenced=true result=ok`

- `source`/`rate`: this capture's selected Android source and rate, not another client's.
- `permission`: RECORD_AUDIO grant, `denied`, or `unknown`; not a claim that AppOps/OEM policy allows audible capture.
- `mute`: public global microphone-mute flag, or `unknown`.
- `route`: `routedDevice` of the exact active AudioRecord, reduced to builtin/wired/usb/bluetooth/other/none/unknown. No IDs, names, addresses or inventory dumps. `none` means null at sampling, not proof of no HAL path.
- `silenced`: `activeRecordingConfiguration.isClientSilenced` for this recorder only (API29+, module minSdk32). Null/restricted/unavailable configuration remains `unknown`, never false.
- `result`: ok if all queried fields are known, partial if some are unknown, unavailable if all are unknown. No exception messages or raw audio/levels are exported.

The production sanitizer accepts only the full finite schema, rejecting private suffixes, unknown fields and control characters. The existing bounded reporter and actual upload archive retain the receipt.

## Bounded observation ownership

Sample after completed frames 1, 250 and 500, at most three attempts per capture. Public OS policy/route/config queries run on a separate process-wide daemon executor with one worker and a zero-capacity handoff queue. Busy submissions are dropped, never retried/queued. A stuck Binder call can suppress subsequent policy observations; it cannot hold the capture/codec/socket owner or retirement worker. No recording callbacks, listeners or additional recording resources are installed. At worst one blocked query retains one recorder reference and report closure; it never owns their release or successor admission.

Close revokes best-effort results immediately, including while the recorder read remains stalled. There is no OS-query join/cancel wait in close/finally. A callback already entering the existing status sink can race revocation, as with other best-effort receipts; downstream session qualification remains authoritative. Recorder/codec/socket release, exact physical capture ownership, read-after-cancel fencing, original packet sealing/counters and permission checks remain unchanged.

## Physical unknowns / discriminating next evidence

No device access, upload, release or physical validation was performed. First inspect one short parked Siri attempt on a build containing this schema: zero capture plus silenced=true supports Android concurrent-capture policy; mute=true supports global mute; an unexpected exact input-route class supports routing investigation. `unknown` identifies missing access, not a negative result. Known builtin route + unsilenced + unmuted still cannot rule out source/rate/HAL/AppOps policy.

If those predicates do not discriminate, a separately authorized current Android Auto capture on the same build would distinguish a shared regression from CarPlay-specific rate/activation differences. Do not describe historical Android Auto success as that test, ask for repeated uninstrumented Siri attempts, automatically change sources/rates, or claim that new diagnostics fixed audibility.
