# CarPlay ordinary Play and source-return recovery

There is no separate **Resume audio** button. Use the existing vehicle/CarPlay Play controls. The normal native MediaSession Play and exact connected-phone browse-row Play retain the authenticated receiver command queue and genuine MEDIA focus-request path.

## Renderer state is not phone playback truth

- Native Pause/STOP, permanent MEDIA focus loss, denial and real primary retirement hold the host transport publication at PAUSED. Phone NowPlaying truth, title and artwork remain independent and are not rewritten to pretend that the phone paused.
- Repeated phone PLAYING metadata cannot lift that hold or request focus. Only a valid current MEDIA grant can restore normal host playback publication. A cancelled grant, stale renderer completion or another purpose's grant cannot do so.
- This fixes a source-backed suppression boundary: the retained GM `MediaConnectorService` returns without sending Play when the selected controller reports PLAYING. With a truthful nonplaying renderer state and host autoplay enabled, it sends ordinary Play. Autoplay disabled/NEVER does not authorize a focus request. The fixture follows the retained connector branch and invokes OAL's installed callback; it is not proof of the installed vehicle's autoplay configuration.
- Native Play still respects STOP/owner revocation, independent call/Siri owners and failed focus retirement. Borrowed alternate-navigation gain follows the MEDIA owner, so a valid recovery restores both music and navigation at the local AudioTrack boundary.

## Bounded phone-transition policy

The existing process-only phone paused→playing recovery experiment remains **off by default**. Playback metadata does not distinguish a person's phone Play from autoplay. This repair does not silently authorize metadata to take the vehicle source.

For an explicitly enabled, visible, current NORMAL owner, empty-slot fresh SETUP no longer issues a false output-retirement callback that cancels a next-stream phone permit. A paused observation can survive ordinary media/alternate-output retirement; its permission cannot. True must be newly stamped in the current output generation. STOP, owner/selection/visibility changes, communication mode and live call/Siri admission still invalidate prior authority; unknown/repeated TRUE, packets and SETUP do not create retries or HID Play.

If automatic recovery must also work from phone-only Play when no native Play is sent, the product must explicitly accept the bounded metadata-transition policy's indistinguishable-autoplay risk, or supply an independently sourced semantic Play signal. No extra button is substituted for that decision.

## Evidence and remaining physical acceptance

Owner reported that build550's former explicit recovery restored both music and navigation. That demonstrates a working recovery path in that tested interval, not automatic source-return acceptance. The task's initial retained550 log (`055212Z`, `22-46-46`) records car-service STOP at22:47:16, permanent LOSS, then PLAY at22:47:31 with a genuine user-play grant and gain100; controller=unknown does not identify which UI action originated it.

Required vehicle acceptance remains same-session music+navigation → FM/other source → OAL, actual ordinary callback disposition, new MEDIA grant, then owner-audible music **and** navigation. Repeat with phone PLAYING throughout and with phone Pause/Play. Denial and STOP must stay silent. There is no claim that this fixes the separately observed Bluetooth-associated pause loop, caller identity, microphone behavior or any GM source-policy configuration.

Finite callback-entry counters in the manually uploaded `media_readiness.log` now distinguish direct Play, current/other/null browse ID, not-ready/stale rejection, admitted Play and prepare-only callbacks. Arbitrary IDs and controller identities are not retained. These counters observe OAL's boundary, not OEM source selection or human audibility.
