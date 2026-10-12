# iAP2 car-data intake: parent integration contract

This is implemented and JVM-tested intake, **not proof of iPhone consumption**. All fixtures are source-contract fixtures; none are described as captured phone traffic. No runtime/provider files were changed by this worker. Parent owns the actual receiver hook and first car test.

## Source trace

Pinned DiPlay: `e2fd8eadf5a6b705666f292055e72aa93387533d` (`/hermes_work/research/DiPlay`, HEAD verified).

- `shared/src/main/java/com/shilapi/xcertplay/transport/Iap2LocationClient.kt:138-229`: same-link location start `FFFA`, update `FFFB`, stop `FFFC`; requested sentence IDs. Its parser incorrectly includes the component and extension as sentences; OAL does not copy that bug.
- `shared/src/test/java/com/shilapi/xcertplay/transport/Iap2LocationReporterTest.kt:16-23`: explicit fixture `u16(0,0)`, void selectors `1` GGA / `2` RMC / `4` PASCD and `u32(0x8001,1000)`. We implement this fixture contract, NOT a universal claim about unspecified extensions. Reject unknown IDs, duplicate IDs, malformed lengths, nonzero component, invalid intervals; retain only requested GGA/RMC. No implicit “all sentences” for an empty/unsupported request.
- `shared/src/main/java/com/shilapi/xcertplay/iap2/message/Iap2Messages.kt:312-316`: update TLV0 is NUL-terminated UTF-8 NMEA. The existing wire builder supplies TLV lengths and BE unsigned integers.
- `shared/src/main/java/com/shilapi/xcertplay/transport/Iap2IdentificationClient.kt:273-281`: LocationInformation group22 with identifier0, GGA flag17, RMC flag18; omit optional flag20/PASCD speed.
- `shared/src/main/java/com/shilapi/xcertplay/transport/Iap2VehicleStatus.kt:46-88,100-127`: `A100` start / `A101` update / `A102` stop; BE u16 fields3/11 range km, BE u32 fields22/23 current/max energy Wh; 30-second cadence. Empty start is the only admitted request-body contract; **nonempty A100 is disabled with `unsupported_request`**, rather than inferring selector/component/interval semantics absent from the pinned implementation.
- Same file `:54-73`: group20 VehicleInformation electric engine type2, component identifier3; group21 VehicleStatus identifier4, range-presence flags3/11 and ElectricChargeInfo umbrella flag21. Our sparse update under that umbrella includes only truthful supported energy fields22/23. No reserve21, warnings6/15, displayed SOC24, charging25, rated/max range30 or guessed connectors. Phone acceptance of sparse ElectricChargeInfo remains unverified.
- OAL `VehicleDataForwarderImpl.kt:1140-1274`: rangeKm already converted from RANGE_REMAINING meters; Wh fields preserved; batteryPct explicitly derived. `ProcessVehicleDataRuntime.latestVehicleData()` is process-owned; its single AA projection consumer is untouched.
- Android source: https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/main/car-lib/src/android/car/VehiclePropertyIds.java (EV_BATTERY_LEVEL / EV_CURRENT_BATTERY_CAPACITY docs retrieved). Actual usable capacity accounts for aging/temperature; INFO_EV_BATTERY_CAPACITY is nominal when new. https://developer.android.com/reference/android/car/FuelType#ELECTRIC confirms ELECTRIC=10 (not5).

## Exact APIs and capability freeze

New package `com.openautolink.carplaylab.intake`:

- `OalCarDataCapabilities(location, range, currentEnergy, maximumEnergy)` defaults all false. Freeze once **per actual link** before its IdentificationInformation. The exact same immutable value must be passed to its reporter. Never enable fields mid-link simply because input becomes available later.
- `OalCarDataIdentification.apply(base1D01, frozenCapabilities)` transforms an existing identification frame: preserves unrelated values; replaces groups20/21/22 and message lists6/7 with the supported subset. Use this on runtime-tunnel identification only. Do not apply to Bluetooth onboarding identification.
- `OalIap2CarDataReporter(capabilities, elapsedMs, latestNmea, latestEv, locationActive, send, receipt)`.
- Methods: `authenticate()`, `handle(frame): Boolean`, `tick()`, `pollTimeout(remainingMillis): Long`, `close()`.
- `OalEvSnapshot(rangeKm: Int?, currentChargeWh: Long?, maxChargeWh: Long?)`: unknown remains null. Codec omits invalid/out-of-wire-range values rather than saturating them.

New app adapters:

- `OalCarPlayVehicleSnapshotAdapter()` reads `ProcessVehicleDataRuntime.latestVehicleData()?.value` on demand. It never calls `attachSessionConsumer`. Accepts only pure-electric fuel inventory `{10}`, source `vhal`, status AVAILABLE=0, active subscription, matching positive current registration generation, and both observation/receipt age <=30s. Uses current usable capacity only. Energy exceeding a known usable capacity is omitted, not clamped. All unsupported status fields are absent from its type.
- `OalCarPlayGnssSnapshotAdapter(elapsedMs, createSource)` accepts the **existing host** `GnssForwarderImpl`, not a synthetic encoder or phone location. Example construction:

```kotlin
val clock = android.os.SystemClock::elapsedRealtime
val vehicle = OalCarPlayVehicleSnapshotAdapter()
val gnss = OalCarPlayGnssSnapshotAdapter(clock) { sink ->
    com.openautolink.app.input.GnssForwarderImpl(context.applicationContext, sink)
}
val initial = vehicle.snapshot()
val caps = OalCarDataCapabilities(
    location = qualifiedHostGpsSource, // parent verifies existing permission + enabled real GPS provider
    range = initial?.rangeKm != null,
    currentEnergy = initial?.currentChargeWh != null,
    maximumEnergy = initial?.maxChargeWh != null,
)
val reporter = OalIap2CarDataReporter(
    caps, clock, gnss::latest, vehicle::snapshot, gnss::setActive,
    send = { frame -> exactSession.send(frame, boundedWriteTimeoutMillis) },
    receipt = allowlistedReceiverStatusSink,
)
```

