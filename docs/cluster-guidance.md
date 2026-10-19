# Cluster guidance behavior and validation

OAL relays projected navigation to the vehicle's navigation host. Receiving a Trip successfully is not proof that an OEM cluster displayed it.

## Unavailable is not rerouting

A live route can temporarily lack a usable selected instruction. OAL withdraws displayed guidance instead of sending a loading Trip that some GM clusters label **Rerouting**. Source-route ownership remains bounded and separate, so guidance can restart when the same route supplies a usable instruction. A deliberate host stop remains suppressed until the source route clears/replaces; missing data does not override that choice.

CarPlay route updates are deltas. An omitted current-instruction list preserves selection; an explicitly empty list clears selection. The selected definition can remain available during quiet portions of a route. Dynamic distance/time fields expire independently after 30 seconds without their own updates. The local source-route/cache ownership expires after 120 seconds without an accepted route update; maneuver-definition traffic alone cannot extend it. These are OAL policies, not claimed Apple protocol deadlines.

### Advancing CarPlay maneuver windows

The phone can send the next maneuver definition before changing the selected turn. OAL keeps an eight-entry insertion-order definition window and, separately, at most one displayed-current snapshot. Advancing the window retires its oldest definition rather than protecting the previous selection at the expense of the upcoming turn. The separate snapshot is display-only: selecting another ID cannot reuse it as a cached fallback. Explicit clear, route retirement and ownership expiry still withdraw the display.

### Sourced formatted distances

A complete, valid display distance can be supplied without numeric metres. OAL preserves that observation—including a real zero feet—in both Trip builders and the navigation screen. Invalid or partial display pairs fall back only to a valid sourced numeric distance. Missing distance and ETA remain unknown; they do not become an invented zero-distance turn or arrival time.

The AAOS host publication path additionally requires a valid sourced step distance before submitting a Trip. If it is missing or expires, OAL withdraws guidance while keeping bounded source-route ownership; a fresh valid distance can resume the same route. A destination without its own valid distance is omitted rather than borrowing the step distance. This avoids a null-distance failure found in an archived host serializer; it is not proof of the installed host's version or the cause of a particular vehicle disconnect.

## Maneuvers and pictures

- Prefer bounded, decode-validated artwork associated with the current instruction. New turn events clear omitted artwork and instruction-only fields, even when the maneuver type repeats; distance-only updates retain their association.
- Original fallback vectors cover recognized normal/slight/sharp turns, keep/fork/merge, ramps, left/right U-turns, clockwise/counterclockwise roundabouts, destinations, departure, ferry and name-change instructions. Geographic directions do not mirror with the UI locale.
- Keep a representable named AndroidX type authoritative. Generic roundabout entry/exit cannot carry an exit number; combined roundabouts require a positive sourced number. Unsourced circulation or ordinal uses a neutral roundabout instead of inventing a first exit or direction.
- Truly unknown instructions retain available text/estimates and current source artwork without fabricating a directional fallback.
- Direct-AA roundabout angle integers do not yet have a proved source unit/sign/reference convention. OAL does not invent a conversion into AndroidX's traveled-in-circulation angle. Existing bridge-provided raw angles are retained as source data; richer phone artwork can still depict the junction.

## Checks before calling a vehicle report resolved

While parked where possible, test both Android Auto and CarPlay independently:

1. Start and cancel a route; confirm no persistent Rerouting card after cancellation.
2. Navigate a quiet segment, then reach the next instruction. Confirm guidance remains or resumes without restarting projection.
3. Exercise roundabout entry, numbered exits and rerouting. Compare direction/ordinal with the actual phone instruction; upload logs on mismatch.
4. Confirm unavailable guidance clears rather than showing a zero-distance turn, while a fresh instruction resumes automatically.
5. Select native navigation deliberately and verify OAL does not fight that host stop. Start a new projected route to restore projected guidance.
6. Repeat after an ignition/session reconnect and on supported non-GM clusters.

Diagnostic markers distinguish `Guidance availability outcome=available/unavailable/build-failed/update-failed`, source route publication and host Trip submission. Failures are bounded and do not start a periodic reconnect/navigation-ownership storm. Physical host behavior, graphics and timing remain vehicle-specific acceptance gates.
