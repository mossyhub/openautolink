# Cluster guidance recovery

OAL's projected Android Auto stream and its AndroidX cluster navigation session have separate lifetimes. A healthy video stream does not establish that a consumer is still available to send cluster trips.

## Failure addressed

A vehicle capture on 0.1.496 showed a primary cluster session become ready, pass its startup health check, and later be destroyed. A route started afterward; maneuver and distance messages kept arriving, but no primary session was recreated and no trip delivery was attempted. This affects the common guidance handoff used for cluster/HUD output.

Native AAOS Maps page switching was reported during that drive. OEM code confirms map-feed selection changes, but neither the capture nor the inspected teardown proves that it caused OAL's session destruction. The AndroidX `Unbinding` message describes client cleanup, not an identified OEM decision.

## Recovery contract

- When an OAL route needs guidance, verify that the enabled cluster binding remains usable and recover a missing consumer.
- Keep the current maneuver available for the replacement session to consume; do not clear navigation just to replace the binding.
- Do not restart the Android Auto transport, video, or audio session for cluster-only recovery.
- Bound automatic recovery and ignore work from retired generations.
- Cancel route-owned recovery after route clear, host stop, arrival, or explicit release/disable. Every delayed callback captures its manager generation, cancellation epoch, and original route demand; dequeued callbacks cannot adopt a later unrestricted bootstrap state or survive disable/re-enable.
- Serialize primary ownership, route/maneuver snapshot validation, trip effects, and retirement under the lifecycle lock. Revalidate after host calls, and reject host-stop suppression for a route the session has not observed.
- Host-requested navigation suppression belongs to the route, not just the old consumer. A replacement session must not reclaim a route the host stopped.
- Preserve the existing invisible, noninteractive bootstrap Activity and its required renderer lifetime.

## Validation

Software tests exercise the real manager and cluster-session code with controlled Android dependencies, including late loss, in-flight initialization, retained-route replay, cancellation, suppression, and bounded retries. A green test/build is not vehicle confirmation.

The vehicle acceptance sequence is: a lost primary binding is replaced, a new primary becomes ready, `Navigation ownership outcome=started` and `Trip update outcome=sent` appear for the current route, and the driver confirms visible cluster/HUD guidance. Also verify that intentionally using native navigation does not provoke a focus fight or an obstructing bootstrap window.

Tracking: [issue #132](https://github.com/mossyhub/openautolink/issues/132), left open pending vehicle verification.
