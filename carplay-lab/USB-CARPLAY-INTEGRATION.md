# Existing USB mode: wired CarPlay integration

## App API

`OalUsbCarPlayRuntime(context, device: UsbDevice, display: CarPlayDisplayPreferences,
 surface: Surface, report: (String) -> Unit, onDisplayQualified: (CarPlayDisplayPreferences) -> Unit = {}) : CarPlayRuntime`

`touch(actionMasked, actionIndex, pointers: List<CarPlayPointer>, width, height)` matches wireless.
Telemetry is the same exact `ProjectionReceiver` snapshot; display qualification is the same frozen decoder offer.
Appearance/GNSS/vehicle data reuse OAL intake adapters; metadata and artwork reuse its exact metadata link.

The parent must retain this instance for touch and coordinate existing USB mode:
iPhone candidate + saved CarPlay enabled -> this runtime; Android -> existing AA path regardless of that flag.
Do not create a new USB mode or toggle. `isCandidate(device)` is Apple VID AND USBMUX descriptor shape, not proof of iPhone.
The runtime requires granted initial USB permission and privately readable serial; then Lockdown ProductType proves an iPhone.

## Manifest and user consent (parent-owned)

```xml
<service android:name="com.openautolink.carplaylab.usb.OalUsbCarPlayVpnService"
 android:permission="android.permission.BIND_VPN_SERVICE"
 android:exported="false">
 <intent-filter><action android:name="android.net.VpnService" /></intent-filter>
</service>
```

Runtime calls `UsbCarPlayVpnConsent.await(VpnService.prepare(context)) { canceled }` when necessary.
The UI must launch/complete exactly that generation; denial cancels startup, never silently approves.
No Bluetooth/SSID/AP configuration is needed. Existing INTERNET/USB host capability applies;
existing microphone and optional fine-location permissions retain their normal media/data meaning.
No added app Gradle dependency is needed.

The VPN scopes only the OAL package and `fe80::/64`; bridge filters IPv6 to link-local/ff02 discovery.
It does not capture IPv4/default routes, alter Wi-Fi, or enable tethering. Android permits one VPN per user:
consent can replace another active VPN. Explain this to the user; vehicle/AAOS VPN availability remains hardware QA.
The service is wired-only, cannot replace an active exact owner, and detaches only its token.

## Ownership and protocol

Bounded DiPlay transport files (GPL-3.0-only attribution retained) implement USBMUX v2, Lockdown pairing/TLS/carkit,
NCM USB host, NTB16 and Ethernet IPv6 framing. No upstream controller or syslog relay was copied.
USBMUX and NCM use separate owned Android USB connections. No raw transport data or private identifiers are logged.
Lockdown trust is atomic private-file publication under app `noBackupFilesDir/usb-lockdown`; filenames are serial digests.
Receiver identity/pairings keep the existing app-private receiver persistence.

Reenumeration: one vendor request, 15-second window, observed selected detach, same USB bus directory,
one candidate and exact private serial after fresh OS permission. Missing serial, ambiguity or mismatch fails closed.
The runtime does not connect other phones automatically. Initial USBMUX-only discovery remains a candidate;
iPad/iPod are rejected by Lockdown before pairing. The iPhone Trust prompt may require unlock and acceptance.
Saved trust rejection fails closed; no hidden loop silently replaces durable trust.

The actual selected NCM data interface number is serialized in wired identification.
After identification and MFi succeed, receiver listens on the exact scoped TUN address and offers
its actual bound port/key via wired CarPlayStartSession. Bootstrap reads only until an authenticated
AirPlay session exists, then hands the same USB CSM to receiver runtime. Wired disables the AirPlay
secondary iAP2 tunnel, preventing duplicate consumers/metadata owners. The original USB artwork callback
forwards into the receiver's metadata link. GNSS/vehicle/appearance subscriptions live on that exact link.

`cancel()` fences admission and new resource installation without blocking UI. `close()` closes owned CSM,
receiver, metadata, TUN, USB and binding handles; it waits for CSM and receiver worker retirement and retains
failed resources for retry. Parent may admit AA only after successful close; failure is not retirement.
Caller Surface is never released. Physical iPhone connection is deliberately not attempted while reserved.

## Verification receipts

Behavioral RED: Apple VID-only candidate gate: 3 tests, 1 assertion failure.
Behavioral RED: replacement mismatch/window gate: 4 tests, 1 assertion failure.
Behavioral RED: wired receiver without SSID/BT identification: 5 tests, 1 assertion failure after fixing test constructor.
GREEN: those 5 tests plus actual `:app:compileDebugKotlin` passed in serialized host Gradle (30s).
Full unfiltered library gate `:carplay-lab:testDebugUnitTest :app:compileDebugKotlin`: BUILD SUCCESSFUL (26s).
Final USBMUX awaited-retirement gate `:carplay-lab:testDebugUnitTest :app:compileReleaseKotlin`: BUILD SUCCESSFUL (24s).
Parsed exact JUnit XML: 74 suites, 332 tests, zero failures/errors. Wired additions account for
67 tests (44 USBMUX framing, 8 USB read queue, 4 Ethernet IPv6, 3 NTB16, 4 candidate/reenumeration,
2 trust persistence, 1 scope-filter and 1 receiver identification). Transport tests are retained upstream
regressions; policy/receiver slices have explicit behavioral RED/GREEN. Trust/scope tests were added
after implementation, not claimed as strict TDD. No fake phone transcript or physical result is represented.

The USB-specific stage strings still require the parent diagnostic export allowlist; existing audio,
display and receiver stages use the existing reporters. Do not infer USB stage absence from sanitized archives.
These are source/JVM receipts, NOT proof of physical USB rendering, audio, Trust, NDP or car VPN support.