`qualifiedHostGpsSource` and `exactSession` are parent-owned real seams, not supplied/fabricated by this module. Obtain location permission through the existing app permission path; do not open another prompt or substitute synthetic GPS. If not qualified, `location=false`.

The original upstream boolean `vehicleStatusEnabled=true` produces a broader reference identification than this implementation supports. Keep all original `locationInformationEnabled`, `vehicleStatusEnabled`, and `vehicleSpeedEnabled` flags **false in the base** and apply the transformer when sending `1D01`. This explicitly adds supported message IDs/groups; do not merely flip the broad booleans. `vehicleSpeedEnabled` remains false in every case.

## Required runtime hooks (parent / network owner)

1. Construct a fresh reporter and GNSS adapter for each actual runtime iAP2 tunnel. Never share subscription state from Bluetooth or an older Wi-Fi tunnel.
2. Send `OalCarDataIdentification.apply(base,caps)` for that tunnel's `1D01` while preserving the current real identification/Wi-Fi endpoint callback. Pairing identity is not a log/session token.
3. Only call `authenticate()` after **that link** has successfully completed actual authentication (or the runtime owner has proved authentication inherited by this exact established encrypted tunnel); `1D02` identification alone is not authentication.
4. Dispatch `handle(frame)` on the control worker before its other endpoint dispatch. It consumes only `FFFA/FFFC/A100/A102`; unrelated messages remain unchanged.
5. On every receive iteration and receive timeout call `tick()`. Use `recv(reporter.pollTimeout(1000))`; no timers, child workers, global send queue, or source-driven network sends are required.
6. The supplied `send` targets the captured exact session, is synchronous and bounded, and returns only after the real transport write. If an outer queue is unavoidable, it must recheck the reporter/link generation at the actual write and issue its own transmission receipt; never call the reporter's `send` callback merely to enqueue and then label it transmitted.
7. Close reporter on stop/error/EOF/replacement in the owner's `finally`, **before** closing/replacing the tunnel; close GNSS in a `finally` as well. Reporter close/stop serialize against admitted writes; after return there can be no later write. An already admitted bounded write can complete before stop acquires the lock. There is no queued/catch-up output. Socket teardown remains the runtime owner's responsibility after a send failure.
8. GNSS starts on the authenticated location subscription only and stops on stop/retirement. Its constant-space cache validates ASCII length, checksum, talker, fix validity, time and coordinate ranges; sends only requested GGA/RMC. Retained observations expire after3s of monotonic receipt age. **Existing host GnssForwarderImpl drops the Android callback timestamp**, so this adapter proves receipt freshness, not an independent measurement-time age; it does not fabricate the missing timestamp. Old adapter-generation callbacks are rejected.

## Privacy and first-test receipts

Full-string schema emitted by reporter (bounded internally, one per `(id,reason)` per second; suppression counted):

```
carplay-car-data id=<allowlisted decimal message ID or 0> reason=<allowlisted token> count=<successful synchronous transmission count> fields=<presence mask> suppressed=<count>
```

Reasons: `authenticated`, `retired`, `unauthenticated`, `started`, `stopped`, `disabled`, `unsupported_request`, `source_absent`, `transmitted`, `send_failed`.

For location `started`, fields-mask bit1=GGA, bit2=RMC. For `transmitted`, bit1=TLV0 NMEA, bit2=TLV3 range, bit4=TLV11 electric range, bit8=TLV22 current energy, bit16=TLV23 max energy. For other reasons fields=0. Counts are per link. A failed write never increments/counts as transmitted. No sentence, coordinates, vehicle values/identity, raw plist, credentials or exception text reaches this receipt sink. Sink exceptions cannot break transport. Parent must admit this **exact schema** through its existing sanitized status export, without relaxing it to arbitrary strings.

`source_absent` intentionally does not expose invalid input or its value. Existing OEM diagnostics can show input observation age independently. A transmitted frame proves only accessory transport output. iPhone request reception, accessory transmission, iPhone acceptance/consumption and visible Maps behavior are separate first-car-test observations.

## Runnable verification

On lance-host worktree, use the shared lock and JDK17/SDK:

```sh
export JAVA_HOME=/home/lance/.local/jdks/temurin-17
export ANDROID_HOME=/home/lance/.local/android-sdk
flock /mnt/Temp/Hermes_Work/.oal-gradle.lock ./gradlew :carplay-lab:testDebugUnitTest --console=plain
flock /mnt/Temp/Hermes_Work/.oal-gradle.lock ./gradlew :app:testDebugUnitTest \
  --tests 'com.openautolink.app.carplay.OalCarPlay*SnapshotAdapterTest' \
  --tests 'com.openautolink.app.input.ProcessVehicleDataCoordinatorTest' --console=plain
```

Behavioral assertion failures were executed before implementation for unsigned wire/zero-vs-unknown encoding, NMEA filtering/staleness/invalid checksums, per-link authentication/start/stop, malformed requests/intervals, sparse EV requests/ticks, safe transmission/failure receipts, identification pruning, real process-flow mapping/freshness, GNSS lifecycle and failed-stop retirement, wire-boundary filtering, poll deadlines and field-presence receipts. Initial missing implementations were nonfunctional signatures, never reported as complete. Final verification also exercises concurrent admitted-write vs stop and the actual AA coordinator consumer remaining attached.

No device action, release or commit was performed. First physical GNSS delivery and iPhone sparse EV consumption are pending parent integration and car test.
