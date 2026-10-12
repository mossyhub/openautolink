# Pairing-only bench: provenance and integration contract

## Source and license

Imported from the pinned local DiPlay revision
`e2fd8eadf5a6b705666f292055e72aa93387533d`, directory
`shared/src/main/java/com/shilapi/xcertplay/airplay/`.
DiPlay credits upstream [shilapi/xcertplay](https://github.com/shilapi/xcertplay)
(GNU GPL v3); full upstream `LICENSE` is retained alongside these sources.
Original comments, including LIVI's clean-room SRP attribution, are retained.
No private identity, certificate, key, media asset, or authentication data was imported.
Bouncy Castle is the existing module dependency (1.79), under its MIT-style license.

### Exact imported Kotlin files

- `AirPlayCrypto.kt`, `AirPlayIdentity.kt`, `Bytes.kt`
- `Srp6a.kt`, `Tlv8Codec.kt`, `PairSetup.kt`, `PairVerify.kt`, `PairingStore.kt`
- `ControlCipher.kt`, `RtspMessage.kt`, `BplistCodec.kt`
- `AirPlayConfig.kt`, `AirPlayInfoPlist.kt`, `AirPlayDisplaySettings.kt`, `AirPlayHid.kt`
- `MfiSapAuthSetup.kt`

No `AirPlaySession`, `CarPlayMediaEngine`, media receiver, timing server, event
channel, or IAP tunnel is imported. The bench wrapper follows AirPlaySession's
pairing routes and its plaintext final pair-verify response before enabling
ControlCipher. `/auth-setup` uses the exact upstream MFiSAP responder and the
module's existing MfiAuthenticator contract; the caller supplies/owns that instance.

### Local changes to the imports

- PairSetup/PairVerify require ordered, single-use transcripts; errors invalidate
  session state. Strict state lengths and controller key/signature/identifier
  lengths prevent stale-success and malformed-input acceptance.
- PairSetup persists only after verifying the controller's Ed25519 signature and
  constructing M6; storage errors return authentication failure, never success.
- PairingStore is synchronized, validates IDs/public-key lengths, refuses controller
  key replacement, and invokes durable persistence before publishing an entry.
- SRP client proofs use constant-time comparison.
- TLV8 rejects truncation/duplicate fields (255-byte continuation remains supported).
- ControlCipher caps incoming frames at its existing 16384-byte output maximum.
- AirPlayInfoPlist excludes the video-in-car branch; rejects video-in-car and
  buffered-audio configs. Buffered audio's source-backed type constant 103 is
  inlined to avoid importing its receiver. Original remaining declaration is kept.
- Added SPDX/source headers. Other imported behavior is unchanged.

## Public API

`com.openautolink.carplaylab.pairing.PairingProbeServer : java.io.Closeable`

```kotlin
val server = PairingProbeServer(context, receiverBtMac) { metadata -> report(metadata) }
server.setAuthenticator(authenticator) // optional, before start; caller retains ownership
server.allowPeer(setOf("192.168.0.47")) // numeric selected-phone addresses; no DNS
server.start()
// server.port is OS-assigned; server.pairingId and server.publicKeyHex are durable/public.
// PairingProbeServer.SOURCE_VERSION == "1.0", shared with discovery/EndpointOffer.
server.close()
```

Caller owns discovery, actual tablet IPv4 advertisement (initial bench address
`192.168.1.100`), Wi-Fi details, iAP2 offers, activity lifecycle, and selected-phone
IP discovery. The server does not embed either observed address as a default.
IPv6 testing must separately obtain/select the phone's address before allowing it.

## Security and limits

- Default empty allowlist rejects every TCP peer; each selection change closes all
  existing sessions and advances a generation checked around every request.
- Allowlist accepts numeric unicast IPs only. This is NOT physical-phone identity
  proof: IP reassignment/spoofing/interface ambiguity remain outside this API.
  Only successful stored-controller Ed25519 PairVerify authenticates a controller.
- Binds real IPv6 wildcard TCP port 0, backlog 2; reports actual local address/port.
  IPv4-mapped acceptance depends on Android's socket implementation and MUST be
  demonstrated by an actual IPv4 connection on the tablet. No guessed port or
  unobserved dual-stack/reachability claim is emitted. Bind failure propagates.
- Two concurrent workers, no waiting connection queue; at most 32 admitted selected
  connections per server lifetime and 16 requests per connection; 5s read idle timeout,
  30s absolute socket deadline (also closes stalled writes).
- Header cap 8192 bytes, body cap 32768 bytes, frame cap 16384 bytes; duplicate
  headers, transfer encoding, malformed lengths, truncation, and AEAD failures
  fail closed. Input is not read past the final pair-verify response boundary.
- `/pair-setup`, `/pair-verify`, `/info`, optional `/auth-setup` only. SETUP/media
  methods explicitly receive 403 and close. Unknown routes/methods receive 404.
  Missing/repeated MFiSAP receives 501, malformed SAP 400. Only one SAP response
  is emitted per connection. No credentials are loaded by the server.
- MFiSAP status means response constructed/sent, NOT phone acceptance or licensing
  established. PairVerify status appears only after real X25519/Ed25519 proof.
  Neither status means projection works. No media sockets are opened.
- Metadata callback is bounded/queued on a daemon (64 messages; overflow drops),
  never logs arbitrary paths/headers/body, controller IDs, private/public crypto
  material, certificates, challenges, signatures, or exception messages/stacks.
- Close stops network I/O and waits at most 750ms for transport threads. If a caller
  supplied authenticator ignores interruption, state ownership is retained until
  that worker exits; close still returns. A daemon callback blocked by caller code
  cannot be forcibly terminated. Callback must marshal UI work as appropriate.

## Persistent state

The public Context constructor uses only
`context.noBackupFilesDir/pairing-probe/{state.bin,state.lock}` (plus transient
`state.new`). Identity and one selected controller are stored together, owner-only,
with fsynced data and atomic same-directory replacement. An exclusive file lock
prevents stale concurrent writers. Ed25519 public key is derived from the persisted
seed. Corruption, unsupported atomic replacement, or competing ownership fails
closed; it never silently regenerates an existing identity. No existing identity
location is imported or accessed. Atomic replacement is crash-safe at the file
level; parent-directory power-loss durability is platform/filesystem dependent.

Only one controller is persisted, and key replacement is forbidden. Switching
physical phones is intentionally not an automatic trust reset; a separate explicit
bench reset policy would be needed. The server exposes no private-key getter.

`/info` uses upstream's actual binary plist builder, with explicit bench config
(name OAL pairing bench, sourceVersion 1.0, 1280x720 test display, no audio output).
Those are a probe declaration, not measurements or a promise that media/HID works;
SETUP remains forbidden regardless of the upstream feature table.

## Tests and verification boundary

JUnit sources cover independent SRP M1–M6/Ed25519 transcript, PairVerify proof and
replay, invalid AEAD/signature, TLV truncation/duplicates, control framing,
persistence reload/corruption/exclusivity, denied peers, revoked sessions, media
rejection, binary `/info`, missing SAP, numeric-IP policy, and bounded shutdown.
MfiSapAuthSetup tests use explicitly synthetic fixtures; no real MFi identities
are required and they do not claim real phone acceptance.

No Gradle/build/test compilation was run by this worker, per parent coordination.
Tests were written before the corresponding new wrapper implementations, but
RED/GREEN execution is pending the parent's unfiltered module test run. Host
static source checks are not a substitute for executing the crypto transcript
or validating the Android IPv4 bind and real phone handshake.
