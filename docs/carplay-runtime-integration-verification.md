# Real CarPlay runtime integration: intake, enrollment and manual fallback

## Implemented runtime path

`OalCarPlayRuntime` now supplies `ProjectionReceiver.runtimeCarDataFactory`. Each actual Wi-Fi iAP2 tunnel gets a new `OalCarPlayIntakeLink`, borrowing the process-owned vehicle StateFlow through the completed vehicle/appearance adapters and creating the existing `GnssForwarderImpl` only on an authenticated location subscription. No AA consumer attachment, second VHAL producer, UI/ViewModel edit, WPP policy change, synthetic location or synthetic EV values were added. CarPlay remains opt-in/default-off.

- Qualified GPS capability requires existing fine-location permission and an enabled real GPS provider. No new permission prompt/provider or synthetic encoder.
- Range/current/maximum energy capability is frozen from truthful initially available snapshots before that link's IdentificationInformation. The exact same immutable subset governs its reporter. Values that appear later cannot expand the offer mid-link.
- `Iap2IdentificationClient` has an optional frame transformation seam; runtime uses the completed `OalCarDataIdentification`, while Bluetooth/default clients remain unchanged and broad reference vehicle/speed flags remain false.
- Reporter authentication occurs only after genuine MFi success on the exact tunnel, with the exact AirPlay control owner already authenticated through real PairVerify. Identification acceptance alone cannot activate intake.
- The real runtime control loop dispatches request/stop before wireless endpoint replies and calls tick on receive iterations/timeouts using the reporter's bounded poll deadline. Same-session endpoint offers, subscription behavior and transport reply budgets remain.
- Appearance combines same-link runtime authentication with actual encrypted-event readiness, not RECORD/render/status strings. An atomic `setNightModeIfEventReady` admission reuses existing `setNightMode`/event-write locking without placing pre-ready stale observations in retained transport. Observed false is valid; unknown/stale/unavailable/mismatched-generation observations remain silent.
- Location supports requested, validated raw GGA/RMC; EV retains only source-backed sparse range/current usable energy fields. Unknown/stale fields are omitted, zero is preserved, derived SOC/nominal capacity/reserve/charging/guessed connectors are not invented.

## Transport and retirement correction

Inspection found `Iap2Session.send` only enqueues link work. It cannot honestly back a `transmitted` receipt. Added a separate bounded `sendAndAwaitWritten` path through Session/CSM/link, preserving legacy enqueue behavior. It waits for the actual stream write of every exact-frame chunk, and refuses to receipt an engine-queued command merely because earlier packets in the same batch flushed. Timeout closes the link; no abandoned telemetry is replayed into a successor.

Session callbacks revoke intake cheaply rather than waiting for the appearance mutex from an event-write callback. Runtime finally acknowledges appearance, reporter and GNSS retirement before removing/locally closing the tunnel; an ending/forced transport may close early to unblock IO, but cannot admit a replacement before retirement. Receiver cancellation revokes admission and intake before socket teardown. Interrupted lifecycle waiting is retried with interruption restored afterward: it cannot falsely acknowledge an admitted old appearance write. Receiver/storage retirement remains withheld if its worker has not terminated.

Receipts use the existing bounded receiver status worker and exact full-string diagnostic export schemas. They expose finite IDs/reasons, successful-write counts, field-presence masks and suppression counts, not NMEA/coordinates/EV values/controller identities/credentials/raw exceptions. Appearance writes have value-free success/failure receipts. Local write success is not iPhone consumption.

## Ordinary first enrollment is actually wired

The completed admission/PairSetup owner's hooks are connected to the real runtime/session, not left as helpers:

1. Selected bonded-phone bootstrap identification and genuine receiver MFi success remain required, after the existing explicit OAL CarPlay selection/enablement.
2. Automatic fresh setup waits for the selected phone's actual Wi-Fi request (`5702`, empty sourced contract) or valid wireless availability/session request (`4300`). Generic bond, `4e0e` transport identity and `4e0d` notification alone do not authorize credentials or enrollment. Only one request and one transport notification are retained before listener startup.
3. The authorized empty-store receiver explicitly opens a maximum 120000ms monotonic enrollment window. Default trusted-only receivers still reject missing trust without authorization. The universal invented BT-controller-key-binding blocker is removed only on this mode.
4. Real `AirPlaySession` forwards its owner-qualified durable callback into `PairSetup`. Dynamic endpoint admission uses `admission.allowsPairSetup(exactSession)`, including after claim, expiry, retirement and first commit. Genuine SRP/Ed25519 M5 calls `admission.persistEnrollment(exactSession, id, key, exactStore)`; denial never falls back to plain save.
5. `onControlAuthenticated` uses scoped admission to accept the committed first controller rather than requiring membership in an empty startup snapshot. Real stored-key PairVerify remains mandatory. PairSetup alone cannot admit media.
6. Cancellation/selection retirement calls `peerAdmission.retire()` outside the unrelated receiver lock. Existing persisted controller reconnect ignores the first-enrollment window; enrollment never replaces/adds trust. The library's separate legacy lab behavior remains explicit.

Accepted risk is logged truthfully: native/OAL consent plus protected AP access authorize trust-on-first-enrollment, not cryptographic proof that the selected Bluetooth phone owns the presented controller key. A reachable client knowing the fixed code can race/deny the first enrollment. One candidate, interface scoping and a short window reduce but do not eliminate this risk.

