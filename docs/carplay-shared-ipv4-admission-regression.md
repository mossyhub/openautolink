# Shared-IPv4 admission-owner correction

## Cause and narrow change

Production trusted reconnect called `ProjectionReceiver.start(peerIp)`, selecting wildcard/legacy auxiliary transport but creating no `CarPlayPeerAdmission`. Consequently accepted sessions had `allowPairSetup=false` and the existing stored-key validation callback could never acknowledge M5.

`OalCarPlayRuntime` now calls `startSharedIpv4(network)` for both trusted reconnect and explicitly authorized manual first enrollment. This passes the already selected exact local IPv4 + numeric manual peer into the admission owner separately from transport selection. Listener binding remains wildcard; `AirPlaySession.socketBinding` remains null, preserving legacy auxiliary/media transport. Scoped IPv6/AP startup remains unchanged. Trusted-only startup without an exact admission selection fails closed.

Each actual accepted AirPlaySession is claimed by the existing admission owner before start. Existing dynamic PairSetup admission and stored-key validation/persistence callback now reach that owner. Existing-key M5 is read-only; PairVerify still gates authentication/media. No real phone trust or identity is cleared or edited.

## Executed TDD evidence

The new Robolectric production constructor/start/accept test first failed on the real six-byte Method0/State1 PairSetup request: expected HTTP200, actual HTTP403. After wiring correction it succeeds through independent SRP and signed M5, checks the unchanged durable state bytes and file identity (atomic replacement would change it), then performs real stored-key PairVerify. RECORD remains HTTP403 both before and after M5 until PairVerify.

Seven production-path tests cover stored-key reconnect, replacement key, unrelated controller, invalid signature, retirement during M5, authorized manual first enrollment, and accepted successor control-token ownership. Tests also assert wildcard listener and absent scoped session binding.

Robolectric API32 reports synthetic accepted peer 192.168.0.10; the fixture labels and selects this explicit address. Actual RTSP bytes are exchanged over a real TCP socket on a host-owned IPv4 address. This is production wiring/cryptography evidence, not physical-phone or media rendering evidence. Surface/MFi interface are mocked; the Android AVC inventory uses Robolectric builders.

Host JDK17/SDK gate under `/mnt/Temp/Hermes_Work/.oal-gradle.lock`:

```
./gradlew :carplay-lab:testDebugUnitTest :app:testDebugUnitTest :app:compileDebugKotlin --console=plain
```

BUILD SUCCESSFUL. Parsed XML: carplay-lab 222 tests, zero failures/errors/skips; app 945 tests, zero failures/errors, one skipped. Existing app test nullable warning and Mockito bootstrap-classpath JVM warning remain. Robolectric/Mockito test dependencies required generated verification entries; strict verification passed on subsequent runs.

Ready for parent APK rebuild/install and same-phone reconnect capture, preserving stored key. No device upload/install or commit performed by this worker.
