# Native CarPlay runtime integration handoff

> **Historical handoff:** This records an earlier development stage, not current setup guidance. USB CarPlay has since shipped; the former default-off/manual-enable rollout is superseded by the [current shipped policy](usb-hotplug-independent-protocol-modes.md#current-shipped-carplay-policy) and [CarPlay setup](../README.md#carplay-setup). Saved disabled choices are still respected. Wireless CarPlay remains in development.

## Entry / preference contract

- Production `ProjectionScreen` / `ProjectionViewModel` and the original `MainActivity`, not `BenchActivity`.
- Read the settings worker's **atomic** `AppPreferences.carPlaySettings: Flow<CarPlaySettings>` on each explicit connection. Never modify AA preferences to select CarPlay.
- At this handoff stage, enabling was inert and the projection screen exposed **Connect selected CarPlay phone (experimental)** only after manual enablement. The tap requested Bluetooth permissions, then reserved process admission. This is not the shipped USB onboarding flow.
- The runtime uses exact selected bonded Bluetooth identity, numeric selected peer **IPv4**, selected interface, explicit SSID and receiver address. No friendly-name protocol classification or automatic phone selection.
- `SavedWifiPassword` reads the existing encrypted no-backup credential. MFi identity and pairing state remain in the same app-private no-backup directories; no keys/assets/import were added.
- Shared-Wi-Fi override validates current SSID, security, channel, and interface ownership. Configured AP mode uses `wppChannelMhz`; **0 is not usable for CarPlay** and fails closed. OEM AP scoped IPv6 is not implemented. This is an experimental manual candidate, not proven OEM network integration.

## Ownership / rendering

- `CarPlayOwnership.process` reserves before AA retirement. SessionManager start/reconnect reject under their lifecycle mutex while this owner exists.
- The owned worker stops and joins the actual AA BT advertiser, then awaits `SessionManager.stop()` before constructing/bootstrap-starting the CarPlay adapter. Failed AA retirement does not start CarPlay or release admission.
- The adapter reuses library iAP2 identification/MFi authentication, real receiver listener, selected-peer PhoneActivation, EndpointOffer replies, and wireless runtime handoff.
- A real 16:9 SurfaceView lives in the existing projection destination. Settings/diagnostics overlay that Surface; touch is disabled while either shell is visible. Input forwards actual Surface-local coordinates into `ProjectionReceiver.touch`.
- Evidence is only promoted from receiver sink first-render / successful touch-send markers; RECORD and runtime authentication are not rendered-video proof. No audio/Siri/call proof is claimed.
- Stop, disable, ignition OFF, Activity stop, Surface loss and ViewModel clear revoke attempts. Reconnect reads a new saved snapshot after old receiver/control retirement. Return to Android Auto releases the exact CarPlay reservation only after retirement. Stop itself does not let an AA auto-reconnect undo the user's intent.
- Small library APIs expose bounded per-bootstrap replies and actual reader/link/receiver retirement. Historical EOF exceptions are kept separate from thread retirement acknowledgment; no blanket-public policy classes or duplicated protocol implementation.

## Verification

Host JDK17, shared `/mnt/Temp/Hermes_Work/.oal-gradle.lock`:

- `:app:compileTabletDebugKotlin`: succeeded.
- Focused `:app:testTabletDebugUnitTest`: **13 tests, 0 failed/error/skipped** (ownership 4, coordinator 5, evidence 2, real SessionManager lifecycle with mocked native boundary 2).
- Focused `:carplay-lab:testDebugUnitTest` Iap2RetirementTest: **3 tests, 0 failed/error/skipped** (historical EOF, still-blocked worker, RFCOMM reader retirement).
- Latest focused run: `proc_9b6d9558fd14`, exit 0; app compile ran with the final retirement facade changes. XML counts parsed while holding the same flock.
- Temporary external init script narrowed test compilation to these owned tests; **not** an unfiltered combined-tree suite claim. Parent owns full gate / assembly.
- No device installation, protocol exchange, rendered physical frame, touch acceptance, audio routing, vehicle AP proof, commit or release was performed.
