# Microphone diagnostic

The local opt-in tool measures the head unit’s microphone without transmitting it. The separate advanced **CarPlay microphone override** can change explicitly armed future CarPlay capture; neither diagnostic changes Android Auto or selects a phone microphone. Normal CarPlay calls/Siri need no diagnostic arming; use [Audio and call setup](phone-calls.md).

## Normal CarPlay calls

Normal call and speech capture uses the owner-accepted build 548 acquisition package: classic recognition-source AudioRecord, 16 kHz mono PCM16, urgent audio priority and best-effort effects attached after recording starts, converted to the genuinely negotiated wire format. The full qualification/acquisition package is promoted, not one ingredient claimed as the cause. The owner accepted calls and Maps-button Siri; five released capture owners had 3095/3095 nonzero raw blocks. This is a bounded vehicle interval, not universal GM or echo/noise/restart acceptance. Default-purpose MIC is qualified separately.

An explicitly armed receiver-frozen advanced override takes precedence for future owners only. **Off** returns future sessions to production policy, not an earlier silent/default or manual 48 kHz profile. An active recorder is not mutated. Host tests prove qualification/conversion/transport/retirement boundaries, not acoustic quality.

## Run a parked test

1. Park safely. End calls and disconnect projection phones. Record the chosen Bluetooth topology and hold it constant between trial arms; a paired experiment does not require unpairing. If wireless CarPlay still owns a live session, stop it first. Android Auto must be idle.
2. Open **OAL Settings → Diagnostics → Open Diagnostics Dashboard → Microphone**.
3. Leave the panel visible and grant microphone permission if needed. Start a deliberate local comparison only while parked; the tool has no separate consent/stationary checkbox and does not certify vehicle Park.
4. **Inspect inputs** performs a passive input/capability census without recording. Four separate comparisons each run control → test → control:
   - **Channels:** 24 kHz mono → two channels → mono.
   - **Rate:** mono 24 → 48 → 24 kHz.
   - **Input:** default → the uniquely exposed native named input → default, at 24 kHz mono. An absent/ambiguous input or rejected preference ends the test rather than guessing an ID.
   - **Optional parked sources:** `VOICE_RECOGNITION` (6) → `VOICE_COMMUNICATION` (7) → `VOICE_RECOGNITION` (6), all at 24 kHz mono with default input selection. Recorders run sequentially; each must release before the next opens. This compares source requests without entering call mode or changing routing policy.
5. Choose the comparison needed for the investigation and speak throughout the short comparison. Keep the environment unchanged. Channels, rate and input remain recognition-source-only. The sources comparison tests standalone communication-source capture, **not active CarPlay telephony**; it never opens a recorder during projection or bypasses the projection gate.
6. Wait for completion and the summary-storage message, then use the existing **Upload Logs** menu action. **Up to 12 completed numeric comparisons are retained for manual Upload Logs; ordinary upload age/size limits apply.** Upload authorization/configuration and internet access are still required. Nothing uploads automatically.

The microphone permission must be granted to OAL. Non-normal audio mode, system mute, client silencing, unknown recording policy, changed route or changed eligibility aborts the comparison. Leaving the panel or backgrounding OAL cancels it. Each arm has a three-second deadline and the comparison a fifteen-second deadline; a stalled platform call can leave **Stopping** until actual recorder release. OAL does not pretend interruption means release and will not overlap another recorder.

## Local custom capture

The **Local parked capture** group also provides **Run custom · one arm**. Select source `MIC (1)`, `VOICE_RECOGNITION (6)` or `VOICE_COMMUNICATION (7)`; 16, 24 or 48 kHz; mono or two channels; and default input or the existing exact uniquely matched named input. No arbitrary source/rate/channel values or device identifiers are accepted. Named mode refuses absent, ambiguous or truncated inventory; it never guesses a route. The custom arm uses the same worker, capture reservation, policy rechecks and at-most-three-second deadline as comparisons. A platform-stalled release can still take longer. Run and all selectors are disabled while capture is busy; Cancel remains available until stopping. Existing control/test/control comparisons remain accessible below the custom controls.

## Advanced CarPlay microphone override — separate from local capture

This separate opt-in group arms an override for the **next CarPlay connection / new recorder**, not a second local test recorder. The override defaults **OFF**, which uses production capture. The profile lives in process memory only, expires after 15 minutes, and is never an always-on preference. Apply requires a foreground visible panel and idle projection/capture ownership. Apply is the deliberate opt-in; no separate consent or Park checkbox is shown. Configure only while safely parked.

Select automatic (existing purpose-specific source), MIC, recognition or communication; negotiated, 24 or 48 kHz local capture; mono or two channels. Two channels permit average, first or second channel selection. The receiver adapts experimental PCM to its exact negotiated wire format; requested local rate is not the selected wire codec or RTP clock. The live codec preference is Auto, LPCM or Opus. It restricts only existing qualified microphone offers, never adds wireless PCM or changes output offers. The phone still selects the actual wire format; the preference is not a claim that a particular encoder was selected. A restriction with no matching input can deliberately prevent experimental CarPlay connection: **Off + explicit reconnect** restores production offers. No silent microphone-free fallback or midstream codec change is performed. Existing microphone setup/capture receipts remain the source of actual selected wire codec evidence.

