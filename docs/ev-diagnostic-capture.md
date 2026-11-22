# EV diagnostic capture for road testing

This build collects evidence to improve EV arrival estimates. It **does not change the vehicle energy model or enable learned-mode calibration**. A correct current battery display is not proof of an accurate arrival forecast.

## Apple Maps on CarPlay

Enable file logging before connecting, then reconnect CarPlay and retry the route while parked. Leave file logging on and use **Upload Logs** on the car immediately afterward; logcat capture is not required. The upload snapshots `carplay_ev_wire.log` separately from the ordinary file buffer. Its link-scoped entries distinguish the prepared Identify offer (connectors and ratings) from completed local status writes (battery, range and model values). A prepared offer does not prove it was sent or accepted, and a completed local write does not prove Apple Maps used it. These entries do not include route text, coordinates, or a VIN.

This capture is kept in memory for the active file-logging run; stopping logging or exiting OAL can discard it. It keeps the first complete model write and latest status for each of up to eight links and reports how many links were evicted. Upload includes the receipts already recorded when its snapshot begins, not writes that finish later.

## Android Auto road testing

1. Enable the existing **Always Log (maintainer)** option, or start file logging before the journey.
2. Start a Google Maps route before departure when practical. Drive normally; no screen interaction is required during the journey.
3. After reaching the destination and parking, press the existing **Upload Logs** button before turning the car off. Let the upload finish. Check the result message for the EV-file count and any omission or flush warning.
4. Repeat on the return journey. One ordinary journey first verifies data completeness; additional mixed-road and longer journeys then support analysis.

The car needs internet only for the manual upload. There is no automatic/background upload, new server endpoint or phone-app requirement for this capture. Existing owner authentication is reused.

## What is recorded

Dedicated `ev_*.log` files contain bounded structured records of observed energy/capacity, speed and integrated observed distance, signed net pack changes, range, gear/ignition, charging state, available temperatures, and input timestamps/status. Unknown or stale observations are retained with explicit provenance; gaps are not filled with invented driving distance.

Records also distinguish requested EV settings from effective runtime settings, retain actual native model payload/send outcomes and raw Maps forecasts, and identify observable route boundaries without claiming same-destination continuity. The recorder deliberately suppresses initial/latest comparisons and parked-arrival candidates while the real callback lacks a route ID and forecast-production timestamp. The existing dormant learner may correctly be reported as `inactive_not_initialized`; this build does not silently turn it on.

## Limits and privacy

- Collection follows explicit file-logging consent. Disabling capture rejects new observations; previously accepted entries may finish flushing to disk.
- The compact recorder has a bounded queue, small rotating files, retained-byte/file/age limits, and loss/error counters. It cannot guarantee recovery from process death, full storage or unavailable vehicle properties.
- Compact EV records use schema `2`. Schema 2 keeps every schema-1 field (`routeEpoch`, nullable `destinationHash`, and the former initial/latest fields) so additive readers can parse both versions, but it changes route/forecast semantics explicitly rather than silently reusing schema 1: `routeEpochId` is an opaque lifecycle identity, `initialArrivalWh`/`latestArrivalWh` remain null when the protocol cannot prove route continuity, each raw forecast is recorded as `arrivalWh`, and `forecastCorrelation` states why comparison is unavailable. `receivedAtElapsedMs` is retained for schema-1 readers but means callback receipt; schema 2 also emits `callbackReceivedAtElapsedMs` and `forecastTimestampBasis=callback_receipt_not_production`.
- Compact records exclude raw destinations, roads, coordinates and VINs. They remain pseudonymized behavioral telemetry rather than anonymous data: exact relative timing, energy/speed sequences and opaque lifecycle identities can preserve within-capture linkage and may be recognizable when combined with outside trip knowledge. Opaque route epochs rotate at explicit route/session/reroute/clear boundaries and at a large positive remaining-distance discontinuity. Destination text and numerical similarity never certify continuity. The current real callback has no route ID or forecast-production timestamp, so same-native-session delayed forecasts remain uncorrelatable: raw forecasts are retained, but no initial/latest comparison or parked-arrival candidate is emitted. Retired callback registrations are fenced by native-session generation where ownership is observable. **Existing general diagnostic/logcat files can still contain navigation and device details**; the complete upload remains private diagnostic data.
- EV files are prioritized across their retention window. Large or old general logs may be omitted; the ZIP manifest and result message report that. Growing files are bounded-prefix snapshots, not an atomic snapshot of every byte written afterward.
- Standard AAOS properties vary by vehicle. No fixed battery size, guessed temperature correction or one-vehicle calibration is applied.

## Interpretation

Treat individual forecasts as raw evidence only. Do not compare initial and settled forecasts until a future protocol signal can prove they belong to the same route; explicit boundaries and distance discontinuities can disprove continuity but cannot establish it. Native transport completion is not proof Maps used that exact revision. The separate synthetic tablet lab is never part of the car build, and its fixtures must not be treated as real driving data.

The pseudonymized regression gate derived from the private 0.1.501 upload is documented in [ev-real-upload-replay.md](ev-real-upload-replay.md). It replays sanitized semantic inputs through current recorder/core behavior; it does not treat the old archive's computed fields as proof of the repaired behavior.
