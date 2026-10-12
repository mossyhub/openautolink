# Unified dual-protocol OAL product design

**Status:** Proposed architecture and implementation handoff; not yet implemented or ready to ship.

**Owner-approved rollout direction:** Use the existing OAL application/package and existing Play test track, initially behind an explicit **Enable CarPlay (experimental)** toggle that defaults **off**. With the toggle off, retain existing Android Auto behavior, do not start CarPlay discovery/listeners/bootstrap, and do not request its optional microphone consent. With it on, automatic protocol selection is the target; it must not turn every connection into a manual protocol choice. Preserve AA preferences and require exact-owner retirement when disabling CarPlay. This is not authorization to upload now: first reach enough verified bench functionality to justify testing network, audio/routing and vehicle integration in the owner's actual car. Keep all other testers on their existing behavior unless they deliberately opt in.

**Goal:** After one-time setup, entering the car connects an eligible iPhone to CarPlay or an eligible Android phone to Android Auto without choosing a protocol or a transport each trip. WPP is the Android wireless default; USB remains a supported, separate path.

**Owner clarification — coexistence:** In the final product, Android Auto and CarPlay are enabled together; neither is a mutually exclusive operating mode. Keep both protocols' settings accessible, grouped clearly within the existing tabs, regardless of which phone is projecting. The default-off experimental CarPlay gate is temporary for the initial car-testing rollout, not the intended final UX. Select the trusted eligible phone/protocol from actual car Bluetooth connection and protocol eligibility, never its friendly name; gracefully retire the previous projection owner before admitting the next. GM's Bluetooth application owns phone selection and connection priority, including when multiple paired phones are nearby. OAL in WPP mode follows that OEM choice; it must not introduce its own phone ordering, competing selection, or takeover. Protocol enrollment identifies how to serve a phone, not which phone the car should connect. Ambiguous profile observations remain unknown rather than becoming an OAL-selected winner. Both protocols being enabled does not mean rendering two sessions simultaneously. Current manual CarPlay Connect/Return controls are a test-stage limitation, not completion of this automatic selection requirement.

**Architecture:** One process-owned projection coordinator admits one phone/protocol/transport owner, and protocol-specific adapters retain their own wire implementations. Compose presents a shared connection/display shell and capability-filtered protocol settings, not an Android Auto settings screen with CarPlay switches appended.

**Stack:** Existing Kotlin/Compose/Navigation/DataStore/coroutines, Android MediaCodec/AudioTrack/Bluetooth/NSD, AASDK C++/JNI for AA; experimental Kotlin iAP2/AirPlay implementation for CarPlay.

> **Implementation handoff:** Use the writing-plans and subagent-driven-development workflows to turn the gated work packets below into test-first implementation. This document does not authorize production edits, builds, installation, credentials provisioning, or releases.

## Integration direction clarified by owner

Use the actual OAL Application, MainActivity, Compose projection/settings shell and lifecycle on a branch from current main. A tablet-compatible variant may relax automotive hardware requirements, but must not replace the product entry point with BenchActivity. Preserve the existing bench work as receiver research and reusable library code, not the final UI.

Tablet testing may use explicit Advanced → Connection overrides for an existing/shared Wi-Fi interface, selected phone endpoint, and privately stored Wi-Fi configuration. Vehicle mode targets the car AP with the correct interface/scoped address. This difference is acceptable to the owner and is not a reason to maintain a separate bench application. Keep CarPlay overrides separate from saved AA/WPP settings; never silently fall back to another interface or equate shared-LAN success with vehicle network proof. Existing OAL already has manual IP testing and network-interface overrides; reuse the settings conventions, not AA-specific transport assumptions.

## 1. Scope and evidence boundary

Inspected the actual working tree at `/hermes_work/oal-carplay-bench`, branch `feat/carplay-bench-first-auth`, base HEAD `c5cc3bfa4e3aa867d3c39e15046925399d4df81b`. Working-tree bench changes are material: HEAD alone does not describe the CarPlay experiment. This document describes the inspected files, not another branch or `/hermes_work/openautolink`.

Only this document is a deliverable. No production code edits or builds were performed for this design. Paths below are repository-relative; prefix every path with `/hermes_work/oal-carplay-bench/` for the exact absolute path. Existing files and proposed files are explicitly distinguished.

Evidence labels:

- **Tree:** executable source/dependency/route inspected here; does not prove runtime behavior.
- **Retained evidence:** repository documentation reports prior vehicle results; not a new reproduction in this task.
- **Delegation context:** first native CarPlay H.264 1280×720 frame works after the AirPlay `sourceVersion` correction to `950.7.1`; no independent runtime replay in this design task.
- **Target:** behavior specified below but not present in the current product.

Keep these network evidence tracks separate:

1. **TCL/home-network bench:** current `BenchActivity` uses selected phone IPv4 and `wlan0` IPv4 offers on a shared network. First-frame success is scoped to that experiment.
2. **Nonprivileged GM/car AP:** separate scoped-IPv6 reachability premise and OEM topology investigation. It is not proven by home IPv4 rendering. Current AA WPP uses selected-interface IPv4 plus the companion relay. CarPlay has not thereby acquired a proven GM direct-inbound path.

No screenshot, personal phone name, address, SSID, passphrase, certificate, challenge, signature, or private key is reproduced in this document.

## 2. Current product: exact inventory

### 2.1 Build and entry-point separation

| Existing path | Current responsibility / dependency |
|---|---|
| `settings.gradle.kts` | Includes `:app`, `:companion`, and working-tree `:carplay-lab`. |
| `app/build.gradle.kts` | Real car application; `carplayBench` derives from debug. Only `carplayBenchImplementation(project(":carplay-lab"))` adds the experimental library. Normal debug/release do not acquire CarPlay through that dependency. |
| `carplay-lab/build.gradle.kts` | Android **library**, not the shipping car application; SDK 36/min 32, Java/Kotlin 17, Bouncy Castle `bcprov-jdk18on:1.79`, JUnit `4.13.2`. |
| `app/src/main/AndroidManifest.xml` | Normal AAOS app entry points, USB attachment, cluster and media integration. |
| `app/src/carplayBench/AndroidManifest.xml` | Replaces `OalApplication` with plain `android.app.Application`; removes MainActivity, USB attachment Activity, cluster/media services and SettingsReceiver; launches `BenchActivity`. This bench does not run normal AA startup alongside CarPlay. |
| `app/src/main/java/com/openautolink/app/OalApplication.kt` | Process VHAL runtime, pre-wake observation, AA Bluetooth control and process MediaSession initialization. Some comments predate current WPP admission: executable owner checks are authoritative. |
| `carplay-lab/src/main/java/com/openautolink/carplaylab/BenchActivity.kt` | Explicit phone selection, network fields and test buttons; owns RFCOMM/iAP2 bootstrap, activation, pairing probe/media receiver and cleanup. Not seamless production discovery. |

Do not move the library dependency to unconditional `implementation` or restore normal Application startup in the bench as a shortcut. That would combine independently-owned audio, listeners and lifecycle systems before ownership exists.

### 2.2 AA transports and discovery are not the same setting