Arm while disconnected, then leave the page and connect CarPlay within the expiry window. Leaving/backgrounding cancels standalone capture but **intentionally retains the armed future-session profile**. Disarm/reset or expiry affects future sessions only, not a recorder already started. Stop/reconnect CarPlay to apply a new choice. Other experimental profiles' call intelligibility, caller audio and echo behavior still require real parked testing; this is not an automatic fallback.

## Passive measurements during normal projection

Normal projection can retain bounded numeric capture, decoded/output signal, recorder-policy, own-route and terminal-retirement observations without arming an experiment. Terminal summaries preserve completion observations across normal recorder/receiver retirement for the existing manual log archive. This does not save microphone audio or automatically upload anything. Nonzero signal, a recorder starting, encoder output and packet sends are different observations; none proves Siri understanding or far-end call audibility. Unknown or unavailable policy/route observations remain unknown.

## Independent Siri lab — parked, explicit opt-in

The separate **Independent Siri lab · history and live capture** controls distinguish a qualification-history bundle from a live speech-capture package. Hold VR48 two-channel capture fixed while comparing normal/manual history, or hold normal history fixed while comparing VR24 mono with VR48 two-channel average. History includes genuine offer/cache/timing differences, not a pure recorder-allocation count; rate, channels and conversion change together as one package. Unsupported combinations are refused without fallback; the phone still selects a genuinely qualified wire codec. Wait for actual recorder **and** receiver retirement between short trials. This does not fix Siri.

The live override defaults OFF and requires an explicit Apply action in the foreground panel with no active projection/local trial. It lives in memory for 15 minutes and affects future receiver/recorder owners only. Leaving retains an armed future-session profile. Disarm/expiry does not mutate an active recorder; stop/reconnect to apply changes. Keep accessory navigation activation and Bluetooth setup consistent between arms.

## Test: suppress native Playing status — default OFF

This independent CarPlay-only experiment suppresses the native PLAYING status announcement by submitting Playing as None to the native media session; it does not suppress or send playback commands. It retains paused/unknown states, title/artwork, session identity and transport callbacks. It does not mute audio, change Bluetooth, repair calls or establish which OEM source is selected. **Native progress, play indication, controls or targeting may change.** Media audio ON behavior and repeated-pausing outcomes are not validated; a parked experiment and owner acceptance remain required before changing setup recommendations.

Arm only while parked with no projection/local test. The memory-only arm expires after 15 minutes and is frozen once per future CarPlay metadata owner. Leaving retains the arm. Disarm/expiry affects future owners only; reconnect to restore normal publication. Normal operation leaves this experiment OFF. Parked OFF/ON/OFF testing is still required before drawing a vehicle behavior conclusion.

## Interpret results

- Requested, recorder, active-client and device shapes are distinct. Two device channels do not by themselves establish physical stereo microphones or a required channel conversion.
- `No samples captured` is not the same as captured samples containing zeros.
- Nonzero samples establish local signal, not intelligible speech, phone receipt or caller audibility.
- Preferred-input setter acceptance is not proof of actual routing. Read the actual named-route match, stability and comparison with the first arm.
- A route change makes a channel/rate comparison inconclusive. Opposite channel polarity is only a hypothesis until the actual per-channel and sum/difference evidence supports it.
- Empty advertised capability arrays mean unconstrained support by the public API contract, not zero supported formats. Unknown/truncated values remain explicitly unknown/truncated.

## Privacy and scope

The standalone local diagnostic never records audio to a file, plays, recognizes or transmits it. It keeps only bounded numeric aggregates, finite classifications and Boolean observations. An explicitly armed live experiment still sends microphone audio through the normal CarPlay session to the connected iPhone; it is not a private local-only capture. Device names, addresses, IDs and raw exception text are excluded. Completed numeric summaries are atomically saved as bounded per-run `microphone_diagnostic_run_*.log` files in OAL's ordinary log directory for the existing manual uploader (up to 12 runs). `microphone_diagnostic.log` is a compatibility alias for the latest completed result, not the retained history. Ordinary upload age/size limits still apply.

When the tool is idle, normal capture uses the purpose-specific production policy described above; the diagnostic itself does not change it. The advanced override does not invent wire formats or change Android Auto. During testing, a process-wide reservation excludes OAL's normal microphone recorders until actual release. There are no audio-mode, output-device, Bluetooth/SCO or vendor-parameter writes.

A successful tablet test does not prove GM capture or CarPlay cellular-call audio. Final vehicle acceptance still requires isolated car-microphone speech reaching Siri or the far-end caller.
