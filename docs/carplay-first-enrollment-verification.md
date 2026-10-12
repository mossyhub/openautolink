# First-time CarPlay enrollment: independent premise audit and implementation

## Outcome

**The earlier blanket blocker was based on an additional OAL threat-model requirement, not a demonstrated CarPlay enrollment requirement.** Public Apple onboarding uses native device selection, Bluetooth Secure Simple Pairing, CarPlay permission/consent, Wi-Fi provisioning and protocol authentication. Independent receiver code and pinned DiPlay support ordinary first-controller SRP/Ed25519 PairSetup followed by stored-key PairVerify; they do not expose the demanded selected-Bluetooth-phone → AirPlay-controller-key cryptographic handoff.

Implemented a narrow, explicitly enabled, bounded first-enrollment admission and owner-qualified PairSetup persistence hook. These hooks are **not yet wired into the real receiver by this worker**: OalCarPlayRuntime/ProjectionReceiver/AirPlaySession wiring belongs to the runtime integration owner. Exact integration instructions: [carplay-ordinary-enrollment-handoff.md](carplay-ordinary-enrollment-handoff.md). Default trusted reconnect remains closed to empty-store enrollment unless the runtime explicitly supplies the enrollment window.

This conclusion does not assert that the entire private licensed CarPlay specification lacks additional mechanisms. It rejects the circular premise that interoperability must wait for an undocumented, newly invented key-binding protocol. No private credentials, alternative crypto or authentication bypass were introduced.

## Source audit

### Apple public design (normative user experience, not complete crypto specification)