| Existing mode / paths | Actual behavior | Product recommendation |
|---|---|---|
| `direct_transport = wpp`: `transport/bluetooth/AaWirelessBtControl.kt`, `AaWirelessBtServer.kt`, `WppSessionAdmission.kt`, `transport/hotspot/WppTcpServer.kt` beneath `app/src/main/java/com/openautolink/app/` | Factory-style Bluetooth/WPP startup. A selected-interface listener is bound first. Standard topology permits phone→HU TCP; current GM flow advertises companion phone-local loopback proxy, then car dials that companion through the selected interface. Both feed the AA native session. | Default Android wireless path after setup readiness is satisfied. Preserve production wire behavior during first UI migration. |
| `direct_transport = hotspot`: `transport/hotspot/TcpConnector.kt`, `transport/PhoneDiscovery.kt`, `ui/projection/ProjectionViewModel.kt` | Legacy outbound car→companion TCP. Independent `connection_mode` selects `car_hotspot` or `phone_hotspot`; this is network topology, not another projection protocol. Manual IP is a diagnostic override. | Compatibility-only, never automatic fallback for a failed current WPP handshake. |
| `direct_transport = usb`: `transport/usb/UsbConnectionManager.kt`, `UsbAccessoryMode.kt`, `UsbTransportPipe.kt`, `UsbTransportOwnership.kt`, `UsbAttachedActivity.kt` | AOA v2 device selection/permission/re-enumeration, exact pipe detach retirement, same AASDK session with a USB pipe. No legacy wireless launch intent is required by this transport. | Keep wired AA. Remove it from the top-level mode selector, not from implementation. |
| Saved `nearby` | `data/AppPreferences.kt:490–495` maps the old value to `hotspot` on read. | Preserve legacy migration explicitly; no new Nearby option. |
| Historical SBC/bridge | Retained bridge tree/docs; `docs/testing.md` marks that environment retired. Normal app uses native AA directly. | No bridge/SSH/OTA page in the primary product UI. Retain maintainer documentation outside the everyday flow. |

All abbreviated paths in this table are under `app/src/main/java/com/openautolink/app/` as stated. Key existing seams:

- `app/src/main/java/com/openautolink/app/data/AppPreferences.kt`: **actual new-install transport default is still `hotspot`**, not WPP. `connection_mode` defaults to `car_hotspot`; WPP AP interface defaults to `ap_br_swlan0`. Changing only a label does not change these defaults.
- `app/src/main/java/com/openautolink/app/transport/OalProtocol.kt`: companion AA TCP `5277`, phone-local WPP proxy `5280`, identity TCP `5278`, UDP discovery `5279`, mDNS `_openautolink._tcp`.
- `app/src/main/java/com/openautolink/app/transport/PhoneDiscovery.kt`: singleton mDNS, UDP and subnet-sweep discovery with companion identity receipts; selected-interface constraint and source-bound probes. Discovery is not permission to start projection.
- `app/src/main/java/com/openautolink/app/transport/WppInterfacePolicy.kt`: selected interface and its live IPv4/prefix are authoritative. Missing interface never falls back to another NIC.
- `app/src/main/java/com/openautolink/app/transport/bluetooth/CompanionIdentityPolicy.kt`: expected stable companion ID takes precedence; bootstrap matching may use reported Bluetooth name or friendly-name fallback. Name equality is not cryptographic authentication.
- `app/src/main/java/com/openautolink/app/data/KnownPhonesStore.kt`: stable companion IDs and labels, intentionally no persisted IP cache. Existing read-modify-write operations are not a transaction across the whole registry.
- `app/src/main/java/com/openautolink/app/ui/projection/ProjectionViewModel.kt:933–962`: misleadingly named `usbTransportActive` suppresses legacy wireless discovery for **both USB and WPP**. Do not re-enable all old chooser/autodial behavior merely to discover two protocols.

### 2.3 Existing WPP admission and projection ownership

`app/src/main/java/com/openautolink/app/session/SessionManager.kt` owns the AA session, decoder/audio/microphone/forwarders and streaming services. It snapshots settings, installs the discovery interface constraint, creates session dependencies, starts transport, waits for the selected WPP listener to bind, then installs wireless admission. `transportStartInterruptGeneration` fences the final admission against interrupted startup.

`app/src/main/java/com/openautolink/app/transport/bluetooth/WppSessionAdmission.kt` gives the current protocol session an exact identity token with generation, transport and immutable WPP interface. `AaWirelessBtControl.installSessionOwner`/`clearSessionOwner` gate process SDP. Preferences are not the active owner. `startFromPreferences` waits for the selected interface and rejects missing IPv4 rather than advertising another NIC.

`AaWirelessBtControl` separately claims a Bluetooth phone, maps it to companion identity/address, and invokes `onCompanionSelected`. `WppBootstrapPolicy.kt` suppresses redundant activation once streaming and handles late companion discovery. `withIdentityValidatedCompanion` is restricted to the claimed phone on the selected subnet, not the global nearby address list. Its current claim policy includes a claim-age condition; the target below must not let elapsed age alone preempt a healthy stream.

`app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSession.kt` branches `usb`/`wpp`/TCP and owns connectors, native generation, reconnect jobs, native callbacks and pipe retirement. Current recovery invokes unqualified `AaWirelessBtControl.readvertise()` in some paths. A future coordinator must carry the originating session token through delayed work, not sample a replacement owner at execution time.

`app/src/main/cpp/aasdk_jni.cpp` currently has process-global `gSession` and `gSessionGeneration`; ordinary stop is global, not an exact-generation stop API. Some media/mute commands already accept expected generation. This is an integration hazard, not evidence that a generalized cross-protocol owner already exists.

**Bottom line:** AA has real admission fences, but no shared AA/CarPlay admission authority. CarPlay can bypass all these fences because it lives in a separate Activity/variant. Neither a saved preference nor `SessionState.IDLE` establishes native retirement.

### 2.4 Actual settings routes and bulk

`app/src/main/java/com/openautolink/app/ui/navigation/AppNavHost.kt` declares `projection`, `settings`, `diagnostics`, `safe_area_editor`, but registers **projection and safe_area_editor** as destinations. Settings and diagnostics are slide-in overlays supplied to `ProjectionScreen`; the old settings navigation callback is a no-op. Do not design against imagined existing standalone settings routes.

- `app/src/main/java/com/openautolink/app/ui/projection/ProjectionScreen.kt`: surface/touch geometry, overlays, unconditional Settings button and USB picker visibility. Settings stays reachable even when connection/picker is broken.
- `app/src/main/java/com/openautolink/app/ui/settings/SettingsScreen.kt`: seven rail tabs: Connection, Display, Video, Audio, Input, EV, Diagnostics. Connection contains transport chips, WPP credentials/interface/config listener, topology modes, known phones and manual IP. Display mixes shell geometry, AA insets, drive side, sensors, cluster and AA status chrome. Video includes GAL/PDK, codec, frame rate, resolution, seed-IDR thresholds, density, margins, decoder depth and pixel aspect.
- `app/src/main/java/com/openautolink/app/ui/settings/SettingsViewModel.kt`: maps many preference flows, WPP config transfer and `saveAndReconnect()`; reconnect reads a large AA-specific argument list.
- `app/src/main/java/com/openautolink/app/ui/settings/SafeAreaEditorScreen.kt`: existing visual inset editor; reusable interaction, not evidence of identical protocol wire semantics.
- `app/src/main/java/com/openautolink/app/ui/settings/EvEnergyModelScreen.kt` and `EvEnergyModelViewModel.kt`: existing embedded EV tab, AA energy model controls.
- `app/src/main/java/com/openautolink/app/ui/diagnostics/DiagnosticsScreen.kt` and `DiagnosticsViewModel.kt`: existing diagnostic surface; retain detailed information behind help/advanced rather than as everyday setup.

