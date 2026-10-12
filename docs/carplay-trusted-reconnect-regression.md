# TCL trusted reconnect PairSetup regression

## Evidence and source comparison

Capture: `/home/hermeswebui/.hermes/profiles/dev/cache/scratch/oal-black-current.log`, process 23295, 2026-10-09 18:49:35 attempt. Offered H264 2560x1440@40; first POST body 6 bytes returned 403, followed by control EOF and session-ended. No screen stream had started. Source `AirPlaySession.handle` uniquely returns 403 for a POST at the PairSetup admission branch (other 403 is SETUP/RECORD pre-auth). Capture did not export the URL or TLV values, so exact captured bytes are not independently established.

Pinned DiPlay commit `e2fd8eadf5a6b705666f292055e72aa93387533d`:
- `shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlaySession.kt:546-555` always routes `/pair-setup` to its per-session responder, including when controller trust exists.
- `PairSetup.kt:34-105` completes fresh SRP with fixed code 3939, verifies encrypted controller Ed25519 M5, saves the presented key, and returns signed/encrypted M6. No reconnect skip requirement or trusted-only blanket guard.
- https://github.com/shilapi/xcertplay/blob/e2fd8eadf5a6b705666f292055e72aa93387533d/shared/src/main/java/com/shilapi/xcertplay/airplay/PairSetup.kt

OAL's new `CarPlayPeerAdmission.allowsPairSetup` previously allowed only first enrollment in an empty startup store. Thus a persisted-trust reconnect rejected M1, before its controller identity/key could be learned and authenticated. The six-byte fixture is normal TLV Method=0 + State=1, not a dump of captured private traffic. Source parity supports allowing this flow; only the parent-owned phone run can confirm the actual phone reuses its saved key.

## Minimal correction

Existing one-controller startup trust allows PairSetup exchanges for the exact claimed live control owner. Genuine SRP and Ed25519 M5 remain unchanged. The existing owner callback, serialized with retirement, accepts only the startup controller identifier and the exact stored 32-byte key, with the store's identifier set equal to startup trust. It does **not** call save: zero trust writes, no additions/replacements. Unknown/new/replacement keys return protocol authentication error after M5; retired/released owners cannot authorize it. PairSetup completion does not authenticate media. Existing stored-key X25519/Ed25519 PairVerify, control encryption, scoped peer admission, pre-auth timeout, MFi and empty-store bounded first enrollment remain unchanged.

Only executable production change: `carplay-lab/src/main/java/com/openautolink/carplaylab/network/CarPlayPeerAdmission.kt`. `PairSetup.kt` changes callback documentation only. Existing AirPlaySession and ProjectionReceiver already wire the dynamic owner predicate and post-proof callback; neither needs behavior edits.

## Real execution

Strict RED: new real TCP/RTSP regression compiled and failed assertion `Trusted reconnect must reach the real SRP responder, not blanket HTTP403`, expected 200, actual 403.
GREEN: same test passed after admission-only correction. Added security checks execute valid SRP with replacement/unrelated controller keys, invalid existing-key M5 signature, pending M5 after retirement, and released-token successor fencing. Existing contradictory blanket-denial policy test updated to proof-only semantics.

`TrustedPairSetupReconnectTest`: 6 tests. Real six-byte M1, independent SRP-3072/SHA512 client proof + server proof, actual encrypted/signed M5/M6, real TCP PairVerify, zero persistence writes, and RECORD403 until PairVerify. Synthetic identities/keys only. No raw device trust/credential reads.

Host execution under shared `/mnt/Temp/Hermes_Work/.oal-gradle.lock`, JDK17 `/home/lance/.local/jdks/temurin-17`, SDK `/home/lance/.local/android-sdk`:

```
./gradlew :carplay-lab:testDebugUnitTest --console=plain
./gradlew :app:testDebugUnitTest --tests 'com.openautolink.app.carplay.*' :app:compileDebugKotlin :app:compileTabletDebugKotlin --console=plain
```

Full receiver suite: BUILD SUCCESSFUL, parsed XML 215 tests / 53 suites, 0 failures/errors/skips, including all 6 new regression tests. Focused app tests and both app Kotlin variants: BUILD SUCCESSFUL. Final combined rerun parsed XML while holding the same shared lock: app CarPlay 72 tests / 21 suites and receiver 215 tests / 53 suites, all zero failures/errors/skips. An earlier separate XML read caught another owner's credential-import UI RED (not this run); same-lock rerun avoids misattributing shared output. Existing JVM sharing warning and tablet ProjectionViewModel inference/deprecation warnings, no errors.

## Parent-owned physical acceptance

Rebuild/install current tablet APK without clearing no_backup/pairing-probe/state.bin or forgetting the receiver on the phone. Keep selected phone/shared LAN/credentials/MFi intact. Reconnect should now show PairSetup 200 responses, then genuine PairVerify and stream SETUP rather than the initial POST403. Verify panel rendering, visible post-touch change and surviving control session separately. A later M5 authentication error would indicate the phone presented a different identifier/key or invalid proof, not permission to replace stored trust. Do not weaken admission or erase pairings to conceal that result. No device, release/signing, upload or commit operation was performed by this worker.
