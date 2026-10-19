# Microphone diagnostic

This opt-in tool measures the head unit's microphone capture. **It is not a microphone fix or a phone-microphone selector.** It does not send test audio to CarPlay or Android Auto.

## Run a parked test

1. Park safely. End calls, disconnect projection phones, and leave the iPhone unpaired from vehicle Bluetooth. If wireless CarPlay still owns a live session, stop it first. Android Auto must be idle.
2. Open **OAL Settings → Diagnostics → Open Diagnostics Dashboard → Microphone**.
3. Leave the panel visible and check the consent box. An automotive device requires fresh, valid actual Park information; the CarPlay Always-in-Park override does not qualify. A nonautomotive test tablet instead requires explicit stationary confirmation.
4. **Inspect inputs** performs a passive input/capability census without recording. Four separate comparisons each run control → test → control:
   - **Channels:** 24 kHz mono → two channels → mono.
   - **Rate:** mono 24 → 48 → 24 kHz.
   - **Input:** default → the uniquely exposed native named input → default, at 24 kHz mono. An absent/ambiguous input or rejected preference ends the test rather than guessing an ID.
   - **Optional parked sources:** `VOICE_RECOGNITION` (6) → `VOICE_COMMUNICATION` (7) → `VOICE_RECOGNITION` (6), all at 24 kHz mono with default input selection. Recorders run sequentially; each must release before the next opens. This compares source requests without entering call mode or changing routing policy.
5. Choose the comparison needed for the investigation and speak throughout the short comparison. Keep the environment unchanged. Channels, rate and input remain recognition-source-only. The sources comparison tests standalone communication-source capture, **not active CarPlay telephony**; it never opens a recorder during projection or bypasses the projection gate.
6. Wait for completion and the summary-storage message, then use the existing **Upload Logs** menu action. **Up to 12 completed numeric comparisons are retained for manual Upload Logs; ordinary upload age/size limits apply.** Upload authorization/configuration and internet access are still required. Nothing uploads automatically.

The microphone permission must be granted to OAL. Non-normal audio mode, system mute, client silencing, unknown recording policy, changed route or changed eligibility aborts the comparison. Leaving the panel or backgrounding OAL cancels it. Each arm has a three-second deadline and the comparison a fifteen-second deadline; a stalled platform call can leave **Stopping** until actual recorder release. OAL does not pretend interruption means release and will not overlap another recorder.

## Interpret results

- Requested, recorder, active-client and device shapes are distinct. Two device channels do not by themselves establish physical stereo microphones or a required channel conversion.
- `No samples captured` is not the same as captured samples containing zeros.
- Nonzero samples establish local signal, not intelligible speech, phone receipt or caller audibility.
- Preferred-input setter acceptance is not proof of actual routing. Read the actual named-route match, stability and comparison with the first arm.
- A route change makes a channel/rate comparison inconclusive. Opposite channel polarity is only a hypothesis until the actual per-channel and sum/difference evidence supports it.
- Empty advertised capability arrays mean unconstrained support by the public API contract, not zero supported formats. Unknown/truncated values remain explicitly unknown/truncated.

## Privacy and scope

No live audio is recorded to a file, played, recognized or transmitted by the tool. It keeps only bounded numeric aggregates, finite classifications and Boolean observations. Device names, addresses, IDs and raw exception text are excluded. Completed numeric summaries are atomically saved as bounded per-run `microphone_diagnostic_run_*.log` files in OAL's ordinary log directory for the existing manual uploader (up to 12 runs). `microphone_diagnostic.log` is a compatibility alias for the latest completed result, not the retained history. Ordinary upload age/size limits still apply.

Normal capture sources, rates, channels, effects and negotiated CarPlay formats are unchanged when the tool is idle. During testing, a process-wide reservation excludes OAL's normal microphone recorders until actual release. There are no audio-mode, output-device, Bluetooth/SCO or vendor-parameter writes.

A successful tablet test does not prove GM capture or CarPlay cellular-call audio. Final vehicle acceptance still requires isolated car-microphone speech reaching Siri or the far-end caller.