### 2.5 CarPlay closure currently available

Existing sources:

- `carplay-lab/src/main/java/com/openautolink/carplaylab/pairing/PhoneActivation.kt`: selected-IP-only `_carplay-ctrl._tcp.` resolution/activation, bounded sockets and revocation. Activation accepts numeric scoped IPv6 and validates matching scope. It does not make the rest of the bench scoped-IPv6 capable.
- `carplay-lab/src/main/java/com/openautolink/carplaylab/EndpointOffer.kt`: actual bound endpoint and Wi-Fi parameter replies. Its bootstrap `sourceVersion = "1.0"` is distinct from AirPlay `/info`; do not blanket-replace version strings.
- `carplay-lab/src/main/java/com/openautolink/carplaylab/pairing/PairingPersistence.kt`, `PairingProbeServer.kt`: persisted identity/pairings and exclusive state ownership. Pairing probe must close before media ownership.
- `carplay-lab/src/main/java/com/openautolink/carplaylab/media/ProjectionReceiver.kt`: caller-owned valid Surface and MFi provider required; preexisting pairing state required; selected peer admission followed by AirPlay authentication; dynamic listener port, iAP2 runtime tunnel and cleanup.
- `carplay-lab/src/main/java/com/shilapi/xcertplay/airplay/AirPlayConfig.kt`, `AirPlayInfoPlist.kt`, `ScreenStream.kt`, `CarPlayMediaEngine.kt`: protocol and offer implementation.
- `carplay-lab/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt`: **H.264-only video sink**; `onVideoCodec` rejects non-H264. AAC/Opus platform decode and LPCM output code exist, but code presence is not audible runtime proof.
- `carplay-lab/src/main/java/com/shilapi/xcertplay/media/MediaCodecSupport.kt`: AVC and HEVC byte helpers exist; HEVC helper presence does not enable HEVC in the sink.
- `carplay-lab/src/main/java/com/shilapi/xcertplay/mfi/LocalMfiAuthenticationClient.kt`: experimental local identity provider; private provisioning remains a prerequisite, never a bundled product credential. Valid local signing is not itself iPhone trust or distribution authority.

`ProjectionReceiver` currently offers `sourceVersion="950.7.1"`, 1280×720 at 30 FPS, `hevc=false`, `microphone=false`, no cluster, no video-in-car and no buffered main audio. `BenchActivity` still requires selected IPv4 and sends `wlan0` IPv4 endpoints. `MediaPolicy.kt` rejects scope suffixes. Wildcard `::` listener binding is not proof of GM AP reachability or correct interface-scoped admission. First-frame evidence does not establish touch, audible media/guidance, Siri microphone, calls, switching, ignition reconnect, HEVC, higher resolutions or production integration. Parent-owned touch/audio experiments are outside this document's verified claims.

## 3. Audit: may AA transports other than WPP be removed?

**Recommendation: remove legacy transport selection from the primary UX; do not delete USB or perform broad backend removal now.**

Evidence read in this tree:

1. `docs/wireless-wpp.md` describes AA 17.4 disabling the exported wireless-start receiver and documents current WPP/companion operation.
2. `companion/src/main/java/com/openautolink/companion/service/TcpAdvertiser.kt:809–859` still attempts `WirelessStartupReceiver` broadcast and `WirelessStartupActivity` for the legacy launch route. Sending an intent successfully is not proof Gearhead accepted it.
3. `docs/research/wpp-phone-hotspot-feasibility.md` scopes research to Gearhead `17.4.663004-release` and separates station Wi-Fi/WPP constraints from USB. Its topology table explicitly lists USB as working and rejects the projecting phone hosting its own AP for stock WPP.
4. `docs/experimental-gal6.md:72–79` retains actual 0.1.454 USB and wireless HEVC/2560×1440 results. `docs/compatibility.md` likewise retains USB validation, alongside issue reports on other configurations.
5. Current `AasdkSession.startUsb()` and USB manager/pipe code are separate from the obsolete wireless launch route.

Therefore the strong claim supported here is **“WPP is the supported current stock wireless cold-start path in the analyzed/reported AA versions.”** It is not **“recent AA only projects over WPP”**, nor **“USB is unusable”**, nor a fresh audit of every later Gearhead build. The broad comment in `AppPreferences.kt` and broad phone compatibility wording in `docs/compatibility.md` should later be narrowed to wireless startup.

| Mode | UX policy | Backend removal gate |
|---|---|---|
| WPP | Android wireless default; setup only when incomplete. | No removal; protect regression baseline. |
| USB/AOA | “Use wired Android Auto” in phone/connection context. Optional cable support entry, not global protocol chooser. | No deletion proposed. Require permission, attach/detach, re-enumeration, native retirement and current-phone acceptance tests. |
| Legacy shared-Wi-Fi TCP | Advanced → Android Auto → Compatibility connection; explicitly historical startup support. | Keep saved configurations initially. Delete only after usage/support audit, tested replacement, consented migration and rollback support. |
| Projecting-phone hotspot legacy mode | Hide for new setups; flag existing profiles as legacy, never offer as WPP fallback. | Same staged removal gate; a travel router/second-phone AP is a different shared network, not this mode. |
| Manual IP | Maintainer diagnostic override tied to the compatibility profile, not seamless product setup. | Keep gated for lab/support until identity-aware endpoint diagnostics replace it. |
| Nearby/SBC remnants | No top-level options. | Remove only independently dead, tested code, not files that current diagnostics/tests still depend on. |

Do not fork version-string heuristics into every UI screen. A capability record can distinguish `supported`, `legacy`, `unknown`, `blocked` with evidence basis. No network/intent attempt may quietly switch wireless modes after a failure. Never automatically enable legacy companion `CarWifiManager` association during WPP: Gearhead owns station association in this mode.

## 4. Target everyday UX

### 4.1 Connection home

One calm screen, not a protocol dashboard:

```text
OpenAutoLink                                  Settings

[Phone label] · CarPlay / Android Auto
Connecting… / Reconnecting… / Ready to connect

[Connect / Retry / Change phone]   [Help, only on failure]

Other phones (collapsed unless a choice is needed)
```

The protocol label is an outcome, not a selector. Show status stages users can act on: Bluetooth unavailable, vehicle Wi-Fi not ready, companion unavailable, paired phone authentication needed, display unavailable. A handshake/RECORD status must not read “Connected and working”; only accepted owner plus rendered/video-ready evidence promotes projection status. Audio readiness is separate.