- [WWDC17 session 717, Developing Wireless CarPlay Systems](https://developer.apple.com/videos/play/wwdc2017/717/), Bluetooth setup sequence around 17:15 onward: selection through native Bluetooth or CarPlay menus; Bluetooth Secure Simple Pairing; iAP2; iOS permission to enable wireless CarPlay; transport identifiers for recognizing a device across transports; WirelessCarPlayUpdate; user confirmation before sending Wi-Fi credentials; AP join; Bonjour/CarPlay Control session start; iAP2 over CarPlay. The transcript says **“The head unit must wait for the user confirmation before responding.”** General Bluetooth-menu pairing needs CarPlay consent; a CarPlay-specific selection already communicates that choice. A mere bond does not by itself establish all consent.
- [WWDC16 session 722](https://developer.apple.com/videos/play/wwdc2016/722/): Bluetooth for discovery/initial connection and sending Wi-Fi credentials; Wi-Fi for subsequent audio/video/control. Does not prescribe an OAL controller-key handoff.
- [WWDC23 session 10150, connectivity at 10:00](https://developer.apple.com/videos/play/wwdc2023/10150/): preferred simplified flow exchanges IP address/port over existing iAP2, eliminating Bonjour for iOS14+; continue Bonjour for older phones. Thus lack of selected-phone IP discovery is not necessarily a first-enrollment blocker on the endpoint-exchange path.
- [Apple Support setup](https://support.apple.com/en-us/102521): use the car's wireless/Bluetooth pairing mode and iPhone Settings → General → CarPlay → Available Cars. No companion application or extra custom receiver phone-selection UI in the documented ordinary flow.

The transcript was independently retrieved from the actual Apple HTML because generic web extraction omitted many paragraphs. Public user-experience descriptions alone cannot establish absence of every private cryptographic mechanism; implementation evidence below supplies the narrower interoperable behavior.

### Pinned DiPlay

Pin `e2fd8eadf5a6b705666f292055e72aa93387533d`, locally verified with git; paths relative to `/hermes_work/research/DiPlay`:

- `shared/.../airplay/PairSetup.kt`: fixed code `3939`, SRP proof, encrypted M5 controller identifier/key/signature, Ed25519 signature verified against that presented key, key persisted for later verification. No Bluetooth identity, link-generation token, selected-phone controller key or cross-transport secret input.
- `shared/.../airplay/PairVerify.kt`: X25519 exchange and Ed25519 controller signature against previously persisted trust. This is the cryptographic reconnect/media-control admission, not optional.
- `shared/.../airplay/MfiSapAuthSetup.kt:10-16,25-65`: accessory certificate and signature over ephemeral keys, derived AES encryption, already encrypted control. It proves the accessory's MFi authorization to the phone, not that a new controller is the selected Bluetooth phone.
- `shared/.../mfi/Iap2MfiAuthenticationClient.kt:18-69`: accessory certificate/challenge/signature, phone AuthenticationSucceeded, returns Unit. No phone long-term AirPlay key is supplied.
- `shared/.../transport/Iap2WirelessControlClient.kt:125-205,268-307`: bounded 0x5703 Wi-Fi replies, 0x4300→0x4301 session offer, 0x4e0d availability, 0x4e0e identifiers and post-transport Wi-Fi resend. StartSession supplies the accessory's public identity/key/listener endpoint. No controller-key authorization secret.

MFi credentials remain separate, privately provisioned through the existing authenticator. Local accessory Ed25519/UUID generation is not MFi credential generation and does not by itself authorize enrollment.

### Independent/source-diverse corroboration

- [Showcase](https://github.com/amineross/showcase/tree/ed75c7a71d420770d11982e16ca22a3eb489b6b6), cached in `/hermes_work/research/carplay-media-source-review/showcase`: `source/carplay_services.m:1246-1308` dispatches PairSetup, persists its controller registry on completion, waits for PairVerify; separate auth-setup handles accessory authentication. `source/carplay_bt.m` separately authenticates iAP2 and supplies Wi-Fi configuration. PairSetup receives no selected-Bluetooth-phone key. Its BT metadata has wiomoc ancestry; do not count every metadata similarity as independent corroboration. Its raw logging, globally shared pairing context, fixed example identity and unrelated compatibility choices are **not** imported into OAL.
- [wiomoc/iap2](https://github.com/wiomoc/iap2/tree/032135e9b5f1276cffdf8a820baeaf10f5cf6b10), independently cloned to `/hermes_work/research/carplay-enrollment-audit/iap2`: `iap2/__main__.py:129-148` authenticates/identifies then sends Wi-Fi configuration on phone request; `control_session_message/car_play.py` models 0x4e0e Bluetooth/USB identifiers and 0x4e0d availability, not an AirPlay Ed25519 controller-key transfer. This corroborates the bootstrap only, not a full AirPlay receiver or security guarantee.
- [signalius/WirelessCarPlay](https://github.com/signalius/WirelessCarPlay/tree/6f4ce42f60274089ccb7410989f106ea827fb946), independently cloned to `/hermes_work/research/carplay-enrollment-audit/WirelessCarPlay`: `source/Sources/AirPlayReceiverServer.c:2888-2959` creates a SetupServer and sets fixed `3939`; `AccessorySDK/Support/PairingUtils.c:1576-1635` decrypts M5, verifies the self-presented controller Ed25519 key, saves that peer. No selected Bluetooth phone input in this path. **This is SDK-derived code bearing restrictive MFi license headers, not a clean independently authored open-source implementation.** It is corroborating research only; no source was imported or bundled and it is not treated as authoritative current Apple public documentation.

### Local GM evidence

Paths relative to `/hermes_work/research/gm-carplay-oal-20261006/targeted`:

- `com.gm.server.wirelessprojection.WirelessCarPlay.java:201-223`: existing native `checkDeviceConnectedAndConsent(mBtAddress)` and authentication boundary. This demonstrates consent exists as an OEM concept; OAL must not copy its `ignoreAuth` optimization or claim that an observed bond implies native CarPlay consent.
- `com.gm.server.iap2.feature.CarPlayStartSession.java:184-220`: Cinemo session parameters use the receiver's pairing identity, listening port, plugin version, accessory Bluetooth ID, actual AP configuration and local IP address. This corroborates DiPlay endpoint exchange, not controller-key transfer.
- Relevant crypto implementation remains in absent Cinemo native dependencies. Therefore wrappers alone neither prove a cryptographic binding exists nor justify demanding one before ordinary enrollment.

## Supported candidate behavior and accepted risk

Ordinary flow remains: launch/enable OAL CarPlay and configure head-unit Bluetooth MAC/AP information once, use GM native Bluetooth selection/pairing, accept the ordinary iPhone CarPlay prompt (and native CarPlay consent when required), then deliver real AP/endpoint information through the selected bootstrap. Do not add a companion or OAL phone list. The existing explicit OAL CarPlay enable choice is available for accessory-side consent; do not silently treat every generic GM bond as a CarPlay choice. Runtime integration must document which existing native/OAL choice supplies that consent.

Fresh enrollment is **trust on first enrollment under native consent and AP access control**, not cryptographic proof of which bonded phone presented the key. Fixed-code SRP proves self-consistency/key possession; MFi proves the accessory to the phone. A rogue client able to reach the same AP/listener and knowing the fixed code can race or deny the first setup. A one-live-candidate rule, interface scoping and short deadline reduce exposure, but do not defeat a local-network attacker. Other occupants with the AP password remain in this threat model. Automatic enrollment must not silently replace or add controllers to an already trusted receiver.

Product alternatives for stronger assurance are: retain trusted-only provisioning as an explicitly stricter mode; use an isolated/protected AP with credential access restricted during setup; research licensed protocol/AP-controller APIs for an actual per-device admission mechanism; or evaluate real USB first setup when the hardware/runtime supports it. Do not invent a secret TLV, equate IP/MAC to cryptographic identity, or weaken MFi/PairVerify to satisfy the stricter threat model.

## Implemented owned code

- `CarPlayPeerAdmission.kt`: opt-in `enrollmentWindowMillis` (default zero, max 120000ms); empty-store first enrollment without a copied phone key; exact control-owner commit; one durable controller; no trust until successful store commit; real PairVerify callback required before exposing media binding; stale token, wrong interface/family/scope, retirement, deadline and second enrollment rejected. Existing startup trust reconnect remains immutable. Enrollment deadline never expires an already committed/verifiable projection session.
- `PairSetup.kt`: optional `saveController` Boolean durable-commit callback, called **after** genuine SRP and M5 Ed25519 proof, before M6 success. False/exception maps to protocol auth failure; callback cannot claim success unless the proved key is in the store. Existing default library/lab persistence behavior remains unchanged.
- Added `CarPlayOrdinaryEnrollmentTest.kt`; expanded `PairSetupTranscriptTest.kt`; updated `CarPlayEnrollmentAdmissionTest.kt` to name a missing explicit authorization window rather than a presumed missing standard protocol.

## Executed verification / remaining gates

JDK17 `/home/lance/.local/jdks/temurin-17`, SDK `/home/lance/.local/android-sdk`, host mirror `/mnt/Temp/Hermes_Work/oal-carplay-integration`, shared `/mnt/Temp/Hermes_Work/.oal-gradle.lock`.

- Observed behavioral RED then GREEN: empty-store explicit enrollment previously threw; expired enrollment previously admitted a new candidate; real revoked M5 previously persisted despite callback denial. Missing-API compile failures were followed by actual assertion/runtime failures before implementation.
- Focused final gate: **38 tests, zero failures/errors/skips**, XML parsed under the **same lock as execution**. Eight classes: ordinary enrollment (11), trusted admission (6), SRP/Ed25519 transcripts (6), network admission (1), real PairVerify/control sockets (3), pairing crypto (6), persistence (3), generated receiver identity (2).
- Includes real SRP M1..M6 followed by real X25519/Ed25519 PairVerify through admission, bad-signature no-callback, rejected/failed persistence, old control token, deadline, scoped IPv6/source rejection and synchronized retirement-versus-durable-save.
- Unfiltered module execution reached **204 tests with one failure** in parent-owned `RuntimeCarDataWiringTest.productionRuntimeUsesActualPerTunnelLifecycleAndRawSources`. Earlier transient compilation failures in concurrently edited `RuntimeAppearanceEventTest` were subsequently resolved by its owner. Do not describe the unfiltered gate as green; do not change unrelated worker files to make it green.
- An attempted temporary Gradle setSource filter was additive (did not exclude other tests); final focused run used normal explicit `--tests` filters, no repository build configuration changes.
- Real runtime hook wiring, bootstrap/consent-qualified enablement, cancellation integration, app compile and physical iPhone/GM consumption belong to the runtime/parent gates. This worker did not claim first-install car projection succeeds before those gates. No device, release, commit, settings/UI ownership edits, credential copying or private credential output.

## Next research artifact if actual phone onboarding still fails

Capture a sanitized, stage-qualified **fresh-store** reference-versus-OAL transcript on the same iPhone/AP showing: native/iPhone consent, selected-bootstrap identification and AA05, 0x5702/0x5703, 0x4e0d availability and 0x4300/0x4301 ordering, actual bound endpoint/family, PairSetup state/result and controller-count transition, PairVerify result, auth-setup outcome and first media SETUP. Record only finite message IDs/states/lengths/booleans, never raw keys/certificates/credentials/plists. The artifact discriminates consent/provisioning/endpoint ingress from crypto failures. If no TCP ingress occurs, compare actual StartSession endpoint/SSID/address scope to pinned DiPlay and GM, then test sourced iOS14+ endpoint exchange versus supported legacy Bonjour—not an undocumented controller-binding invention.
