# CarPlay vehicle-data contract qualification

This is a source/host-test qualification, not owner-phone or vehicle proof. The wire fixtures are synthetic shapes recovered from representative iPhone16,1 firmware **24A446**, not a capture of the owner's phone. Maps recognition, EV enrollment, consumption/charging model requirements, OEM GNSS output and visible routing remain unverified.

## Evidence and implemented boundaries

The outside-tree audit `/hermes_work/carplay-vehicle-protocol-deep-audit.md` pins disassembly and source comparisons in `/hermes_work/research/carplay-ios/analysis/24A446/vehicle-data-audit/`. In particular:

- `location-start.txt`, `location-wire-start.txt`, `location-filter-list.txt`: StartLocationInformation parameter **0 is a U32 global/minimum interval**, GGA/RMC selectors **1/2 are void**, and their U32 intervals are **0x8001/0x8002**. The production reporter now keeps independent sentence clocks. A present global interval bounds each selected sentence's minimum period; an absent global interval does not override a supplied per-sentence period. Missing intervals default to 1000 ms; accepted interval values are 1–60000 ms, with a **local 250-ms scheduling floor** to bound polling. The floor is a local resource policy, not a firmware scalar-width claim.
- `vehicle-start-wire.txt`: A100 includes void status-group selectors **3/11/21**, not empty-only starts and not scalar update selectors 22/23. Requests are intersected with the frozen supported groups. Empty starts retain the previous all-supported compatibility behavior. Unknown IDs, duplicates, malformed TLVs, oversized bodies and non-void selectors fail closed and revoke the old subscription. EV writes remain limited to one attempt per 30 seconds, including repeated starts and missing samples.
- `vehicle-update-*.txt` and `vehicle-status-schema.json`: verified update IDs/widths are retained: **3/11 U16 range**, **22/23 U32 current/usable-maximum energy**. A group-3 request does not authorize group-11 output; group 21 authorizes supported current/maximum fields. Codec units/profile eligibility are not expanded by the firmware width evidence.

GPS requests are capped at 128 bytes before parsing. Duplicate IDs, incorrect widths, out-of-bounds intervals and orphan per-sentence intervals are rejected precisely. These are bounded production admission policies, not a claim that every recovered sender encoding is accepted: interval IDs must carry U32 (including value 1); the sender's void-for-numeric-1 form is rejected as width, and 0x8000 is rejected as unsupported_id. Conflicting global/per-sentence values use the conservative max(global, sentence, 250-ms local floor), not a claimed firmware-mandated conflict rule. EV STOP→START preserves the exact link's last attempt timestamp: restart waits out any remaining 30-second resource budget, rather than allowing repeated stop/start to bypass it. The existing known PASCD selector 4 can be parsed as void but is never forwarded or advertised; PASCD/speed/gear semantics are not invented. Stop commands revoke even with malformed payloads. NMEA is still validated through the production cache and wire boundary, never synthesized from Android Location objects.

## USB ownership and capability coherence

One immutable capability snapshot feeds USB IdentificationInformation and its actual runtime reporter. A bounded, exact-CSM bootstrap owner retains only the latest command for each of the GPS and EV planes (two bounded copies, including invalid-start rejection sentinels). Identification, MFi and the post-authentication endpoint wait route only the four known vehicle start/stop IDs into that owner. Unknown messages retain the previous failure/endpoint handling. Deadlines are not extended by side requests.

The runtime authenticates the exact link before replay; there is no pre-authentication source activation or write, no extra CSM reader, no cross-link queue and no global/latest-session target. Stops replace older starts, payload mutation cannot change retained commands, and runtime cancellation is a nonblocking fence independent of transport-write retirement. The immutable offer does not grow when a later source appears, nor shrink because a separate late factory sampled stale data; every actual update still passes current source admission.

## EV source validity

The 0.1.519 upload establishes a local admission defect: an AVAILABLE range sample
with active, matching registration was rejected at source age **31295 ms**;
current battery energy was then intermittently dropped, and the next session
froze an empty EV offer. This proves filtering, not Apple Maps eligibility.

The production forwarder now carries the actual supported `CarPropertyConfig`
change mode in each observation. Requested `SENSOR_RATE_ONCHANGE` is not evidence
of HAL change mode. STATIC (0) and ONCHANGE (1) values may have old event timestamps
because unchanged values do not need periodic change events. Their source age
therefore is not a heartbeat deadline. CONTINUOUS (2), unknown/unreadable and
invalid modes retain the **30-second source-age** limit.