- First setup asks about the phone only when discovery cannot establish a trusted eligible identity. iPhone selects CarPlay capability; Android selects AA capability. Do not infer protocol from a friendly name, Apple-looking icon or MAC vendor alone.
- Previously trusted phone → use its verified protocol and profile automatically. Unknown device → pairing/onboarding; no blind attempts to both protocols.
- During a stream, hide setup/status chrome; keep the unconditional Settings escape and an optional Change phone affordance. Do not build a second permanent toolbar over the projected app.
- No foreground phone picker on every wake or first transient error. Keep reconnect inline; show choices only for unresolved equal candidates or explicit user action.
- Setup is one-time, not magically zero-touch provisioning. Normal OS Bluetooth/USB permission and iPhone trust prompts may still be necessary in the eventual product. This document requests no user interaction now.

### 4.2 Settings information architecture

Replace seven always-visible technical tabs with four primary destinations in the existing overlay shell:

| Primary section | Everyday controls | Disclosure |
|---|---|---|
| **Phones** | Trusted phones; current/preferred labels; automatic connect (on by default for trusted phones); Change phone; Add/Forget phone; wired AA action when present. | Per-phone protocol/capabilities, setup completeness, compatibility connection only for Android profiles. |
| **Display & controls** | System bars/fullscreen; fit/crop; visual screen-fit editor; driving side; optional overlay buttons; steering-wheel mapping when supported. | Protocol video/geometry tuning nested by selected phone/protocol, not shared raw fields. |
| **Sound** | Supported media/guidance level offsets and microphone setup when actually available. | Protocol audio details and OEM phone-call guidance. Hide unsupported mic controls rather than advertise a disabled implementation. |
| **Help & advanced** | Connection health, permission checks, logs/support action. | Android Auto, CarPlay, vehicle integration, and maintainer diagnostics subpages. |

Networking is a setup card under Phones, not a fifth permanent rail tab. Reuse one **Vehicle Wi-Fi profile** for SSID/security/secret and selected interface, with protocol-specific endpoint derivation. BSSID, live channel and IPv4 versus scoped IPv6 are not interchangeable across protocols. If nonprivileged AAOS cannot read vehicle AP secrets or real BT identity, show “Setup required”; never guess or request signature-only permissions.

Per-protocol advanced controls:

- **Android Auto:** auto video quality; manual H.264/HEVC/VP9 where AA decoder/offer support permits; resolution/FPS/layout width; GAL/PDK; AA hidden clock/signal/battery and theme; seed-IDR thresholds; AA margins/pixel aspect/decoder depth; compatibility transports. Preserve every existing preference even if hidden.
- **CarPlay:** auto video quality; H.264 or **validated, supported HEVC only**; offered resolution/FPS/physical dimensions/viewArea/safeArea/dock policy. No AA GAL, DPI, seed threshold or AA UI-chrome settings. Until implemented/proven, show a read-only “H.264 · 1280×720 · 30 FPS · experimental” summary, not live controls that do nothing.
- **Vehicle integration:** GPS/IMU, cluster, EV model and contribution consent appear only where the adapter/vehicle contract exists. AA EV model remains AA-specific. CarPlay has no current energy/cluster parity. Do not invent capabilities by reusing existing AA toggles.
- **Maintainer:** raw network/interface diagnosis, buffer/codec logging, config-transfer listener, logging/upload controls. WPP config listener stays explicit, bounded and off by default; it transfers SSID/BSSID, not the Wi-Fi password.
- Existing “Always in Park” override must never become shared everyday settings or bypass driving restrictions. Keep as a separately gated diagnostic compatibility setting pending safety review.

### 4.3 Visual and interaction specification

Design process follows loaded design/planning skills: clear hierarchy, progressive disclosure, one dominant action and context-specific sections. Automotive precision/quiet surfaces from the BMW reference are inspiration, not copied branding, marketing imagery or tiny web controls.

- Use the existing native Compose typography/font stack and `ui/theme/Theme.kt`; no CDN fonts, web runtime, glass gradients or decorative protocol cards.
- Proposed sizing tokens: 8 dp spacing unit; 16/24 dp panel padding; at least 64 dp touch targets; body 20 sp, section title 24 sp, status title 28 sp; use font scaling and measured screen width rather than hardcoded physical pixels. Validate against actual head-unit density before adoption.
- Single accent for actions/selection; text and icon explain state independently of color. Dark night surfaces and light day surfaces; WCAG AA text contrast and visible focus tested before a token palette is finalized.
- Wide display: compact left rail plus one content column; narrow window: list/detail navigation with a visible Back action. No horizontal technical chip wall or nested independent scroll areas.
- Phone row: label, protocol subtitle, current/preferred state, one contextual action. Do not expose raw IDs/IP/MAC in ordinary rows. Nearby untrusted receipts are not automatically trusted phones.
- Footer action states: **Done** for immediate/shared edits; **Apply & reconnect [protocol]** only when active session changes are pending; **Apply on next connection** when inactive. Validation/reconnect failure leaves Settings open with the outcome. Do not close the overlay unconditionally as the current Save & Reconnect callback does.
- Geometry changes during a stream need controlled surface recovery; no automatic surface rebuild on every slider movement. Debounce preview, commit protocol snapshot on Apply, and never send projected touches through a settings overlay.
- Expensive/long editing and setup are parked-only where required by platform/vehicle policy. Missing safety state is not permission to assume parked. Keep escape/help reachable.

Proposed logical routes are `projection`, `settings/phones`, `settings/display`, `settings/sound`, `settings/advanced`, `settings/advanced/android-auto`, `settings/advanced/carplay`, `settings/advanced/vehicle`, `settings/help`, `safe_area_editor`. Initially these can be typed overlay destinations inside `ProjectionScreen`; do not accidentally destroy its surface just to make every subpage a NavHost route. These routes are target IDs, not existing destinations.

## 5. Target ownership, automatic discovery and selection

### 5.1 One process authority

Create `app/src/main/java/com/openautolink/app/projection/ProjectionCoordinator.kt` (proposed). It owns the single activation lease and effects; the current AA `SessionManager` becomes an AA adapter behind it rather than a parallel top-level owner. `OalApplication` initializes the coordinator before any actionable advertisement/connection startup.

Minimal proposed contract:

```kotlin
// Conceptual boundary types; not compiled implementation.
data class ProjectionLease(
    val powerEpoch: Long,
    val generation: Long,
    val phoneKey: String,
    val protocol: ProjectionProtocol,
    val transport: ProjectionTransport,
    val networkEpoch: Long,
    val settingsRevision: Long,
)
// Coordinator serializes these into decisions + asynchronous effects:
// CandidateObserved(receipt), ConnectRequested(exactReceipt),
// AdapterPrepared(exactLease), Started(exactLease), Retired(exactLease, outcome),
// PermissionResult(exactLease, exactAttachment), NetworkChanged(epoch),
// WakeChanged(epoch), StopRequested(reason).
```

`phoneKey` uses a tagged identity (AA companion ID/Bluetooth mapping versus CarPlay verified controller identity), not a shared friendly-name key. Persist trust separately from reachability. `ProjectionPhase` is `Disabled`, `Idle`, `Preparing`, `Connecting`, `Projecting`, `Recovering`, `Retiring`, `Blocked`; current `SessionState` is mapped for AA, not expanded everywhere without an adapter.

