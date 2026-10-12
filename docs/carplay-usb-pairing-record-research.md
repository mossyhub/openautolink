# USB Lockdown pairing-record rejection research (build 511)

## Scope and outcome

Read-only production/reference-source investigation; no phone, ADB, USB or network-interface operations; no production/test source changes. Only this report is added to the repository. Synthetic keys and a standalone JVM harness were created under `$TMPDIR/lockdown-record-research`, never from owner pairing records. Those scratch files are disposable; their complete harness sources are reproduced below.

**No root cause is established.** The actual OAL generator produced independently parseable certificates with valid RSA/SHA-256 signatures, matching private/public keys and correct device SKI. Production Pair serialization independently decoded correctly. The second Pair is not intrinsically wrong: other implementations explicitly repeat Pair after pending approval, including with an unchanged record. The strongest outstanding discriminator is the exact remote error (currently grouped), followed by phone-side acceptance versus the immutable pending record; neither can be inferred from a Trust/PIN interaction alone.

### Supplied physical evidence (not re-tested here)

Direct-cable build 511: USB permission/re-enumeration, USBMUX and ProductType passed. Pair at `08:18:55.190` returned trust-pending. Owner completed Trust + PIN. On the same channel, second Pair at `08:19:10.380` returned grouped `INVALID_RECORD` at `08:19:10.397`; no prior detach. Pair separation is **15.190 seconds**, second-response latency **17 ms**. This is a prompt remote rejection, not a host read timeout or demonstrated physical disconnect. It does not prove certificate validation was completed by the first pending response.

OAL checkout HEAD is `114ae144ea6eacf786fbd66b6ea116e28bede9ed`, with a substantial existing integration worktree; current511 is the supplied installed-build context, not a Git ref. The inspected files are worktree files, not necessarily immutable build511 artifact sources. Their SHA-256 fingerprints at research time:

- `LockdownPairRecord.kt`: `436a0bda99fa78167f8ad4edbac4502f58bb22fa3c0683fd809fa8c378f182d2`
- `LockdownPairingClient.kt`: `2ada85cd042d70e64e6e61ac299cbbd0d83f40bfd6950c8176090993bf1308ed`
- `LockdownPlistChannel.kt`: `13fcc66b356b00827e18eb8d38f42357b70ef2d7b3af9fb3036742f7e87f6179`

DiPlay checkout: `e2fd8eadf5a6b705666f292055e72aa93387533d`.

## Prioritized discrepancies / discriminators