All modes still require a valid nonfuture source timestamp, AVAILABLE status,
VHAL provenance, active subscription, matching positive registration generation,
bounded numeric values, pure-electric fuel identification and a **30-second
receipt-age** limit. That receipt must be an actual read/event, not a cached
snapshot access or a batch publication. No TTL was increased.

Because Android explicitly warns that status-only changes may omit callbacks,
the existing process-owned registration/refresh lane performs real status/value
reads of the three supported CarPlay EV properties every 5 seconds when their
supported mode is STATIC/ONCHANGE. It does not add a second subscriber or poll
continuous/unknown modes. Each completed read is published before the next
property's Binder read can block. Read failures/unavailable/null observations revoke
admission, and blocked reads expire at the unchanged receipt-age bound. Reads
preserve the HAL event timestamp, never replace it with now. A callback/error
arriving during a read outranks that read; manager, permission and registration
checks fence late results after retirement. The poller is independent of the GM
HistoryProvider and canceled during forwarder cleanup. EV availability callbacks
publish promptly even without a numeric value, avoiding a retained AVAILABLE
snapshot after a null/status failure.

Per-property diagnostics keep the existing bounded reason/age schema. Actual
usable capacity remains distinct from nominal/new capacity; no nominal
replacement, fabricated SOC, inferred charging state or connector inventory is
added. Host regression tests exercise the actual reflection registration/read
path and production CarPlay adapter, not a replica. On-vehicle HAL behavior,
phone consumption and Maps enrollment/recognition remain unverified.

Actual supported runtime configuration is authoritative, not a property-name
assumption: the current Android `VehiclePropertyIds` documentation labels range
and battery level CONTINUOUS, and actual usable capacity ONCHANGE. The 519 archive
does not export change mode, so it proves the old age rejection but not the
vehicle's mode. The regression recreates the uploaded ages with a discovered
ONCHANGE config; if the OEM reports CONTINUOUS, the conservative age gate remains
and this change does not promise that vehicle's range/current will be admitted.

API contract references:
- https://developer.android.com/reference/android/car/VehiclePropertyIds
- https://developer.android.com/reference/android/car/hardware/CarPropertyConfig
- https://developer.android.com/reference/android/car/hardware/CarPropertyValue
- https://developer.android.com/reference/android/car/hardware/property/CarPropertyManager

The API defines timestamp as the event time on the elapsed-realtime clock,
ONCHANGE as report-on-change, and warns callers not to rely on status-change
callbacks. Successful registration plus AVAILABLE must be revalidated rather
than treated as indefinite liveness.

## GNSS activation

The Android LocationManager NMEA listener is a collection subscription, not an owned navigation request. The Android API requires GPS enabled and foreground location eligibility; the AOSP Android 15 `GnssNmeaProvider.registerWithService` calls `startNmeaMessageCollection`, not a LocationManager navigation request. See:

- https://developer.android.com/reference/android/location/LocationManager
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-15.0.0_r1/services/core/java/com/android/server/location/gnss/GnssNmeaProvider.java

For subscribed CarPlay sources, `GnssForwarderImpl` now owns a 250-ms GPS request using its own exact listener and removes it on stop. The existing two-argument AA constructor retains its independent provider-request policy. A false NMEA registration result cannot claim an active source. Location callbacks only activate the engine: they never create or log NMEA/coordinates. Exact failed source retirement remains retryable while callbacks and reactivation are fenced. Host OS-boundary tests establish requested registration/removal, not OEM GNSS callbacks or phone consumption.

## Diagnostics and privacy

The production sanitizer accepts only full-string finite schemas for capability offers, request boundaries, property admission and bootstrap deferral. Existing successful after-write receipts retain transmission count and actual field presence. Per-key throttles and the app's bounded reporter/export queue remain in effect. Request diagnostics contain IDs, lengths, supported masks and admitted effective cadence only, never rejected scalar values, raw packets, coordinates, VIN, phone identity, authentication material or exception text. Oversized bootstrap bodies are represented by a bounded rejection sentinel rather than retained; later request `bytes=129` describes that sentinel, not the original oversized size. Source ages saturate at 99999999 ms; -1 means unavailable/future. Status -1 is unknown/non-allowlisted.

Tests cover the actual parser/reporter, adapter, identification transform, GNSS implementation, exact protocol client loops, bootstrap replay, production sanitizer/reporter and real LogUploadArchive. Source-wiring guards supplement, not replace, executable tests. Private/control-character suffixes and unknown diagnostic reasons must remain rejected. A completed transport write means **sent locally; Maps consumption unverified**.

Version allocation, signing, publication, PR/merge and affected-issue updates belong to the parent release controller. No device connection or deployment is part of this qualification.