Hard invariants:

1. At most one **activating/projecting/retiring** lease; old retirement must acknowledge before replacement effects may start. Retirement failure stays blocked with retry; no false IDLE or free owner.
2. Passive discovery and cache refresh may coexist; advertisement that triggers phone startup, RFCOMM handshake response, CarPlay connect request, USB permission/AOA switch, connector replacement, decode/audio/mic start all require a current qualified lease.
3. Every asynchronous continuation captures its original lease, phone claim and network/power epoch before scheduling. Validate at actual mutation/effect boundary, not just before a delay. Late NSD result/socket/native callback/input/re-advertise cannot mutate the replacement.
4. Native AA retirement and CarPlay thread/session/state-lock retirement are real resource acknowledgments, not `close()` returning or a UI flag. `ProjectionReceiver.close()` currently retires pairing state on a separate thread; add an awaited result before reusing that state.
5. Preserve exact attachment/pipe generation across USB detach and AOA re-enumeration; successor enumeration is not the original device lease. Never grant a queued permission or start a detached pipe.
6. Bound discovery memory/queues/timeouts; cached endpoints expire or are revalidated after network/power changes. IP, name, open port and a 2xx control response never establish authentication.

### 5.2 Discovery has one policy owner, not one giant scanner

Create `projection/PhoneRegistry.kt`, `ProjectionDiscovery.kt`, `ProjectionSelectionPolicy.kt` (proposed under the same package). Keep existing `PhoneDiscovery` as the AA companion adapter; use CarPlay-specific NSD/Bluetooth receipts separately. Registry writes must be transactional to avoid cross-protocol lost updates.

Distinguish **passive observations** from **activation effects**:

- AA passive: current vehicle BT eligibility and companion identity receipts on the selected network; bounded targeted probe/sweep only when AA policy requires it.
- CarPlay passive: verified paired identity eligibility and control-service endpoint receipts; current `PhoneActivation` mixes discovery with the mutating GET connect request, so split those responsibilities before it can be shared safely.
- USB passive: census/attachment receipts; no permission request, device open or AOA mode switch before explicit/capability-eligible admission.
- Wake/AP presence: control-plane preparation only; not evidence of driving intent or phone choice. Require adapter dependencies/Surface policy and allowed lifecycle before publishing actionable endpoints.

Current WPP owner installation follows binding an AA listener and can monopolize a waiting AA session. Merely starting this and `BenchActivity` together does **not** implement dual-protocol discovery. Target adapters must separate `prepare` (resources with no phone activation), `activate(exactLease)` and `retire(exactLease)`. Initially preserve existing WPP adapter semantics and select a protocol before entering its preparation/activation path.

**Do not enable two independent auto-start advertisements.** A WPP SDP publication can cause the phone to act before OAL knows which phone will win. CarPlay connect activation also has side effects. In the first safe integration, passive BT/registry facts select one candidate, then only its adapter publishes/responds/activates. If platform BT facts cannot select a candidate, use preferred eligible trusted phone or a bounded serialized probe window; retire that attempt before considering another. Unresolved equal candidates produce a single choice, not simultaneous starts.

A later shared dual-service listening catalog may expose both protocol services only after tests prove the first mutating handshake response atomically claims the shared owner, loser rejection is protocol-correct, and WPP publication itself cannot launch an uncontrolled loser. That is an evidence gate, **not** an assumed technique for seamless startup. Fail closed if the platform cannot meet it.

### 5.3 Selection and phone-switching priority

Eligible means trusted protocol identity, current route, required permissions/setup, lifecycle allowed, no conflicting retiring owner, and adapter capability. Rank in this order:

1. **Explicit current user selection** (including Use wired AA) requests an intentional handoff; never starts the new owner before the old one retires.
2. **Healthy active owner** remains sticky, regardless of arrival of another phone, cable, preferred phone or old claim age. Calls/assistant and safety policy may defer a manual handoff with a visible reason.
3. **Authoritative vehicle Bluetooth phone selection**, where observable and associated with a trusted protocol profile, outranks stale OAL default for new wireless attempts.
4. **Preferred eligible phone**, then **last successfully projecting eligible phone**; save success only after projection readiness, not discovery or handshake.
5. A single remaining eligible trusted phone may connect. Equal candidates with no authoritative choice → inline choice; never “whichever socket arrived fastest.”

A preferred phone does not let a nonprivileged app change GM's active HFP device. Existing `docs/wireless-wpp.md` requires WPP switching in the vehicle's Bluetooth settings. First UX migration shows **“Select this phone in the vehicle Bluetooth settings”** when OAL lacks control; an app phone row must not pretend it can force that switch. Once OEM selection changes, exact old phone claim is released and a new qualified attempt can proceed.

If an active phone disappears, reconnect that exact phone for a bounded grace before selecting another. Do not promote another phone based on mDNS service loss alone. USB insertion does not steal a live wireless session. No automatic trusted iPhone→AA fallback or Android→CarPlay attempt. Unsupported protocol/platform combination is an explicit unavailable outcome.

### 5.4 Race table: mandatory design tests

| Race | Required outcome |
|---|---|
| Android WPP handshake and iPhone activation arrive together | One exact lease wins by policy/serialized event decision; loser performs no startup response/connect/decoder/audio effect. |
| A is retiring while B discovered | B remains candidate only until A native/control/audio/state ownership acknowledgment. |
| A reconnect timer runs while B is CONNECTING | A fails exact lease check before changing connector/SDP; CONNECTED-only guards are insufficient. |
| Settings changed to WPP while USB still owns | Saved configuration changes; no WPP publication until USB retirement and new lease. |
| Queued AA `PhoneConnected` after CarPlay owner installed | Drop before shared state/MediaSession/UI publication. |
| Late CarPlay NSD resolution or runtime status after AA starts | Revoke original activation fence; callback cannot connect/update new owner. |
| USB detaches during permission/native-start queue | Revoke exact attachment synchronously; queued effect fails. AOA successor gets new receipt. |
| Selected interface vanishes / address reused | Invalidate network epoch, revoke offers/cached address proof, do not select another NIC or different phone. |
| Explicit stop followed by late force-reconnect | Stop epoch wins; no rearm without a later explicit enable/wake decision. |
| Retirement throws or thread never exits | Keep retiring owner and bounded retry/help state; no claim of successful switch. |

Use a serialized reducer for state, execute blocking teardown outside its monitor, then post exact completion back. Do not hold discovery/phone-claim locks while stopping native or waiting on Binder/network/thread joins. Freeze lock ordering when wrapping the existing WPP `phoneClaimLock` and AASDK `connectionStartLock`; prefer immutable event receipts over introducing inverse lock nesting.

## 6. Reconnect and power policy

The coordinator owns retry **permission and budget**; adapter retains protocol-specific recovery mechanics. Do not stack coordinator exponential retry on an uncontrolled AASDK retry loop. Initially the AA adapter can report existing reconnect events; later remove duplicate schedulers only behind tests.