## Manual fallback and parent UI handoff

Automatic selected-Bluetooth AP credential + endpoint provisioning remains preferred; default unknown-peer IPv6 setup needs no manually entered iPhone IPv6 address. The runtime does not claim native AP join is impossible.

The user's explicit fallback (join car AP on iPhone, configure the current numeric phone IP in OAL, then select/connect CarPlay) uses the **same trusted-only, bounded, exact-interface first-enrollment admission**, including IPv4. It does not await automatic Wi-Fi provisioning requests, but still requires the selected Bluetooth identification/MFi bootstrap and real AirPlay PairSetup/PairVerify. It does not bypass bootstrap authentication if Bluetooth itself cannot authenticate. Existing trusted shared-LAN IPv4 reconnect retains the proven legacy transport path. No companion application was added.

New safe typed UI contract: `CarPlayConnectionFailure(kind: CarPlayFailureKind, manualPeer: Boolean, cause)` with finite `userGuidance`; receiver exposes typed `runtimeFailureStage`, not parsed status strings. Runtime emits distinct typed failures for network selection, bootstrap identification/authentication, wireless-request deadline, bind/admission failure, runtime identification/authentication/transport and unknown runtime-startup timeout.

**Parent must finish persistent UI propagation (owned Coordinator/UI, deliberately untouched here):**

- Catch `CarPlayConnectionFailure` and retain only `kind`, `manualPeer` and `userGuidance` in a safe failure DTO; never export cause/message/stack traces or persist raw exception objects.
- Preserve that DTO/guidance through the coordinator's finally/teardown. Its current generic class-name/ended-status catch would otherwise hide this actionable guidance. Clear on a new explicit attempt/success or user dismissal/Return, not automatically on retirement.
- A manual startup timeout says: “Check iPhone Wi-Fi is joined to the car AP and verify the current iPhone IP in OAL, then reconnect.” It does **not** claim the IP changed. Known authentication/bind failures have separate guidance and must not be converted into IP-change diagnoses.
- Automatic-mode timeout keeps endpoint provisioning preferred and offers the explicit joined-AP/numeric-peer fallback; do not demand a manual IPv6 address as the ordinary path.

## Executed verification

Host: `lance-host`, `/mnt/Temp/Hermes_Work/oal-carplay-integration`; JDK17 `/home/lance/.local/jdks/temurin-17`, SDK `/home/lance/.local/android-sdk`. Shared lock `/mnt/Temp/Hermes_Work/.oal-gradle.lock`; no SDK install/device/upload/release/commit.

Observed compiling behavioral RED then GREEN for: qualified GNSS runtime identification and request/stop; auth+event-gated appearance; real encrypted ready-only night command; real tunnel IdentificationInformation transformation; blocked actual transport write; mixed legacy/engine backlog premature receipts; cancellation preventing raw-source restart; interruption versus admitted-write retirement; value-free exported intake/appearance/enrollment receipts; actual session constructor durable callback/claim/PairVerify; selected phone wireless request gate; manual fallback wiring and typed timeout guidance. Supplemental wiring assertions ensure helpers are hooked into real runtime files. A transient unresolved `runCurrent` test extension was corrected and rerun to an assertion failure before implementation; compile errors were not claimed as behavioral RED.

Final completed combined-source gate:

```sh
flock /mnt/Temp/Hermes_Work/.oal-gradle.lock sh -c '
 ./gradlew :app:testDebugUnitTest \
   --tests "com.openautolink.app.carplay.*" \
   --tests "com.openautolink.app.input.ProcessVehicleDataCoordinatorTest" \
   :carplay-lab:testDebugUnitTest \
   :app:compileDebugKotlin :app:compileTabletDebugKotlin --console=plain
 # Parse XML before releasing this same lock.
'
```

Result: **BUILD SUCCESSFUL**; app focused **69 tests / 19 suites**, full carplay-lab **209 tests / 52 suites**, both with **0 failures/errors/skips**. Both normal and tablet Kotlin variants compiled. Exact parsed counts and target suites are in `carplay-runtime-integration-verification.json`. JVM emitted the existing bootstrap-classpath class-sharing warning; no test/build error. No test-source exclusions were used. Unfiltered full app/assembled tablet/device gates remain parent-owned.

## Physical checks still required

- Fresh-store native/iPhone permission, actual wireless-request/credential/endpoint sequence, controller-count transition, genuine PairVerify, scoped automatic IPv6 ingress and manual joined-AP IPv4 fallback.
- Stop/reconnect/selection change and first-window expiry on the actual phone; durable trust survives restart without reopening enrollment.
- Actual OEM day/night changes and observed false; no stale replay after delayed event readiness.
- Real host GPS fix, requested GGA/RMC, accessory transmission and phone Maps acceptance. Existing raw source drops the Android callback sample timestamp, so location adapter proves receipt freshness, not independent measurement age.
- Real sparse EV phone acceptance/consumption and OEM field freshness, including initially unavailable fields remaining absent for the current frozen session.
- Rerun combined TCL 1440p rendering, human audio, post-touch visible interaction and surviving session; old physical evidence is not evidence for this new integrated tree. Microphone/Siri/calls, car AP ingress and persistent failure UI require their own parent/device checks.
