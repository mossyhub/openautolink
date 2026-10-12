# Independent normal modes and transient USB hotplug

Owner contract supersedes the former shared/global USB/Wireless selector.

## Exact storage migration

* `direct_transport` remains Android Auto's **normal** selection, with the same `usb` / `wpp` IDs. Default remains `wpp`. No startup, cable consent, Stay, switch, failure, detach, or restoration writes this key. Its pre-existing read normalization of retired AA transport IDs to WPP is unchanged; stored bytes are not rewritten by this change.
* `carplay_settings_v1` gains `connectionMode`, a String restricted to `usb` / `wireless`. New installations, missing records, legacy records missing this member, and unknown mode strings decode as `usb`. This is explicitly **not** inferred from the Android Auto normal mode. Existing enabled/disabled status and all other valid record fields are retained. Existing enabled CarPlay with no mode therefore becomes wired-only normal CarPlay, not implicit wireless opt-in.
* Decoding is read-only. The next explicit CarPlay Save/enable edit serializes the defaulted mode in the same atomic record. Explicit `wireless` round-trips; unknown modes cannot be encoded. USB CarPlay is supported; fresh settings permit iPhone projection while explicit saved enable/disable choices are retained. No shared/global transport preference is introduced or consulted.
* A cable switch is an in-memory generation lease only. Neither protocol's normal mode is changed, including after successful USB projection.

## Candidate consent and lifecycle

The ViewModel keeps one USB manager observing while normal Android Auto/WPP runs, including while wired CarPlay is enabled. Observation does not stop SessionManager, mark external USB ownership, request permission, issue AOA, open endpoints, Pair, or stop an existing receiver. Apple remains excluded from AOA independently of enablement.

One exact attachment produces a generation-qualified candidate. Active/connecting AA, selected/connecting CP, or an existing transient USB override produces `usbHotPlugPrompt`. An idle selection may start automatically, but it rechecks owner activity after its disk read and promotes itself to a prompt if startup won the race. Disabled or invalidly configured CarPlay cannot admit an Apple candidate; Android remains eligible.

Public UI contract:

```
val usbHotPlugPrompt: StateFlow<UsbHotPlugPrompt?>
data class UsbHotPlugPrompt(val generation: Long, val protocolLabel: String)
fun acceptUsbHotPlug(generation: Long)
fun stayOnCurrentProjection(generation: Long)
```

`protocolLabel` is restricted to `Android Auto` / `CarPlay`. Stay/dismiss suppresses this exact attachment without touching either owner or saved settings. Detach invalidates pending consent; replug or explicit Retry gets a new capability. A second pending phone cannot replace the first. A second cable attached while USB itself is active can be offered without changing current dispatch ownership.

Accept revalidates exact generation/presence and latest disk-backed enablement/configuration before retirement, after disk reads, after awaited retirement, and at manager admission. The policy claims a transient generation first to fence queued wireless callbacks; then it awaits advertiser, AA native session, and CP receiver retirement. Only after acknowledgement does manager admission begin permission/AOA or Apple runtime startup. A confirmed second candidate retires its old USB owner before dispatching the new one; delayed cleanup for the old generation cannot restore over a replacement.

AA normal startup captures a USB policy epoch and checks it both before and inside SessionManager's serialized lifecycle owner, including its final asynchronous start branch. Externally owned USB blocks a saved-WPP rearm while the transient lease is live. Exact pipe identity also fences delayed USB-ready callbacks. SessionManager reports synchronous native USB start rejection; later exact transient AA disconnects retire the attempt instead of retaining a stuck override.

On detach/rejection/start failure, restoration clears the override only after awaited retirement. With wired-only/disabled normal CP, AA normal WPP restarts through its unchanged GM/default-phone-priority path; AA normal USB remains waiting for a cable. Wireless CP can restart only if its own saved mode is explicitly `wireless` and enabled/configured. Failed retirement retains the lease/token and blocks successors until explicit Retry proves retirement.

The existing USB permission generations, Apple serial-qualified re-enumeration callbacks/deadlines, VPN consent, authentication/certificate paths, and native detach acknowledgement are retained. Expected AOA/Apple protocol re-enumeration does not produce another consent prompt.

## Verification scope

Behavioral host tests exercise consent suppression/replug/retry, finite protocol decisions, active/in-flight owner decisions, pending detach, exact admission/re-enumeration, second pending/active USB phone, single-owner retirement ordering, stale cleanup, start failures and disk-backed unchanged normal preferences. Source wiring checks supplement these tests for actual ViewModel callback placement. These are host/synthetic proofs, not physical USB/phone interoperability. No devices or uploads are part of this work.

Final combined gate on the matched local/host source tree: JDK17, the shared `/mnt/Temp/Hermes_Work/.oal-gradle.lock`, one worker, bounded invocation of `:app:testDebugUnitTest :carplay-lab:testDebugUnitTest :app:assembleDebug --rerun-tasks --no-daemon --max-workers=1 --console=plain` returned BUILD SUCCESSFUL (78 tasks executed). XML readback: app 1139 tests, 0 failures/errors, 1 skip; carplay-lab 365 tests, 0 failures/errors/skips. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`, SHA256 `6f1fa3db53d4d6bfd662c7884b806d15937ef48eaf2a03777e1ebc1102f1c0ec`. Existing Kotlin deprecation/type warnings and SDK XML-version warning were nonfatal. Release publication and physical interoperability remain separate gates.