- Proposed initial transient recovery schedule: 1, 2, 4, 8-second delays, capped at 15 seconds with jitter; monotonic clocks, single-flight and bounded attempts per power/network epoch. These are target defaults to tune, not existing measured timing.
- Keep exact last owner sticky for a proposed 30-second recovery grace after a confirmed transport loss; explicit vehicle phone change/user selection can end that grace. Exhaustion shows Retry/Change phone, not repeated chooser popups.
- Missing BT/AP/permission/surface means **waiting for readiness**, not burning retry budget or inventing endpoint values. Re-arm once on an authoritative readiness edge.
- Sleep/ignition OFF: stop actionable advertisement, send appropriate protocol goodbye if safe, cancel activation/retry/input, retire audio/mic/decoder and sockets. No signature-only power/door API assumption. Activity/process survival is an optimization, not correctness.
- Wake: bump power/network epoch, revalidate addresses/interface/BT identity and current adapter dependencies; publish only after infrastructure AND session readiness. Persist trust/settings; never persist an open socket as readiness.
- Surface loss while foreground versus deliberate background/settings/sleep must be classified separately; network-arrival watchdog is not decode-output proof. Reconnect only with the right failure cause.
- AA WPP recovery must retain captured `WppSessionAdmission.Token` through companion validation and bound callback to qualified re-advertise. CarPlay uses a fresh listener/offer and valid pairing/authentication with exact selected peer; avoid broad PairSetup loops for a recoverable stream interruption.

No promise of wake/reconnect parity follows from the CarPlay bench's first frame. Required acceptance includes cold launch, surviving-process long sleep and process recreation independently.

## 7. Shared media/control shell without false parity

Reuse display geometry/presentation, not wire protocols:

- Existing `ProjectionScreen` owns the real Surface and physical render rectangle. Shared coordinator grants one `SurfaceLease`; only active adapter owns its decoder and projected input dispatch.
- Existing `input/TouchCoordinateSpace.kt`, `TouchScaler.kt` and `TouchForwarderImpl.kt` beneath `app/src/main/java/com/openautolink/app/` are AA-specific collaborators. Shared geometry converts physical touch through fit/crop/insets to protocol input space. CarPlay currently emits normalized HID contacts. Never reuse AA negotiated pixels as CarPlay coordinates.
- Existing `app/src/main/java/com/openautolink/app/media/OalMediaSessionManager.kt` stays process-owned, but commands/metadata dispatch to a generation-qualified active adapter. CarPlay media command/metadata parity requires separate implemented protocol support.
- Existing `app/src/main/java/com/openautolink/app/audio/AudioPurposeCoordinator.kt`, `AudioFocusManager.kt` and `AudioPlayerImpl.kt` must not compete with independent `AndroidMediaSink` focus ownership. Define one process audio policy, adapter-specific decode, purpose mapping and retirement before co-running adapters. Preserve GM mute/media and custom key remaps separately.
- `app/src/main/java/com/openautolink/app/input/ProcessVehicleDataRuntime.kt` remains one vehicle source. AA session borrows data; CarPlay consumes only implemented/permission-supported semantics. No duplicate VHAL subscribers for a second protocol.
- Call UI/control is distinct from voice transport. AA on the current topology uses vehicle Bluetooth HFP; do not disable Phone calls to reduce UI overlap. CarPlay projected answer/end, OEM overlay avoidance and two-way call audio remain separate runtime gates; the bench microphone is disabled. No automatic claim of CarPlay telephony/Siri support.

## 8. CarPlay codec and resolution policy

**Current:** H.264, 1280×720, 30 FPS; HEVC is explicitly disabled and rejected by the sink. AA HEVC validation cannot validate CarPlay HEVC. Higher AA tiers and VP9 do not transfer to CarPlay.

Create `carplay-lab/src/main/java/com/openautolink/carplaylab/media/CarPlayVideoCapabilities.kt` and `CarPlayVideoOfferPolicy.kt` (proposed) before exposing new controls. The admissible offer set is the intersection of:

1. Actual platform decoder capabilities: MIME/profile/level, size/alignment, size+rate support and acceptable hardware/software policy using MediaCodecList/VideoCapabilities; decoder name alone is insufficient.
2. Implemented CarPlay transport/parser/CSD/decoder route. `ScreenStream` and HEVC CSD helpers are necessary but not sufficient; extend `AndroidMediaSink` to support H.265 and negotiated dimensions intentionally.
3. Known protocol/phone capabilities and validated offer semantics. If exact remote limits are unknown, do not pretend there is a negotiated capability list; use conservative offers and record the actual selected format.
4. Device/phone/route validation allowlist and user safety/quality preference.

Default **Auto (validated)** initially resolves to the known H.264 bench profile. Once HEVC is implemented and validated on the exact target, auto may offer it. Advanced **Prefer HEVC** means preference, never force: an unsupported selection is disabled with explanation or resolves to a disclosed compatible profile. It must not simply flip `AirPlayConfig.hevc`.

Resolution policy:

- The only current proven CarPlay size is 1280×720. 1920×1080 or native/aspect-matched profiles are future candidates, not promised support; do not copy AA's 1440p/4K list.
- `AirPlayDisplayConfig` can express pixel/physical dimensions, FPS, viewArea/safeArea; derive one validated session offer from the live render rectangle, phone profile and decoder limits. Keep physical millimeters separate from AA DPI/layout width.
- Capture actual codec configuration/SPS dimensions and MediaCodec output format; validate touch mapping to that stream and active view area. Reject unsupported format early, rather than claiming the advertised dimensions are necessarily the received ones.
- If a newly enabled profile fails startup, retire that exact attempt and permit **one** lower validated profile attempt for the same phone/epoch; show fallback outcome. No format-change loop on a healthy stream and no silent protocol switch.
- Prove HEVC hvcC→VPS/SPS/PPS CSD, Annex-B conversion, random-access gating, decoder creation, size/rate check, format changes, queue overflow, surface recovery and retirement. Capability probing must not seize a production Surface/audio owner.

Preserve `950.7.1` in the presently corrected AirPlay offer pending protocol evidence; do not upgrade a source version merely to unlock hypothetical HEVC features. The bootstrap endpoint's `1.0` has a different role.

## 9. Settings/data migration policy

Introduce a versioned schema, preserving old keys for rollback. Proposed files:

- `app/src/main/java/com/openautolink/app/data/ProjectionPreferences.kt`: shared auto-connect/preferred-phone/display/audio presentation values.
- `app/src/main/java/com/openautolink/app/data/ProjectionSettingsMigration.kt`: transactional, idempotent old→new import with explicit schema version and backup/rollback mapping.
- `app/src/main/java/com/openautolink/app/projection/PhoneRegistry.kt`: tagged protocol identity/trust/capability/last-success records; no durable IP cache or raw secret logging.

Import AA video/audio/input/EV settings into the AA profile **without resetting** resolution, GAL, seed thresholds, margins, key map or opt-in contribution consent. CarPlay starts with its own conservative profile and no assumed AA parity. Shared fit/system-bars can initialize from existing shell values, but protocol insets/DPI/physical size remain separate.

