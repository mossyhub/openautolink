# Isolated public-AAudio capture comparator (not integrated)

This directory is deliberately outside every Android/Gradle/CMake source set. No JNI,
Android executable entry point, permission request, UI, or automatic capture exists.
`capture.cpp` is real libaaudio input code; host tests replace only the platform C ABI.
Cross-compilation links the same implementation against the existing NDK API32/34
libaaudio libraries. Neither proves a working vehicle microphone.

Fixed request: input preset6 (VOICE_RECOGNITION), PCM16, 48,000 Hz, two positional
channels; SHARED, LOW_LATENCY, default device, session NONE, privacySensitive=false.
No format/source matrix, exclusive request, MMAP-policy override, routing/mode/SCO,
AudioEffect creation, vendor/private API, zero-triggered retry, converter or uplink.
AAudio itself may choose its legacy AudioRecord backend; no public API32/34 getter
in the installed NDK header proves MMAP use. Sharing/performance are not MMAP proof.
Session NONE also means this is not session/effect-equivalent to Java AudioRecord's
allocated session. Automatic OEM processing and policy suppression remain possible.

## Bounds and contract

- A single worker owns create/open/start/read/stop/close; cancellation only sets an
  atomic flag, never closes a stream concurrently with its reader.
- At most 96,000 two-channel frames, 400 reads, 480 frames per read, requested read
  timeout20ms, steady-clock read-window deadline2s. Open/start/getters/stop/close can
  still stall in Android. The deadline is NOT a guaranteed completion time.
- A stalled read/close retains exclusion. `await(timeout)` returning false is pending,
  not release. Fence clears only after successful actual close AND owning thread join.
- Start/await/summary/destruction must share one control thread; only cancel is
  cross-thread. Keep the runner and referenced fence alive through retirement.
  Destructor cancels and joins and can block: never destroy on UI or admission locks.
- Close failure remains fail-closed after join/destruction; `recoveryRequiresRestart`
  is terminal for this process. Retain the owner/lease tombstone, NOT a supposedly
  retryable native pointer. No retry/reclaim operation exists. API32/34 AOSP preserves
  the object on illegal callback-thread close and says to close from another thread;
  this worker has no callbacks, so that exception does not apply. Public close docs
  do not promise validity/retry for arbitrary errors. Never retry an unknown handle.
  Stop and close return codes are retained separately; failed stop still attempts
  close exactly once. Successful close plus join permits retirement even if stop failed.
- This fence is ONLY an executable standalone contract, not the production process
  lease. Do not wire it beside production capture without real shared admission.
- Numeric summary after successful await only: requested/actual shape, mask, sharing,
  performance, privacy flag, session NONE/allocated/unknown, route same/changed/unknown,
  terminal reason/error/stopError/closeError/restart flag, frame/empty-read counts and per-channel samples/nonzero/peak/
  RMS/squares/clipping. No PCM/file/socket/callback export, device/session IDs, names,
  addresses, hashes of identifiers or arbitrary exception text. Device IDs exist only
  transiently to compare the two own-stream getter results. No physical mic identity
  or continuous route stability is claimed. No effect topology beyond no app-created
  effects is observable here; active HAL/preprocessing and suppression are UNKNOWN.

## Host qualification

From the designated host, with one compile/test process and the shared lock:

```sh
cd /mnt/Temp/Hermes_Work/oal-bt-free-capture
flock -w 45 /mnt/Temp/Hermes_Work/.oal-gradle.lock \
  python3 tools/native-aaudio-capture/gate.py final
flock -w 45 /mnt/Temp/Hermes_Work/.oal-gradle.lock \
  python3 tools/native-aaudio-capture/gate.py asan-final
flock -w 45 /mnt/Temp/Hermes_Work/.oal-gradle.lock \
  python3 tools/native-aaudio-capture/cross_gate.py
```

`gate.py` uses an unmodified copy of the installed NDK AAudio header in the evidence
include directory and only erases Android availability/nullability annotations for
host GCC. Tests supply deterministic fake C entry points, never a synthesized device
result. The cross gate uses the actual NDK compiler/sysroot without annotation erasure
or fake symbols. Results are in `/hermes_work/carplay-bt-free/capture/` (container path).

See that directory's `handoff.md` for real outcomes, source audit and the ONE proposed
next capture discriminator. This is not a permission to run it on any Android device.

Cleanup follow-up evidence/design is in `capture/cleanup-followup/handoff.md`. Set
`CAPTURE_EVIDENCE_DIR` to a fresh evidence directory for both gates to preserve earlier
outputs. This is C++ over the public AAudio C ABI, NOT a callable JNI/C bridge yet.
