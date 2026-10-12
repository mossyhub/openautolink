# CarPlay metadata-only GM cluster handoff

## Outcome and scope

**Metadata without a second video stream is a real CarPlay contract.** Authenticated exact-Wi-Fi-link intake now publishes through OAL's existing `ClusterNavigationState` / `ClusterMainSession` and process `OalMediaSessionManager`. Host publication is implemented and CPU-tested; physical phone metadata delivery and GM rendering are not yet verified. No second screen, cluster video capability, fake screen, extra MediaSession, device upload/install or commit was added.

The legacy Android Auto media/position/art semantics remain unchanged; their writes now use the same cross-protocol metadata mutation gate as CarPlay. Owner reports establish that AA media/navigation work, with Google Maps the only tested AA navigation provider; this does not make their APIs Google-Maps-specific.

## Primary sources and exact source pin

1. Apple, [WWDC19 session 252, Advances in CarPlay Systems](https://developer.apple.com/videos/play/wwdc2019/252/), second-screen section: iAP2 Route Guidance provides route metadata to draw in native UI; iAP2 Now Playing provides tracks/podcasts. Apple explicitly keeps HUD guidance on iAP2 while discussing separate video streams. Metadata is a distinct path, not evidence that a cluster video stream must be advertised.
2. Apple, [CarPlay Developer Guide, 2026-06-08](https://developer.apple.com/download/files/CarPlay-Developer-Guide.pdf), “Share upcoming maneuvers with vehicle,” p.55: many vehicles without a fully digital cluster can receive maneuver metadata. Third-party maneuver sharing requires iOS 17.4+ and the app returning true from `mapTemplateShouldProvideNavigationMetadata`; app supplies maneuver/lane metadata. This is **provider opt-in**, not an Apple Maps package allowlist in the receiver.
3. Apple, [WWDC25 session 216, Turbocharge your app for CarPlay](https://developer.apple.com/videos/play/wwdc2025/216/), 16:28 “Add support for metadata”: navigation-app metadata is sent to the vehicle, which maps it to native visual types; example is the third-party Coastal Roads app. Installed Google Maps alone does not prove its exact version opts in.
4. Apple [CPManeuver](https://developer.apple.com/documentation/carplay/cpmaneuver): navigation metadata is separate from symbol/instruction assets used on projected screens. Public API docs do not specify iAP2 wire field IDs.
5. Local DiPlay pin: `/hermes_work/research/DiPlay`, commit `e2fd8eadf5a6b705666f292055e72aa93387533d`. Wire semantics below come from executable receiver source/tests, **not from a public Apple accessory specification**:
   - `shared/src/main/java/com/shilapi/xcertplay/iap2/message/Iap2Messages.kt:278-291` subscription bodies.
   - `.../transport/Iap2IdentificationClient.kt:281-291` route display component, `:301-326` message lists (OAL copied variant has shifted lines).
   - `.../media/CarPlayPlaybackStatus.kt:31-49,93-107` retained media fields and units.
   - `.../transport/Iap2FileTransferReceiver.kt:9-14,53-88,112-126` artwork datagrams and bounds.
   - `.../hud/BydHudRouteState.kt:111-179,220-225` guidance fields, terminal states, units, 30-second stale policy; `BydManeuverCodes.kt:25-49` maneuver vocabulary.
   - `shared/src/test/java/com/shilapi/xcertplay/glance/CarPlayGlanceTest.kt` executable 0x5201/0x5202 examples.

## Contracts recovered

| Contract | Sourced subset |
|---|---|
| Start / stop Now Playing | `0x5000` / `0x5002`; start requests group 0 IDs 1,4,6,12,26 and group 1 IDs 0,1,7 |
| Now Playing update | `0x5001`: group 0 title=1, duration=4 U32 milliseconds, album=6, artist=12, artwork transfer ID=26 U8; group 1 playback status=0 U8, elapsed=1 U32 milliseconds, source app=7 string |
| Update semantics | Omitted fields retain; present empty strings clear. Intake leaves unreported playback/position null, never infers playing from video/audio. Conservatively recognizes status 0/1 only; other values are not assigned invented meaning. Source app is not used as a provider allowlist or logged. |
| Artwork | Separate negotiated iAP2 file-transfer session, **not a CSM album-art message**. Datagram byte 0 U8 transfer ID; byte 1 low nibble opcode (Data=0, Start=1, Cancel=2, Setup=4, Success=5), Last flag=0x40; v2 Setup has BE U64 size at byte 2, BE U16 file type at byte 10. NowPlayingArtworkData file type=2. Existing receiver caps 2 MiB/transfer and four pending transfers. |
| Start / stop route guidance | `0x5200` / `0x5203`; start carries field 0 U16 component 42, void 1/2. Identification group 30 advertises the same component 42, current/post-maneuver name limits 64, storage capacity 8. This is a metadata display component, not an AirPlay video display. |
| Route update | `0x5201`: state=1 U8 (0 no route, 2 arrived); current road=3 string; remaining time=6 U64 seconds; remaining distance=7 U32 meters; next maneuver distance=10 U32 meters; current maneuver indices=13 U16 list. |
| Maneuver update | `0x5202`: index=1 U16, maneuver type=3 U8, post-maneuver road=4 string, traffic side=8 U8. Cache joins by index, no phone-app identity filter. |

Intake rejects malformed scalar sizes, duplicate fields, over-16-KiB metadata payloads and over-1024-byte strings. Eight cached maneuvers maximum; stale route expires after 30 seconds using monotonic elapsed time. An empty current list hides guidance immediately (a deliberate fail-closed deviation from DiPlay's transient grace); no-route/arrival clears cache. Retirement makes the object permanently inert. Separate link objects fence replacement generations; no cross-session retained route.

Completed art is accepted only after authentication and only for the currently reported transfer ID. Pre-reference or old-ID art is discarded rather than displayed on the wrong track. This may miss a cover if completion precedes its reference; phone ordering needs verification. U8 ID reuse within the same session cannot distinguish an old completion without stronger transport ordering; no invented track UUID exists.

## Existing firmware / GM investigation

Read `/hermes_work/research/carplay-ios/README.md` first. Reused iPhone16,1 iOS 27.0.1 `24A446` cache; no download/full extraction. `AccessoryNavigation` UUID `A09907AA-63EB-335E-97A6-C827285C742E`, `Navigation-iOS` UUID `F09B0E16-BAA1-354B-92F7-152815B6452B`, both 1216.2.2.0.0. Recovered headers expose component ID lists, start/stop route guidance, requested source-name/source-support flags and max maneuver capacity. Those declarations corroborate a component/provider abstraction but **do not prove wire IDs or Google Maps support**.

Bounded host probes used `GOMEMLIMIT=512MiB GOMAXPROCS=1`, foreground timeout, `--quiet` for disassembly. Research artifacts remain outside the app in `/hermes_work/research/carplay-ios/work/`:
- `navigation-metadata-objc.txt`, `navigation-metadata-symbols.txt`;
- `navigation-metadata-conversion-arm64.txt`: actual `ACCNavigationShim convertIAP2ACCRouteGuidanceInfo:forAccessory:` at `0x2430e907c` and maneuver conversion at `0x2430e91dc`; dictionaries preserve numeric keys through conversion. No full serializer recovered.
- `accessory-navigation-symbols.txt`, `accessory-navigation-types-arm64.txt`: `+[ACCNavigationRouteGuidanceUpdateInfo keyForType:]` at `0x2269c2f28`, numeric type jump table; external pointer/string resolution not completed. Do not promote this to a proven wire serializer.

GM corpus: `/hermes_work/openautolink/recon_dump/apks/com_gm_rhmi/java_src/com/p016gm/rhmi/audio/carmedia/MediaItemMetadata.java:105` consumes standard MediaMetadata title/artist/album/description, not Apple Maps-specific data. GM navigation selector/presenter chain and separation from full-map video are documented in the existing `automotive-infotainment-research` skill reference `gm-cluster-map-selection-and-host-lifetime.md`; source path is `com_gm_domain_server_delayed/.../navigation/cluster/NavigationClusterService.java` and `NavigationSelector.java`. None of this grants an ordinary app native CarPlay privileged service access; the intended path remains OAL's existing AndroidX NavigationManager and process MediaSession.

## Implemented seams

- New `CarPlayMetadataIntake.kt`: authenticated bounded retained media/navigation parser and referenced art intake.
- New `CarPlayMetadataIdentification.kt`: strips Now Playing/route component and their subscriptions from Bluetooth bootstrap only; Wi-Fi runtime retains the pinned metadata identity and subscribes after its own MFi authentication. This prevents a second long-lived metadata binding on RFCOMM; real-phone acceptance of the changed bootstrap identity remains a gate.
- New `RuntimeMetadataLink.kt`: exact runtime lifecycle interface.
- App `OalCarPlayMetadataLink.kt`: one constant-space coalescing worker per exact authenticated runtime link; no bitmap decode or host effects on the artwork link pump. Revoke prevents subsequent snapshots; close awaits an admitted worker before exact-owner host retirement. Safe receipts contain counters/booleans only, with `hostPublication=true` emitted after a host publication call returns, not merely because a factory exists. This is not a GM-render receipt.
- `CarPlayClusterMetadataAdapter.kt`: provider-agnostic mapping into existing `ManeuverState`, no nav-image data; absent distance/next-turn ETA remain null. Trip remaining time is **not** mislabeled as time to next turn.
- Minimal `ProjectionReceiver` hooks: metadata object per exact runtime tunnel; authenticated after MFi and AirPlay control authentication, before existing subscriptions; one existing CSM receive loop dispatches metadata; existing link-pump artwork callback forwards only to exact ready owner; tick/revoke/close follow runtime retirement.
- Minimal `OalCarPlayRuntime` factory installs/owns the exact metadata link. Runtime close retries/awaits its retirement before the existing coordinator acknowledges CP retirement and releases AA admission. Existing multitouch methods, HEVC negotiation, display/codec/settings/UI files were not changed by this host-publication worker.

## Implemented host publication and ownership

- `OalCarPlayMetadataHost` claims one exact-link token only after authentication, reads existing cluster enabled/distance-unit preferences, reuses the process MediaSession, and creates the existing generation-aware `ClusterManager` only when cluster navigation is enabled. Navigation snapshots/terminal/stale clears flow through `ClusterNavigationState`; manager recovery continues using its existing route demand. No phone-app filter is applied.
- `MetadataHostOwnership` spans CP admission, host writes, and retirement effects with one monitor for **both** cluster and MediaSession. Legacy AA entry points retain their old payload behavior but reject writes while CP owns the host. An old CP clear cannot erase a newer AA state. A throwing retirement retains admission; coordinator AA reentry still requires awaited runtime close. CP reconnect uses a new exact-link token.
- `ClusterMainSession` preserves null next-step arrival/distance. AndroidX 1.7.0's public `TravelEstimate.Builder` rejects null fields even though its serialized model/getters explicitly permit them. `ClusterTravelEstimate` uses the library's private serialization constructor/kept protocol fields for unknown estimates, with explicit R8 member retention. Tests exercise the **actual packaged AndroidX model**, asserting null arrival/distance and `REMAINING_TIME_UNKNOWN`, not an epoch/arrival-now sentinel. Known AA step estimates still take their legacy public-builder path. This pinned-library compatibility seam must be re-tested on AndroidX upgrades and on the GM host; unknown-estimate GM render acceptance remains physical QA.
- `OalMediaSessionManager.updateCarPlay` takes a retained nullable media snapshot plus explicit `clearArtwork`. It writes metadata and state atomically under the existing session lock: unreported state is `STATE_NONE`, unreported elapsed position is `PLAYBACK_POSITION_UNKNOWN`, not paused/zero. Null title/artist/album are omitted, not synthesized. Album art is bounded to 2 MiB input; a bounds-only decode rejects invalid or >16384 dimensions before sampled ARGB pixel allocation, then caps publication at 320 px. Legacy AA Base64 caching/nudges are preserved.
- Track title/artist/album change clears retained old cover; without a new explicit artwork transfer reference, the old ID is also discarded. Referenced new art is accepted only by the exact authenticated session. This deliberately favors blank art over wrong art. Same-title/artist/album track changes and same-session U8 reuse cannot establish a unique track identity without stronger sourced ordering; rapid changes remain a phone test.
- Existing cluster diagnostic lines no longer emit road names for these host writes. No raw media/art/address/phone identifiers are logged.

## Execution evidence

All publication Gradle gates ran on the host mirror with JDK 17 / Android SDK, serialized `flock /mnt/Temp/Hermes_Work/.oal-gradle.lock`, `--no-daemon --max-workers=1`, `-Xmx768m -XX:MaxMetaspaceSize=384m -XX:ActiveProcessorCount=2`, Kotlin in-process. No devices, uploads or commits.

Compiled behavioral REDs: `metadata-publication-red1.log` (unknown estimates), `red2` (shared host ownership/blocked retirement), `red3` (nullable CP media/explicit art clear), `red4` (authenticated async host publication), `red5` (artless track change/unknown-distance adapter). Each was followed by actual GREEN execution. Additional downstream publication, sampled artwork, and blocked-write/close tests exercise the integration.

`metadata-publication-focused.log` forced `--rerun-tasks`: **21 app + 7 receiver tests**, zero failures/errors/skips (archived XML in `/mnt/Temp/Hermes_Work/metadata-publication-focused-results/`).

`metadata-publication-full.log` forced unfiltered combined suites plus `:app:compileReleaseKotlin`: **1013 app tests, zero failures/errors, one pre-existing skip; 265 receiver tests, zero failures/errors/skips**; BUILD SUCCESSFUL. After adding an extra blocked-write/awaited-close assertion, `metadata-publication-final.log` ran 1014 app tests with one intermittent sibling failure (`VehiclePermissionRefreshTest.synchronousBatteryErrorInvalidatesInitialRead`, missing EV_BATTERY_LEVEL map key), while all metadata and receiver tests passed. No battery/vehicle files were changed. Unfiltered `metadata-publication-final-rerun.log` actually reran the test task: **1014 app tests, zero failures/errors, one skip**, BUILD SUCCESSFUL. This rerun does not establish the sibling failure's root cause. Release compilation was exercised in the forced full run; it is not R8/device execution proof.

## Phone/car acceptance matrix

Parent should run **Apple Maps and Google Maps separately** on the actual iPhone, recording app/iOS version and observed runtime metadata receipt. A Google Maps absence is a provider-support result until exact phone-side source/opt-in is established, not reason to hardcode Apple Maps in OAL.

Physical acceptance still required:
- Start route, left/right/straight turns, distance countdown, reroute, cancel, arrival; verify standard GM TBT card and unchanged main video. Confirm no additional video SETUP stream.
- Music and podcast title/artist/album, duration/position/status; art transfer, artless track, rapid track changes and U8 transfer-ID reuse. Cover must never carry to another track.
- Disconnect/reconnect, cancel during authentication, late artwork after retirement, CarPlay-to-AA return and new AA track/route. Old CP clear must never erase new AA state; MediaSession token must remain process-stable.
- Observe phone wire intake separately from GM render, routing permission and focus. A `hostPublication=true` receipt proves an app host publication call returned; it must not be reported as GM cluster render success. Observe the actual native card/media tile independently.
