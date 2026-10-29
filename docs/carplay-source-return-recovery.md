# CarPlay ordinary Play and source-return recovery

There is no separate **Resume audio** button. Use the existing vehicle/CarPlay Play controls. The normal native MediaSession Play and exact connected-phone browse-row Play retain the authenticated receiver command queue and genuine MEDIA focus-request path.

## Renderer state is not phone playback truth

- Native Pause/STOP, permanent MEDIA focus loss, denial and real primary retirement hold the host transport publication at PAUSED. Phone NowPlaying truth, title and artwork remain independent and are not rewritten to pretend that the phone paused.
- Repeated phone PLAYING metadata cannot lift that hold or request focus. Only a valid current MEDIA grant can restore normal host playback publication. A cancelled grant, stale renderer completion or another purpose's grant cannot do so.
- This fixes a source-backed suppression boundary: the retained GM `MediaConnectorService` returns without sending Play when the selected controller reports PLAYING. With a truthful nonplaying renderer state and host autoplay enabled, it sends ordinary Play. Autoplay disabled/NEVER does not authorize a focus request. The fixture follows the retained connector branch and invokes OAL's installed callback; it is not proof of the installed vehicle's autoplay configuration.
- Native Play still respects STOP/owner revocation, independent call/Siri owners and failed focus retirement. Borrowed alternate-navigation gain follows the MEDIA owner, so a valid recovery restores both music and navigation at the local AudioTrack boundary.

## Bounded phone-transition policy

The owner explicitly accepted the indistinguishable-autoplay tradeoff: recovery is enabled in the normal production path, without visiting Diagnostics or resuming an Activity. A fresh authenticated phone paused→playing edge may interrupt another selected native source. OAL has no ordinary-app OEM selected-source readback; connection/foreground-service admission is not evidence that OAL is the selected audio source.

Each edge funds at most one MEDIA focus attempt for the exact current authenticated receiver, command, output and NORMAL host lease, while the connection's foreground-service protection remains eligible. Denial consumes the opportunity; repeated TRUE, packets and SETUP cannot replenish it. A later fresh false→true may renew it, including after an earlier STOP or LOSS. Stale observations captured before STOP/LOSS cannot mint newer authority. No HID Play is synthesized.

STOP, new permanent LOSS, owner replacement and connection-protection loss revoke queued or pending authority before Binder completion/publication. A fresh edge admitted during live call/Siri membership defers MEDIA once until that interaction retires rather than stealing its focus. A genuine current MEDIA grant alone restores music/NAV gain and native PLAYING publication. There is no new toggle or Resume button.

## Evidence and remaining physical acceptance

Owner reported that build550's former explicit recovery restored both music and navigation. That demonstrates a working recovery path in that tested interval, not automatic source-return acceptance. The task's initial retained550 log (`055212Z`, `22-46-46`) records car-service STOP at22:47:16, permanent LOSS, then PLAY at22:47:31 with a genuine user-play grant and gain100; controller=unknown does not identify which UI action originated it.

Required vehicle acceptance remains same-session music+navigation → FM/other source → OAL, actual ordinary callback disposition, new MEDIA grant, then owner-audible music **and** navigation. Repeat with phone PLAYING throughout and with phone Pause/Play. Denial and STOP must stay silent. There is no claim that this fixes the separately observed Bluetooth-associated pause loop, caller identity, microphone behavior or any GM source-policy configuration.

Finite callback-entry counters in the manually uploaded `media_readiness.log` now distinguish direct Play, current/other/null browse ID, not-ready/stale rejection, admitted Play and prepare-only callbacks. Arbitrary IDs and controller identities are not retained. These counters observe OAL's boundary, not OEM source selection or human audibility.