| Priority | Concrete source observation | Relevance and test |
|---|---|---|
| P0 | OAL `UsbCarPlayFailurePolicy.kt` groups `InvalidPairRecord`, `MissingPairRecord`, `InvalidHostID` into `INVALID_RECORD`. libimobiledevice maps these separately [L1]. | Existing archive cannot identify which rejection occurred. Parent owns enum specificity; do not assume malformed cert or missing record from this bucket. Keep allowlisted exact remote code plus request phase, not unrestricted remote text. |
| P1 | OAL record is generated once before the pending loop, retained across fifteen-second waits, and resent without new key reads, BUID writes, cert regeneration or escrow insertion (`LockdownPairingClient.kt:53–87`). | Actual synthetic-peer harness confirms two **byte-identical production Pair frames** for pending→success and pending→each rejected-record code. This disproves a host record mutation in this loop, not phone acceptance. First pending may defer deeper record validation; owner approval is not a returned Pair-success receipt. |
| P1 | Retry policies differ: current DiPlay closes/reopens channel and resets `UntrustedHostBUID`, but keeps the same generated record; pymobiledevice3 retries the same options/record on its service, first retry immediately, then one-second sleeps [P2]; go-ios returns pending and documents calling Pair again, generating new certs and HostID [G1]; libimobiledevice `lockdownd_pair(NULL)` generates a fresh record on each invocation [L2]. | There is no universal upstream “never repeat Pair after Trust” rule, nor evidence that samechannel+15s is required. DiPlay is not an independent cert implementation. Do not silently replace OAL with regeneration, reopen or `StartSession` fallback. Separate those hypotheses one variable at a time only in owner-authorized later tests. |
| P1/P2 | OAL manually emits DER and verifies only the signed TBS (`LockdownPairRecord.kt:224–265`), because JBR rejects empty issuer DN. | TBS signature verification alone misses malformed outer X509 structure. Independent Python cryptography parse/signature/key checks **passed** on actual generated records. Generic OpenSSL chain verify fails on both OAL and the reference empty-DN chain (error 18), so that failure is not OAL-specific malformed-record proof. |
| P2 | Serial = 0; empty subject/issuer; SHA256withRSA; RSA2048; notBefore = current host clock, not backdated. | These match libimobiledevice's **OpenSSL** generator [L3]. GnuTLS/mbedTLS use serial 1; Python uses serial 1 and one-minute backdate [L3,P1]. Positive serial/backdate are concrete differential candidates, not established repairs. A host clock ahead of phone could reject validity, but no car clock evidence exists. Do not replace empty names with invented CNs merely to satisfy JCA. |
| P2 | Device SKI = SHA1(DER PKCS#1 public key), no root/host SKI. | Matches OpenSSL `subject_key_identifier=hash` and Python reference, independently validated. GnuTLS currently uses version-selected digest (SHA256 on modern devices), and mbedTLS adds root/host SKIs [L3]. Upstream backends differ; absent root/host SKI or a 20-byte device SKI is not inherently a defect. |
| P2 | OAL generates two lowercase UUID strings per fresh runtime attempt; uses the same BUID for `UntrustedHostBUID` and `PairRecord.SystemBUID`; saves only after Pair success (`OalUsbCarPlayRuntime.kt:160–170`). | libimobiledevice/go-ios/tidevice get a persistent machine BUID from usbmuxd [L4,G1,T1]; go-ios/tidevice uppercase HostID. UUID case is a low-confidence compatibility delta, not a violation established by protocol source. Fresh retries across runtime recreation use different identities; same-loop retry does not. A persistent app BUID could be investigated without touching key material, but do not assert it explains this attempt. |
| P2 | OAL accepts any parsed dictionary with no `Error` as success, without checking `Request==Pair` or legacy `Result==Failure` (`LockdownPairingClient.kt:73–77,185–191`). | Concrete host-side validation gap: synthetic reflection tests show `{}`, wrong Request, and `Result=Failure` classify no-error. libimobiledevice and Python validate echoed Request and legacy Result [L1,P2]. This can create false success/persist an unaccepted record; it does **not** explain the supplied second reply when a real Error was received. Add future behavioral cases with real generated records, not one-byte restored dummy certs. |
| P3 | Pair success wraps optional EscrowBag; runtime extracts `.pairRecord`; `UsbLockdownTrust` stores six PEM buffers/IDs/WiFi, not escrow. CarKit StartService sends no escrow. | Upstream stores response EscrowBag and WiFi only **after** success [L2,P2,T1]. Omission is a downstream persistence/service parity issue, not a cause before the second Pair succeeds. No source asks host to synthesize escrow or mutate certs on Trust approval. |
| P3 | OAL `restore` validates only nonempty bytes/nonblank fields, not cert/key consistency; local load failure becomes synthetic `RemoteError("InvalidPairRecord")`. | Need provenance distinction between local store invalidity and remote device error in future diagnostics. In this supplied pending→Pair-requested sequence a fresh generated record was used, so an existing saved-record load failure does not fit. DiPlay's saved-record clear/re-pair recovery applies to later StartSession rejection, not this fresh pending Pair failure. |

## Exact generator and wire comparison

OAL `carplay-lab/src/main/java/com/shilapi/xcertplay/transport/LockdownPairRecord.kt` was read in full, including PEM decoding, DER length/minimal-positive integer handling, OID/base128 writer, UTF8/UTC time encodings, extension nesting and key defensive copies. The material-generator body is the same profile as local DiPlay (apart from header/line offsets), so a reference-app result cannot independently validate the certificate implementation.

| Property | OAL | Reference findings |
|---|---|---|
| Root/host keys | Separate RSA2048 keys | libimobiledevice and Python also separate; tidevice reuses host/root identity [L3,P1,T2] |
| Certificate public keys | Root=root, host=host, device=advertised phone RSA key | Synthetic actual-generated n/e matches; root signs all three |
| DER | X509 v3, serial0, RSA SPKI with NULL algorithm parameters, SHA256 RSA signature | OpenSSL libimobiledevice matches; Python serial1 |
| DN | Empty subject and issuer | OpenSSL/Python/go-ios empty DNs; general RFC profile validation is not Lockdown validation |
| Extensions | Root critical BC CA=true; leaves critical BC CA=false and KU digitalSignature+keyEncipherment; device noncritical SKI | OpenSSL same. BC=false is DER empty sequence (DEFAULT false omitted); KU DER `03 02 05 A0` is correct, not a suspicious magic byte |
| Private key PEM | PKCS8 `BEGIN PRIVATE KEY` | OpenSSL/Python PKCS8; go-ios PKCS1. Never sent in OAL Pair |
| PairRecord | Exactly DeviceCertificate/HostCertificate/RootCertificate **data**, HostID/SystemBUID **string** | Exact external-record libimobiledevice and go-ios field sets [L2,G1]. Missing DevicePublicKey/WiFiMACAddress is not a required-field discrepancy; tidevice adds DevicePublicKey, Python adds WiFiMACAddress and even RootPrivateKey on this version's wire [P2,T1]. Do not copy Python private-key disclosure into OAL. |
| Pair envelope | Label, Request="Pair", ProtocolVersion string "2", PairingOptions dict ExtendedPairingErrors boolean true | libimobiledevice/go-ios match [L2,G1]. Python also sends HostName; not universally required. No EnableWifiPairing option is demonstrated necessary. SupervisorCertificate/ChallengeResponse are supervised-device paths, not ordinary owner Trust |
| XML/framing | UTF8 XML, PEM base64 encoded once as plist data; four-byte big-endian **byte** count | Actual production serializer parsed via independent `plistlib`, including escaped metacharacters and café Unicode; all fields/types/PEM bytes matched |

## Post-Trust state and replies

There is no inspected implementation that changes the existing certificate chain or learns a replacement DevicePublicKey purely because the owner presses Trust. The phone's internal trust/pending state can change asynchronously; host code sees that only through later replies/notifications. OAL repeats the immutable five-field public record. Python does likewise; success adds local HostPrivateKey and response EscrowBag before saving, not before the approved retry. libimobiledevice's internal API and go-ios instead create fresh records when their Pair function is invoked again, but retain the device-returned BUID identity source.

No source guarantee says the pending reply means certificates were fully validated. Likewise, no success reply/escrow exists in the supplied evidence. Starting a session after owner approval without receiving Pair success is an unverified diagnostic hypothesis, not the established canonical transition. Current libimobiledevice for modern iOS starts a session only after Pair success or a previously saved record; ValidatePair is reserved to older-iOS handshake paths [L2].

A useful future finite receipt matrix is: exact request kind; attempt index; unchanged channel/record generation counters; exact remote code; echoed Request present/matching; Error type (string/integer/other); Result recognized; escrow present/type/length bucket. Never dump certs, HostID, BUID, owner serial, escrow or unrestricted ExtendedResponse. Compare within-attempt equality privately; synthetic tests can retain full frames.

### Issues: source diversity and limitations

- [I1] Historical iOS7 issue reports explicitly say repeat Pair while waiting for user confirmation, and discuss notification-proxy `com.apple.mobile.lockdown.request_pair`, persistent BUID and interactions with PTP/other processes. These are historical observations, not a contemporary iOS26 samechannel proof; its comments include guesses about chaperone certificates. Current source takes precedence over unsupported issue speculation.
- [I2] usbmuxd #281 reports accepted Trust then no persisted host record, with a user-confirmed subsequent manual Pair succeeding on iOS26.3.1. It supports distinguishing user consent from host-record completion; it does not establish OAL's error mechanism. Its title labels pending `-8`, inconsistent with current libimobiledevice enum (`MUX_ERROR=-8`, pending=-19); use strings and the exact library version, not copied numeric claims.
- [I3] SHA1 policy discussion contains reporter experiments with SHA256 on iOS4/15/16; current libimobiledevice uses SHA256 for modern devices. It does not justify downgrading OAL to SHA1 because go-ios's older profile does so. TLS-policy failures are later than this plaintext Pair stage.
- [I4] DiPlay #57 is an iOS26 wired report; local `docs/ISSUE-AUDIT-2026-10-03.md` describes successful video/audio followed later by USB read failure and saved-record StartSession InvalidHostID. Its controller `:1925–1933,2043–2060` only clears/re-pairs a **saved** record rejected at CarKit session open. That is a different phase and cannot diagnose this fresh pending Pair rejection.

## Executed synthetic validation

Executed the actual OAL generator, Pair builder (reflection only to reach a private method), XML serializer/parser and trust store via standalone Kotlin2.1/JDK17. Only USB host/stream and Android Xml plumbing were substituted. Desktop KXML lacks Android's DOCDECL feature toggle, so the scratch Xml adapter ignores the `false` toggle; no production XML changes were made. This is not Android integration or phone validation.

Results from the final run:

```text
PASS actual generator, defensive copy, persistence, production Pair serializer byte identity
CONFIRMED reply-validation gap: empty/wrong Request/Result Failure all classify no-error
PASS scripted pending->Success: 2 identical Pair frames, 1 channel, 1 close
PASS scripted pending->InvalidPairRecord: 2 identical Pair frames, 1 channel, 1 close
PASS scripted pending->InvalidHostID: 2 identical Pair frames, 1 channel, 1 close
PASS scripted pending->MissingPairRecord: 2 identical Pair frames, 1 channel, 1 close
PASS scripted pending->UserDeniedPairing: 2 identical Pair frames, 1 channel, 1 close
PASS parse/signature/profile root serial 0 sig sha256
PASS parse/signature/profile host serial 0 sig sha256
PASS parse/signature/profile device serial 0 sig sha256
PASS private key correspondence, device n/e, SKI SHA1(PKCS1)
PASS independent plistlib: exact five public fields, PEM bytes as data, boolean options, UTF8 length/escaping
PASS differential pymobiledevice3 device key/SKI; confirmed serial=1 vs OAL=0 and backdate policy
```

All three certs in this run were valid `2026-10-11T15:31:11Z` through `2036-10-08T15:31:11Z` (3650-day lifetime). OpenSSL normal and strict verification returned exit2/error18 self-signed certificate for both OAL leaves; a separately generated Python-reference leaf/root pair returned the **same** exit2/error18. Explicit root-public-key signature checks passed for all OAL certs. Do not rewrite this as “OpenSSL verifies the chain” or “OpenSSL failure proves malformed OAL cert”. No phone acceptance was exercised.

Additional proposed local tests, not run here: deterministic boundary clock injection around 2049/2050; skew/expiry validators; malformed/trailing/nonminimal PKCS1 DER rejection matrix; independently parse SPKI input as a negative expected-input test; save/load interrupted publication/corrupt-store/key-mismatch tests; exact numeric Error/ErrorString precedence and unexpected Error dictionary shape. These are local correctness tests, not evidence that any owner record had those defects.

## Reproduction (owner-secret-free)

Prerequisites used: `/hermes_work/.jdk17/bin/java`; scratch Maven jars listed below; Python3 with `cryptography` installed under scratch `pylibs`; OpenSSL. The host initially had no Java on PATH, no Kotlin compiler and no cryptography. Existing JDK17 was discovered; dependencies downloaded to scratch only. Git status required `--ignore-submodules=all` because a pre-existing external aasdk symlink causes default status to error.

Set `P=$TMPDIR/lockdown-record-research`. Save the following snippets with their named files in P. Download jars from `https://repo.maven.apache.org/maven2/` into P/jars:

```text
org/jetbrains/kotlin/kotlin-compiler-embeddable/2.1.0/kotlin-compiler-embeddable-2.1.0.jar
org/jetbrains/kotlin/kotlin-stdlib/2.1.0/kotlin-stdlib-2.1.0.jar
org/jetbrains/kotlin/kotlin-script-runtime/2.1.0/kotlin-script-runtime-2.1.0.jar
org/jetbrains/kotlin/kotlin-reflect/1.6.10/kotlin-reflect-1.6.10.jar
org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar
org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.6.4/kotlinx-coroutines-core-jvm-1.6.4.jar
org/jetbrains/annotations/13.0/annotations-13.0.jar
net/sf/kxml/kxml2/2.3.0/kxml2-2.3.0.jar
```

Install `python -m pip install --target "$P/pylibs" cryptography`. Download [P1] raw source to P/pymobile-ca.py for the differential test. Run `python "$P/run_probe.py" && python "$P/validate.py"`. The harness generates synthetic device RSA keys, synthetic host/BUID UUIDs, synthetic escrow `01 02 03` and synthetic phone label; do not replace any input with owner material. Its five pending cases include real fifteen-second grace, so allow time for completion. It only compiles named production sources to scratch; do not invoke Gradle/build/install/device tools.

### Stubs.kt

```kotlin
package com.shilapi.xcertplay.transport
interface BlockingDuplexByteStream: java.io.Closeable { fun send(bytes:ByteArray); fun recv(size:Int, timeoutMillis:Long):ByteArray? }
class Iap2UsbMuxHost(val stream:BlockingDuplexByteStream) { var connects=0; fun connect(destinationPort:Int=62078,timeoutMillis:Long=5000):BlockingDuplexByteStream { connects++;return stream }; companion object { const val LOCKDOWN_PORT=62078 } }
sealed class IphoneUsbException(message:String):java.io.IOException(message) { class Protocol(m:String):IphoneUsbException(m); class DeviceUnavailable(m:String):IphoneUsbException(m); class TimedOut(m:String):IphoneUsbException(m) }
```

### Xml.kt

```kotlin
package android.util
// KXML desktop compatibility shim only: Android supports disabling DOCDECL.
object Xml { @JvmStatic fun newPullParser():org.xmlpull.v1.XmlPullParser = object:org.kxml2.io.KXmlParser() { override fun setFeature(name:String,state:Boolean) { if(name==org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_DOCDECL && !state) return;super.setFeature(name,state) } } }
```

### Probe.kt

```kotlin
package com.shilapi.xcertplay.transport
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.util.Base64

fun main(args:Array<String>) {
 val out=File(args[0]);out.mkdirs()
 // OpenSSL-generated synthetic device key only; no device/owner files.
 val device=File(out,"synthetic-device-public.pem").readBytes()
 val hostId="11111111-2222-4333-8444-555555555555"
 val buid="AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE"
 val record=LockdownPairRecordGenerator.generate(device,"02:00:00:00:00:01",hostId,buid)
 for ((name,data) in listOf("device" to record.deviceCertificatePem,"host" to record.hostCertificatePem,"root" to record.rootCertificatePem,"host-key" to record.hostPrivateKeyPem,"root-key" to record.rootPrivateKeyPem)) File(out,"$name.pem").writeBytes(data)
 val copied=record.hostCertificatePem;copied.fill(0);check(record.hostCertificatePem[0].toInt()!=0)
 val trust=com.openautolink.carplaylab.usb.UsbLockdownTrust(File(out,"synthetic-trust"));trust.save("SYNTHETIC-PHONE",record)
 val restored=checkNotNull(trust.load("SYNTHETIC-PHONE"));check(restored.hostId==hostId && restored.systemBuid==buid);check(restored.hostCertificatePem.contentEquals(record.hostCertificatePem))
 val stream=object:BlockingDuplexByteStream { override fun close(){};override fun recv(size:Int,timeoutMillis:Long):ByteArray?=null;override fun send(bytes:ByteArray){File(out,"pair-frame.bin").writeBytes(bytes)} }
 val client=LockdownPairingClient(Iap2UsbMuxHost(stream))
 val method=client.javaClass.getDeclaredMethod("pairRequest",String::class.java,LockdownPairRecord::class.java).apply {isAccessible=true}
 val request=method.invoke(client,"Synthetic & <USB> \"research\" café",record) as LockdownPlistValue.Dictionary
 val channel=LockdownPlistChannel(stream);channel.send(request);val before=File(out,"pair-frame.bin").readBytes();channel.send(method.invoke(client,"Synthetic & <USB> \"research\" café",restored) as LockdownPlistValue.Dictionary);check(before.contentEquals(File(out,"pair-frame.bin").readBytes()))
 println("PASS actual generator, defensive copy, persistence, production Pair serializer byte identity")
 val errorMethod=client.javaClass.getDeclaredMethod("errorCodeOrNull",LockdownPlistValue.Dictionary::class.java).apply {isAccessible=true}
 for (reply in listOf(LockdownPlistValue.Dictionary(emptyMap()),LockdownPlistValue.Dictionary(mapOf("Request" to LockdownPlistValue.Text("GetValue"))),LockdownPlistValue.Dictionary(mapOf("Request" to LockdownPlistValue.Text("Pair"),"Result" to LockdownPlistValue.Text("Failure"))))) {check(errorMethod.invoke(client,reply)==null)}
 println("CONFIRMED reply-validation gap: empty/wrong Request/Result Failure all classify no-error")
 // Exercise real pairing loop/channel with only scripted plaintext peer input.
 for (terminal in listOf("Success","InvalidPairRecord","InvalidHostID","MissingPairRecord","UserDeniedPairing")) {
  var pending=ByteArray(0);var pairs=0;var closes=0;val frames=mutableListOf<ByteArray>()
  val peer=object:BlockingDuplexByteStream {
   override fun close(){closes++}
   override fun recv(size:Int,timeoutMillis:Long):ByteArray {val n=minOf(size,pending.size);return pending.copyOfRange(0,n).also {pending=pending.copyOfRange(n,pending.size)}}
   override fun send(bytes:ByteArray){
    val xml=String(bytes.copyOfRange(4,bytes.size));val content=when {
     xml.contains("<string>Pair</string>")-> {frames+=bytes;pairs++; if(pairs==1) "<key>Error</key><string>PairingDialogResponsePending</string>" else if(terminal=="Success") "<key>EscrowBag</key><data>AQID</data>" else "<key>Error</key><string>$terminal</string>"}
     xml.contains("<string>DevicePublicKey</string>")->"<key>Value</key><data>${Base64.getEncoder().encodeToString(device)}</data>"
     xml.contains("<string>WiFiAddress</string>")->"<key>Value</key><string>02:00:00:00:00:01</string>"
     else->""
    }
    val response="<plist version=\"1.0\"><dict><key>Request</key><string>${if(xml.contains("<string>Pair</string>")) "Pair" else "GetValue"}</string>$content</dict></plist>".toByteArray()
    pending=ByteBuffer.allocate(4).putInt(response.size).array()+response
   }
  }
  val host=Iap2UsbMuxHost(peer);var code="Success"
  try { val paired=LockdownPairingClient(host).pair("Synthetic",hostId,buid,25000);check(paired.escrowBag!!.contentEquals(byteArrayOf(1,2,3))) } catch(e:LockdownPairingException.RemoteError){code=e.code} catch(e:LockdownPairingException.UserDeniedPairing){code="UserDeniedPairing"}
  check(code==terminal);check(pairs==2 && host.connects==1 && closes==1);check(frames[0].contentEquals(frames[1]))
  println("PASS scripted pending->$terminal: 2 identical Pair frames, 1 channel, 1 close")
 }
}
```

### run_probe.py

```python
from pathlib import Path
import os, subprocess
p=Path(os.environ['TMPDIR'])/'lockdown-record-research'
r=Path('/hermes_work/oal-carplay-integration')
jars=list((p/'jars').glob('*.jar'));cp=':'.join(map(str,jars));java='/hermes_work/.jdk17/bin/java'
subprocess.run(['openssl','genpkey','-algorithm','RSA','-pkeyopt','rsa_keygen_bits:2048','-out',str(p/'synthetic-device-private.pem')],check=True,capture_output=True)
subprocess.run(['openssl','rsa','-in',str(p/'synthetic-device-private.pem'),'-RSAPublicKey_out','-out',str(p/'synthetic-device-public.pem')],check=True,capture_output=True)
files=[p/'Stubs.kt',p/'Xml.kt',p/'Probe.kt']+[r/'carplay-lab/src/main/java/com/shilapi/xcertplay/transport'/f for f in ['LockdownPairRecord.kt','LockdownPairingClient.kt','LockdownPlistChannel.kt','LockdownPairingStage.kt']]+[r/'carplay-lab/src/main/java/com/openautolink/carplaylab/usb/UsbLockdownTrust.kt']
subprocess.run([java,'-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(p/'classes')]+list(map(str,files)),check=True)
subprocess.run([java,'-cp',str(p/'classes')+':'+cp,'com.shilapi.xcertplay.transport.ProbeKt',str(p)],check=True)
```

### validate.py

```python
from pathlib import Path
import os,sys,hashlib,plistlib,subprocess,warnings
p=Path(os.environ['TMPDIR'])/'lockdown-record-research';sys.path.insert(0,str(p/'pylibs'))
from cryptography import x509
from cryptography.hazmat.primitives import serialization,hashes
from cryptography.hazmat.primitives.asymmetric import padding
warnings.simplefilter('ignore')
certs={n:x509.load_pem_x509_certificate((p/f'{n}.pem').read_bytes()) for n in ['root','host','device']}
root=certs['root']
for n,c in certs.items():
 root.public_key().verify(c.signature,c.tbs_certificate_bytes,padding.PKCS1v15(),hashes.SHA256())
 assert c.serial_number==0 and len(c.subject)==0 and len(c.issuer)==0
 assert c.extensions.get_extension_for_class(x509.BasicConstraints).value.ca==(n=='root')
 if n!='root':
  ku=c.extensions.get_extension_for_class(x509.KeyUsage);assert ku.critical and ku.value.digital_signature and ku.value.key_encipherment
 print('PASS parse/signature/profile',n,'serial',c.serial_number,'sig',c.signature_hash_algorithm.name,'validity',c.not_valid_before_utc.isoformat(),c.not_valid_after_utc.isoformat())
for n in ['root','host']:
 key=serialization.load_pem_private_key((p/f'{n}-key.pem').read_bytes(),None);assert key.public_key().public_numbers()==certs[n].public_key().public_numbers()
device=serialization.load_pem_public_key((p/'synthetic-device-public.pem').read_bytes());assert device.public_numbers()==certs['device'].public_key().public_numbers()
der=device.public_bytes(serialization.Encoding.DER,serialization.PublicFormat.PKCS1)
assert certs['device'].extensions.get_extension_for_class(x509.SubjectKeyIdentifier).value.digest==hashlib.sha1(der).digest()
print('PASS private key correspondence, device n/e, SKI SHA1(PKCS1)')
f=(p/'pair-frame.bin').read_bytes();assert int.from_bytes(f[:4],'big')==len(f)-4
record=plistlib.loads(f[4:]);pr=record['PairRecord'];assert set(pr)=={'DeviceCertificate','HostCertificate','RootCertificate','HostID','SystemBUID'}
for n in ['Device','Host','Root']:assert isinstance(pr[n+'Certificate'],bytes) and pr[n+'Certificate']==(p/(n.lower()+'.pem')).read_bytes()
assert record['ProtocolVersion']=='2' and record['PairingOptions']=={'ExtendedPairingErrors':True};assert record['Request']=='Pair';assert record['Label']=='Synthetic & <USB> "research" café'
print('PASS independent plistlib: exact five public fields, PEM bytes as data, boolean options, UTF8 length/escaping')
for leaf in ['host','device']:
 for flags in [[],['-x509_strict']]:
  r=subprocess.run(['openssl','verify','-auth_level','0',*flags,'-CAfile',str(p/'root.pem'),str(p/f'{leaf}.pem')],capture_output=True,text=True)
  print('OPENSSL',leaf,'strict' if flags else 'normal',r.returncode,(r.stdout+r.stderr).strip())
# Differential: current Python reference uses serial 1 and backdated notBefore.
import importlib.util
spec=importlib.util.spec_from_file_location('reference_ca',p/'pymobile-ca.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
h,hk,d,r,rk=m.generate_pairing_cert_chain((p/'synthetic-device-public.pem').read_bytes())
c=x509.load_pem_x509_certificate(d);assert c.serial_number==1
assert c.extensions.get_extension_for_class(x509.SubjectKeyIdentifier).value.digest==certs['device'].extensions.get_extension_for_class(x509.SubjectKeyIdentifier).value.digest
print('PASS differential pymobiledevice3 device key/SKI; confirmed serial=1 vs OAL=0 and backdate policy')
```

## Pinned citations

All eight pinned raw source files were fetched and byte-compared with the initially retrieved branch-tip files; every comparison matched. Line ranges refer to those pinned revisions.

- **L1** [libimobiledevice lockdown.c error map and reply validation](https://github.com/libimobiledevice/libimobiledevice/blob/fa0f79190142bc309307967c058f89c1b36eb6b8/src/lockdown.c#L55-L215).
- **L2** [lockdown.c modern handshake, record generation, Pair wire, options and success persistence](https://github.com/libimobiledevice/libimobiledevice/blob/fa0f79190142bc309307967c058f89c1b36eb6b8/src/lockdown.c#L699-L1081).
- **L3** [userpref.c complete certificate generator, OpenSSL/GnuTLS/mbedTLS branches](https://github.com/libimobiledevice/libimobiledevice/blob/fa0f79190142bc309307967c058f89c1b36eb6b8/common/userpref.c#L423-L1037).
- **L4** [userpref.c BUID and usbmuxd save/read](https://github.com/libimobiledevice/libimobiledevice/blob/fa0f79190142bc309307967c058f89c1b36eb6b8/common/userpref.c#L204-L343).
- **P1** [pymobiledevice3 ca.py complete generator](https://github.com/doronz88/pymobiledevice3/blob/852bbea1a40fb655e72d55e06d909fab2e8838a1/pymobiledevice3/ca.py#L13-L240).
- **P2** [pymobiledevice3 lockdown.py Pair record/save](https://github.com/doronz88/pymobiledevice3/blob/852bbea1a40fb655e72d55e06d909fab2e8838a1/pymobiledevice3/lockdown.py#L638-L694) and [reply validation/pending retry](https://github.com/doronz88/pymobiledevice3/blob/852bbea1a40fb655e72d55e06d909fab2e8838a1/pymobiledevice3/lockdown.py#L1030-L1099).
- **G1** [go-ios pair.go ordinary Pair and wire structures](https://github.com/danielpaulus/go-ios/blob/ca2c56d2c294ff702cadf4821edd30b069d3a264/ios/pair.go#L177-L304).
- **G2** [go-ios crypto_utils.go full generator/profile](https://github.com/danielpaulus/go-ios/blob/ca2c56d2c294ff702cadf4821edd30b069d3a264/ios/crypto_utils.go#L20-L196).
- **T1** [tidevice _device.py Pair and persistence](https://github.com/alibaba/taobao-iphone-device/blob/7bfd4d64da636500db688befbb64851078913589/tidevice/_device.py#L185-L239).
- **T2** [tidevice _ca.py generator](https://github.com/alibaba/taobao-iphone-device/blob/7bfd4d64da636500db688befbb64851078913589/tidevice/_ca.py).
- **I1** [libimobiledevice #20 historical Trust handling](https://github.com/libimobiledevice/libimobiledevice/issues/20).
- **I2** [usbmuxd #281 first-pair pending retry report](https://github.com/libimobiledevice/usbmuxd/issues/281).
- **I3** [libimobiledevice #1355 SHA1 policy discussion](https://github.com/libimobiledevice/libimobiledevice/issues/1355).
- **I4** [DiPlay #57 iOS26 wired report](https://github.com/shihabal3amri/DiPlay/issues/57); contextual phase audit is local `/hermes_work/research/DiPlay/docs/ISSUE-AUDIT-2026-10-03.md`.

## Decision boundary

Do not change NCM or re-enumeration policy on this evidence: those prerequisites passed and this attempt reached a real Pair reply without prior detach. Do not delete/replace owner records, downgrade signatures, regenerate on pending, add escrow to first Pair, or treat a scripted peer as physical proof. Exact-code visibility plus immutable-record validation narrows the next authorized test; it does not warrant a claimed repair or a guessed root cause.