| Existing saved state | First migration behavior |
|---|---|
| New install, no saved transport | Target default Android wireless=WPP; remain setup-required if credentials/interface/companion missing. No speculative advertisement. CarPlay auto-connect gated on capability/trust and experimental authority. |
| Saved WPP | Preserve Wi-Fi/interface/BT association and AA profile; hide old selector in everyday UI. |
| Saved USB | Preserve wired startup/picker and cold-plug behavior. Offer wireless setup separately; never convert automatically to WPP. |
| Saved hotspot/car_hotspot | Preserve compatibility profile initially; explain current wireless-start limitation and offer deliberate WPP setup/migration. Do not silently break older AA configurations. |
| Saved hotspot/phone_hotspot | Preserve as explicitly legacy/inactive unless user intentionally uses supported old setup; do not translate to WPP own-SoftAP. |
| Saved nearby | Preserve the current nearby→hotspot semantic, record migration provenance, then apply compatibility policy. |
| Unknown/malformed transport | Retain original value in migration backup, fail closed with repair UI; never treat arbitrary strings as permission to connect. |

Editing is persistence, not activation. Use one immutable settings snapshot/revision per lease. Shared immediate settings, AA-next-session and CarPlay-next-session changes are typed separately. Apply affects only the active adapter; changing inactive AA settings during CarPlay cannot force AA start or restart CarPlay. Reconnect returns a typed result (`Applied`, `Deferred`, `Declined`, `Failed`); show it before closing the overlay. Unsupported options preserve their saved values but cannot become advertised capabilities.

Secrets: retain private/no-backup pairing ownership, minimize AP password exposure and logging, use secure private storage, never export MFi identity/pairings through diagnostics. Forget phone invalidates trust and queued effects for that exact protocol identity, retires it if active, and then deletes appropriate pairing state after acknowledgment. Product provisioning/licensing/security and distribution approval are independent gates.

## 10. Ordered implementation packets and dependencies

These are design-gated work packets, not a request to execute now. Parent owns native runtime touch/audio work; do not duplicate or overlap it. Each code packet must be expanded into small RED→GREEN→REFACTOR tasks with exact boundary tests before delegation.

### Packet A — baseline and pure policy (no wire changes)

**Create:** `app/src/main/java/com/openautolink/app/projection/ProjectionContract.kt`, `ProjectionSelectionPolicy.kt`; `app/src/test/java/com/openautolink/app/projection/ProjectionSelectionPolicyTest.kt`.

Freeze identity/lease/phase/effect/result contracts. Test healthy-owner stickiness, authoritative BT selection, explicit handoff, ambiguous discovery, missing setup, USB insertion and recovering owner grace. Use fake clocks and deterministic event order. Inputs are immutable receipts, outputs decisions; no Android/NSD/native calls. Dependency: none. Exit: pure policy tests, source/evidence agreement reviewed.

### Packet B — settings schema and presentation policy

**Create:** `data/ProjectionPreferences.kt`, `data/ProjectionSettingsMigration.kt` under `app/src/main/java/com/openautolink/app/`; `app/src/test/java/com/openautolink/app/data/ProjectionSettingsMigrationTest.kt`; `app/src/test/java/com/openautolink/app/ui/settings/ProtocolSettingsPresentationTest.kt`.

**Modify later:** `AppPreferences.kt`, `KnownPhonesStore.kt`, `ui/settings/SettingsViewModel.kt` beneath the same main package. Implement old→new migration fixtures for every row in section 9, idempotence, unknown-value retention and transactional phone updates. Build context-specific visibility from capability records, not live transport preferences. Dependency: A contract. Exit: no changed startup behavior and all old values/consents recoverable.

### Packet C — coordinator + AA adapter ownership seam

**Create:** `app/src/main/java/com/openautolink/app/projection/ProjectionCoordinator.kt`, `ProjectionDiscovery.kt`, `PhoneRegistry.kt`, `AndroidAutoProjectionAdapter.kt`; `app/src/test/java/com/openautolink/app/projection/ProjectionCoordinatorTest.kt`, `ProjectionRetirementTest.kt`.

**Modify later:** `app/src/main/java/com/openautolink/app/OalApplication.kt`, `session/SessionManager.kt`, `transport/aasdk/AasdkSession.kt`, `transport/bluetooth/AaWirelessBtControl.kt`, `WppSessionAdmission.kt`, `transport/PhoneDiscovery.kt` (all abbreviated paths under the main app package).

Route every start/stop/reconnect effect through a lease while preserving WPP behavior. Capture exact WPP token through recovery and replace broad delayed readvertise with qualified effect. Prevent old native/session callback publication. Prove fail-once native stop keeps retirement, queued A→B CONNECTING races and explicit-stop suppression. Assess generation-conditional native stop changes in `app/src/main/cpp/aasdk_jni.cpp` plus `transport/aasdk/AasdkNative.kt` before claiming exact native authority. Dependency: A/B and parent runtime boundary review. Exit: WPP/USB baseline does not regress; CarPlay remains disabled.

### Packet D — dormant CarPlay adapter and network gate

**Create:** `app/src/carplayBench/java/com/openautolink/app/projection/CarPlayProjectionAdapter.kt` (bench-only first); `app/src/testCarplayBench/java/com/openautolink/app/projection/DualProtocolAdmissionTest.kt` (proposed variant source set, validate Gradle configuration before relying on it).

**Modify later:** `carplay-lab/src/main/java/com/openautolink/carplaylab/pairing/PhoneActivation.kt`, `media/ProjectionReceiver.kt`, `media/MediaPolicy.kt`, `EndpointOffer.kt`; `app/src/carplayBench/AndroidManifest.xml` only after the bench has explicit shared ownership.

Separate passive control discovery from activation. Add awaited retirement, exact peer/auth/session callbacks, selected-network binding and offer lifetime. Do not wire a normal Application that starts AA as a side effect. Dependency: C, parent verified touch/audio closure, authentication/provisioning review. Exit: deterministic cross-protocol admission tests, no activation/audio/native side effects by loser.

**GM scoped-IPv6 gate:** verify numeric scope parsing, local and remote interface identity, actual listener reachability from iPhone, scoped outbound activation and usable endpoint serialization (wire representation may differ from Android zone notation), authentication/media ports and lifecycle. `PhoneActivation` support alone cannot satisfy this gate. Keep this separate from shared-home-IPv4 acceptance. No arbitrary-NIC fallback, root assumption or paid-data requirement.

### Packet E — unified Compose settings and projection shell

**Create:** `app/src/main/java/com/openautolink/app/ui/settings/PhonesSettingsScreen.kt`, `ProtocolAdvancedSettingsScreen.kt`; `app/src/androidTest/java/com/openautolink/app/ui/settings/UnifiedSettingsNavigationTest.kt`.

**Modify later:** `ui/navigation/AppNavHost.kt`, `ui/projection/ProjectionScreen.kt`, `ProjectionViewModel.kt`, `ui/settings/SettingsScreen.kt`, `SettingsViewModel.kt`, `SafeAreaEditorScreen.kt`, `ui/theme/Theme.kt` beneath the main app package.

