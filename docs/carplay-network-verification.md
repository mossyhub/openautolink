# Scoped CarPlay AP networking handoff

## Implemented
- AP auto selects only IPv6 link-local on the configured up interface (default `ap_br_swlan0`). No address/interface/default-network discovery fallback or AP creation. Explicit IPv4/IPv6 family, exact owned local address, manual numeric peer, independent AP frequency/security remain overrides.
- AP listener binds the actual scoped address before EndpointOffer construction. Offer contains one scope-free AP address; socket binding retains the local numeric zone.
- Unknown AP peer requires exactly one already persisted controller at startup. One live pre-auth connection, 32 total bounded accept attempts and 30s handshake deadline. PairSetup is disabled for unknown-peer admission. Real PairVerify must verify a stored Ed25519 key and the exact-session admission callback must accept it before authenticated control/media SETUP. No new-controller enrollment or unauthenticated media discovery.
- Control/event/keepalive/timing/screen/audio/tunnel/microphone sockets on the new path retain exact session endpoint/family. Scope comparison uses bytes AND zone IDs, not Inet6Address.equals. No secondary wildcard iAP tunnel on this path.
- Default shared-Wi-Fi IPv4 still uses existing MediaPolicy, listener, activation and auxiliary socket behavior; its existing advertisement list is preserved. CarPlay remains disabled by default and Android Auto WPP untouched.
- Manual-peer PhoneActivation binds local address before connecting; unknown AP peer skips it rather than inventing discovery or a phone IP.
- Privacy-safe address-selected, listener-bound, accept, authentication and failure stages; no IPs, scopes, SSID/passphrase, controller IDs or crypto printed by new stages. Missing trusted controller is admission-unavailable, not bind-failed.

## Parent integration
See `carplay-network-contract.md`. Runtime config appended arguments are `networkFamily`, `apSecurity`, `localAddress`; existing frequency arg must receive saved CarPlay `apFrequencyMhz`, not AA WPP frequency. Parent owns UI/VM validation/mapping.

Runtime/receiver are now available for parent hooks. Existing receiver bounded input executor plus exact active session/authentication check is the intended appearance-command seam. Location/status/speed identification flags remain false until parent wires the actual per-link reporter; do not advertise reports that lack a live approved reporter. Integrate before Bluetooth identification if capability flags change. No vehicle/EV/location features were added by the network worker.

## Actual verification
All host Gradle gates used JDK17 `/home/lance/.local/jdks/temurin-17` and shared `flock /mnt/Temp/Hermes_Work/.oal-gradle.lock`.
- RED→GREEN observed for policy creation, unavailable/down/interface selection, family/address overrides, named scopes and explicit-zero scope rejection; trusted peer admission; transport binding; control ancillary binding/PairSetup rejection/trust snapshot; real PairVerify proof callback; runtime wiring and diagnostic stage guards. New API REDs were missing-feature compile failures; later policy/wiring REDs were assertion failures. One request-constructor typo and unavailable Java Files.readString were fixed before their missing-feature/assertion RED reruns.
- Latest isolated receiver regression gate: **33 tests, 0 failures/errors/skips**, including **11 new network tests**. Includes existing IPv4 AEAD transports, pairing crypto, activation and endpoint offers. Real IPv6 loopback TCP screen frame and UDP audio AEAD payloads passed; timing/event/keepalive and iAP tunnel exact bind checks passed, tunnel had exactly one listener, IPv4 attachment to the IPv6 screen listener was rejected. Wrong peer/scope/local interface tested in policy/admission fixtures; no actual GM scoped ingress was tested.
- App settings/coordinator/ownership plus network serialization gate: **18 tests, 0 failures/errors/skips**; disabled-path/ownership regression and two new serialization characterization tests covered.
- `:app:compileDebugKotlin` and `:app:compileTabletDebugKotlin` succeeded. Existing unrelated Kotlin warnings remain.
- A full library run initially passed before concurrent intake additions. Latest full run: **167 tests, one failure** in concurrent `OalIap2CarDataReporterTest.transmissionReceiptsContainNoNmeaAndFailureRetiresWithoutClaimingConsumption` (IllegalStateException line24). Intake owner notified; intake files left untouched. Do not present the latest full suite as green until parent reruns it after that repair.

Reproduce latest scoped gate:
```
JAVA_HOME=/home/lance/.local/jdks/temurin-17 ./gradlew :carplay-lab:testDebugUnitTest --tests '*CarPlayNetwork*' --tests '*LabMediaTransportTest' --tests '*PairingCryptoTest' --tests '*PhoneActivationTest' --tests '*EndpointOfferTest'
JAVA_HOME=/home/lance/.local/jdks/temurin-17 ./gradlew :app:compileDebugKotlin :app:testTabletDebugUnitTest --tests '*CarPlaySettingsTest' --tests '*CarPlayNetworkSettingsTest' --tests '*CarPlayCoordinatorTest' --tests '*CarPlayOwnershipTest'
```
Run each under the shared host flock. XML counts were read while holding that same lock, avoiding another worker replacing task receipts.

## Remaining hardware/security boundaries
- Ordinary Play app visibility/up state of the actual GM AP interface, scoped bind permission, Wi-Fi AP ingress/isolation and iPhone initiation after iAP2 offer remain unverified. No vehicle actions, devices, release/package upload or commits performed.
- Unknown peer cannot bootstrap fresh controller enrollment: owner-provisioned pairing state must include the already trusted controller, or an explicit numeric peer is needed for the existing selected-peer enrollment path. A state file containing only accessory identity is insufficient.
- Skipping selected-IP PhoneActivation is not evidence that the phone initiates RTSP. No scan or unauthenticated advertised-phone selection was added to fill that gap.
- Exact address/zone binding is not privileged SO_BINDTODEVICE or proof of OEM UID routing policy; IPv4 override address binding does not independently prove physical ingress interface enforcement. No privileged routing/AP changes made.

## Files
New `carplay-lab/.../network/CarPlayNetworkPolicy.kt`, `CarPlayPeerAdmission.kt`; five library network JUnit test files and app `CarPlayNetworkSettingsTest.kt`. Modified app `CarPlaySettings.kt`, `OalCarPlayRuntime.kt`; library `ProjectionReceiver.kt`, `PhoneActivation.kt`, `PairingStore.kt`, `AirPlaySession.kt`, `CarPlayMediaEngine.kt`, `ScreenStream.kt`, `AudioStream.kt`, `NtpClock.kt`, `IapTunnel.kt`, `MicrophonePacketizer.kt`, `MicrophoneUplink.kt`. MediaPolicy/PeerPolicy and parent UI/VM files intentionally not modified.
