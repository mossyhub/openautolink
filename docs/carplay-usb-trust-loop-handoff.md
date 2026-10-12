# USB Trust-loop bounded handling handoff

## Verified outcome

No devices, launches, installation, hold code, credentials, VM or manager edits. Source synchronized selectively to lance-host. Final JDK17 serialized host gate: `:app:testDebugUnitTest :carplay-lab:testDebugUnitTest :app:compileDebugKotlin`, `--no-daemon --max-workers=1`, heap768m/metaspace512m/ActiveProcessorCount2, Kotlin in-process. BUILD SUCCESSFUL. XML: app1062 tests /0failures /0errors /1skip; carplay-lab332 /0 /0 /0. Existing compiler deprecation/nullability warnings remain. No release APK or minified DEX gate claimed.

Behavioral RED verified for dropped finite receipts, retry-fence policy, actual repeated runtime permission entry, missing ordered pairing receipts, carkit typed remote errors, and peer EOF classification. Final unfiltered suites pass. Tests additionally exercise password/user-denial single Pair, pending cancellation and real production ZIP receipt preservation.

## Evidence and primary-source interpretation

Parent observation: USBMUX and ProductType remained live during20:06:49.578–20:07:49.623 pre-Pair hold; no removals during hold. Deliberate cancellation ended the diagnostic. Topology loss13seconds later could be cleanup; it does not prove an independent electrical failure. Parent reference DiPlay0.2.13 reports PasswordProtected on each Pair with retry UI. Hub+PD was stable prepair. Neither establishes why the on-phone Trust PIN disappears.

Primary sources read:
- https://github.com/shihabal3amri/DiPlay/blob/main/shared/src/main/java/com/shilapi/xcertplay/transport/LockdownPairingClient.kt
- https://github.com/shihabal3amri/DiPlay/blob/main/shared/src/main/java/com/shilapi/xcertplay/transport/LockdownPairRecord.kt
- https://github.com/shihabal3amri/DiPlay/blob/main/shared/src/main/java/com/shilapi/xcertplay/transport/Iap2UsbMuxHost.kt
- https://github.com/libimobiledevice/libimobiledevice/blob/master/src/lockdown.c (`lockdownd_do_pair`, `lockdownd_pair`, error map)
- https://github.com/libimobiledevice/libimobiledevice/blob/master/tools/idevicepair.c (`print_error_message`)
- https://github.com/libimobiledevice/libimobiledevice/blob/master/common/userpref.c (certificate generation)

PasswordProtected, PairingDialogResponsePending, UserDeniedPairing, InvalidPairRecord/InvalidHostID are distinct remote errors, not aliases. idevicepair asks for passcode entry on-device then a user retry; pending asks for Trust approval then another pairing invocation. libimobiledevice sends one Pair and returns its result; this does not establish a mandatory same-channel pending retry policy. DiPlay reconnects its Lockdown channel for pending only and retains generated record within the attempt. OAL retains that protocol behavior: no NCM reorder, channel-reuse modification, automatic record deletion, or certificate change.

OAL certificate generator differs from retrieved DiPlay only by attribution and equivalent Base64 implementation. Both use2048-bit RSA, SHA256withRSA, zero serial, empty names, root-signed host/device profile. libimobiledevice supports SHA256 for modern iOS. USBMUX implementation differences were logging removal and awaited reader retirement, not request framing changes. No evidence found justifying a speculative cert/USBMUX fix.

## Changes

- LockdownPairingStage metadata-only enum: connecting, host-buid, device-key, wifi-address, record-prepared, pair-requested, trust-pending, paired. Callback does not receive raw values and cannot disrupt protocol operation.
- Runtime forwards these stages through existing bounded sanitizer/reporter. Finite failure reasons distinguish passcode protection, user denial, pending deadline, invalid record, transport loss, permission, cancellation, unknown remote error and invalid response.
- Existing CarPlayConnectionFailure UI gets USB-specific guidance, rather than class-only generic failure. It reports what the phone said without claiming the phone was visibly locked. Invalid record is not deleted/repaired automatically.
- LockdownCarKitClient preserves remote error code in typed RemoteError rather than asking callers to parse exception messages. LockdownPlistChannel maps actual empty peer stream to EOFException, distinct from malformed XML.
- Runtime-owned process-memory exact enumeration fence precedes USB permission/VPN/Pair. It survives manager recreation and blocks fresh attempts under unchanged deviceName/deviceId/vendor/product. Replacement permission failures fence the replacement. A changed/absent exact enumeration clears the fence at subsequent admission; cancellation does not set it. No timer-based retry and no public serial/device identity diagnostics.

## Remaining manager/UX boundary

Manager rejectedDeviceNames is per-instance. Its releaseIphone resumes scanning; a newly created manager may re-admit the same enumeration. Runtime fence now prevents another permission/Trust transaction, but does NOT prevent coordinator/runtime recreation or generic manager “Waiting” status. Parent should separately add manager admission persistence or explicit retry-generation wiring if desired; do not claim runtime policy fixed manager scanning. Current Retry/Save cannot bypass the runtime fence on unchanged attachment: UI guidance deliberately says reconnect once. Fence is memory-only and cannot survive process death. Actual physical detach followed by same reused enumeration without an intervening runtime snapshot is conservatively still blocked.

## Smallest next parent-owned hardware test

Use unchanged hub+PD, one receiver, no hold. Start one normal OAL USB attempt with phone passcode already entered. Capture pairing stage → terminal finite reason plus USB topology timestamps. If PasswordProtected/user denial/deadline occurs, leave topology unchanged and invoke Retry once: require retry=blocked and no additional permission dialog or pair-requested receipt. Then stop. Do not repeat uninstrumented Trust attempts. This validates bounded handling, not projection success or disappearance root cause.