Build the four-section overlay, conditional details, setup card, capability summaries and typed apply outcomes. Remove duplicated reconnect parameter assembly from UI entry points. Keep Settings unconditional and USB picker escape. Keep surface alive while opening Settings. Dependency: A/B/C; expose CarPlay controls only after D capabilities. Exit: no-dead-toggle audit from key/flow→UI→effect, navigation/back behavior, font scaling/contrast/64dp hit targets, unsupported options absent and failure leaves Settings open.

### Packet F — optional CarPlay quality expansion

**Create:** the capability/offer files named in section 8; `carplay-lab/src/test/java/com/openautolink/carplaylab/media/CarPlayVideoOfferPolicyTest.kt`, `CarPlayVideoCapabilitiesTest.kt`.

**Modify later:** `carplay-lab/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt`, `MediaCodecSupport.kt`, `airplay/AirPlayConfig.kt`, `AirPlayInfoPlist.kt`, `ScreenStream.kt`; `carplay-lab/src/main/java/com/openautolink/carplaylab/media/ProjectionReceiver.kt`.

Pure offer filtering first; then deliberate HEVC/negotiated-dimensions implementation and real device evidence. Dependency: D, parent runtime closure and actual decoder capability inventory. Exit: unsupported codec never offered, actual HEVC output proven on target, one bounded fallback and correct touch after profile change. This packet is not required to ship an honest H.264-only experiment.

### Packet G — compatibility retirement and product rollout

**Modify later:** `docs/wireless-wpp.md`, `docs/compatibility.md`, `docs/troubleshooting.md`, `companion/src/main/java/com/openautolink/companion/ui/MainScreen.kt`; prune legacy backend only through a separately reviewed plan after compatibility evidence.

First hide clutter while preserving profiles; second guide intentional WPP migration; only then consider dead-code removal. Keep bench feature default-off outside its variant until owner, network, trust, runtime and distribution gates pass. Dependency: C/E, D for dual protocol, sustained real-vehicle acceptance. No release/version/tag/store action is authorized here.

Dependencies remain local: use existing Compose/Navigation/DataStore/coroutines and test libraries; AA continues using existing AASDK/Conscrypt/protobuf. Experimental CarPlay continues Bouncy Castle and upstream GPL/provenance obligations. No dongle/SBC/remote signing server is required by this target design; valid private authentication provisioning remains required. Do not add a web UI framework or general transport plug-in abstraction to accomplish two adapters.

## 11. Verification contract (future execution; not run here)

Existing regression anchors include:

- `app/src/test/java/com/openautolink/app/transport/bluetooth/WppSessionAdmissionTest.kt`, `WppAdmissionWiringContractTest.kt`, `WppBootstrapPolicyTest.kt`, `CompanionIdentityPolicyTest.kt`.
- `app/src/test/java/com/openautolink/app/transport/WppInterfacePolicyTest.kt`, `WppInterfaceWiringContractTest.kt`, `WppProxyPortContractTest.kt`.
- `app/src/test/java/com/openautolink/app/transport/usb/UsbTransportOwnershipTest.kt`, `UsbTransportRetirementContractTest.kt`, `UsbPermissionWiringContractTest.kt`.
- `app/src/test/java/com/openautolink/app/transport/aasdk/ReconnectSingleFlightGateTest.kt`, `NativeTransportTeardownTest.kt`; `app/src/test/java/com/openautolink/app/ui/projection/WppWakeReconnectPolicyTest.kt`, `UsbPickerVisibilityTest.kt`.
- `app/src/test/java/com/openautolink/app/video/AutoVideoTouchPolicyTest.kt`, `CodecSelectorTest.kt`; `app/src/test/java/com/openautolink/app/session/NativeSessionDependencyContractTest.kt`.
- `carplay-lab/src/test/java/com/openautolink/carplaylab/pairing/PhoneActivationTest.kt`, `PairingPersistenceTest.kt`; `carplay-lab/src/test/java/com/openautolink/carplaylab/media/MediaPolicyTest.kt`; `carplay-lab/src/test/java/com/shilapi/xcertplay/airplay/CarPlayStartupTest.kt`, `LabMediaFormatsTest.kt`, `IapTunnelLifecycleTest.kt`.

Example future commands from this repository with an approved JDK/SDK and shared build lock (do **not** run as part of this documentation task):

```sh
./gradlew :app:testDebugUnitTest :carplay-lab:testDebugUnitTest
./gradlew :app:compileCarplayBenchKotlin
./gradlew :app:externalNativeBuildDebug   # required if JNI/C++ ownership changes
./gradlew :app:connectedCarplayBenchAndroidTest  # only after proposed tests/source set exists
```

Count actual JUnit XML cases; no-tests/build-success is not proof. Run targeted RED/GREEN policy/race tests before full unfiltered regressions. JVM/source-contract tests do not prove Bluetooth, GM network/firewall, native retirement or audible behavior. Visual implementation requires local/emulator inspection, but never embed screenshots or personal runtime data in public design docs.

| Real acceptance scenario | Required evidence |
|---|---|
| Android normal startup | Vehicle BT→companion→WPP selected interface→authenticated native AA→sustained frames/touch/audio, with one owner and no legacy association competitor. |
| iPhone normal startup | Trusted identity→actual endpoint exchange/PairVerify→runtime tunnel→sustained rendered video/touch/media/guidance; microphone/calls only if implemented and separately proven. |
| Both phones nearby | Sticky active owner; deterministic next choice; loser has no actionable startup/media effects. No arrival-order phone theft. |
| Manual protocol/phone change | Old resources acknowledged retired; new exact owner only; OEM BT selection requirement honest. |
| USB | Permission/AOA/reattachment/queued-detach correctness, sustained wired AA on current phone; no WPP theft. |
| Cold/warm/long-sleep/process death | Fresh epochs/dependencies, bounded same-phone recovery, no stale endpoint/callback or chooser storm. |
| GM CarPlay | Car AP scoped-IPv6/direct-route evidence independent from TCL/home IPv4; no privileged API assumptions. |
| Codec/profile change | Offer capability set matches route, actual decoder output and correct touch; unsupported HEVC disabled. |
| Calls | Projected answer/end + vehicle speaker and microphone audio + OEM UI behavior, protocol-specific. |
| Settings/migration | Every imported value/consent preserved; no cross-protocol restart; escape path always available; validation failures visible. |

## 12. Decision summary and limits

**Decide now:** unify around phone identity and a single owner, make WPP the target Android wireless default, keep wired AA, hide legacy wireless/topology controls in compatibility, and replace always-visible technical tabs with shared everyday settings plus protocol-specific advanced details.

**Do not claim now:** seamless dual-protocol auto-start, production CarPlay, GM scoped-IPv6 media, CarPlay touch/audio/call/reconnect parity, HEVC/higher CarPlay resolutions, or completed legacy transport removal. Current normal app is AA-only; the bench is intentionally a separate runtime. First H.264 frame is a meaningful media milestone, not proof of the proposed product architecture.

**Blocking design/runtime gates:** exact cross-protocol activation/retirement; WPP delayed-recovery owner qualification; safe native stop identity; genuine CarPlay provisioning/trust/distribution authority; GM route evidence; parent-owned touch/audio validation; capability-filtered codec implementation; migration and real-vehicle regression acceptance. All remain explicit rather than filled with assumptions.
