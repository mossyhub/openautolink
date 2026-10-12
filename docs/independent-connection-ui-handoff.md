# Independent normal connections and temporary USB prompt — UI handoff

## Outcome and scope

UI source implemented in the real OAL settings/projection composition. This work does not modify ViewModels, DataStore implementation, USB dispatch, runtime, permissions, PIN/Trust handling, release configuration or backend-owned tests. It does not claim rendered Compose, physical phone/vehicle behavior or release readiness.

Connection now has separate compact **CarPlay connection: USB / Wireless** and **Android Auto connection: USB / Wireless** choices in their own groups. The former shared Connection mode selector and legacy AA hotspot/topology/manual-IP settings are removed from this page. The header explains that a USB phone can temporarily take over only after confirmation without changing saved modes.

- Android Auto continues using the existing `viewModel.updateDirectTransport(mode)` convention. Inspection confirmed that it already writes AA preferences asynchronously; this UI work preserves that established behavior rather than introducing a second AA draft/API.
- CarPlay uses `updateCarPlayDraft { it.copy(connectionMode = mode) }`, retained across tabs and persisted only by the existing awaited global Save & Reconnect transaction. Selecting a connection never changes enabled or AA normal mode. The selector remains editable when CarPlay is disabled, but respects loaded/saving gates. CarPlay enabled remains backend-default false.
- CarPlay Wireless remains selectable. Collapsed help explicitly says wireless CarPlay is unverified in the car. Wireless paired-phone and advanced network inputs are visible only for the wireless draft; USB does not present them as mandatory setup. Authentication recovery remains available for enabled CarPlay independently of its normal transport.
- One common `UsbHotPlugSwitchDialog` call occurs before the CarPlay/AA branch split. It observes the agreed ViewModel StateFlow, is independent of connected/streaming state, and is not nested in Settings/Stats. It waits while settings/diagnostics/the parent safe editor are open.
- The dialog asks `Switch to [Android Auto / CarPlay] for this connection?`, with **Switch / Stay here**. Both actions capture `prompt.generation`; back/outside dismiss uses the same explicit Stay callback. The UI performs no preference write, permission request or protocol retirement.
- Pending USB confirmation suppresses the competing CarPlay failure dialog and old USB picker. The safe editor remains a sibling inside each existing exact `ProjectionViewport` parent; no Surface composition/key/lifecycle changes were made.

## Exact owned repository files

Production:

- `app/src/main/java/com/openautolink/app/ui/settings/SettingsScreen.kt`
- `app/src/main/java/com/openautolink/app/ui/settings/CarPlaySettings.kt`
- `app/src/main/java/com/openautolink/app/ui/projection/ProjectionScreen.kt`

Tests:

- `app/src/test/independent_connection_ui_contract.py` — new compiler-independent source UI assertions.
- `app/src/test/java/com/openautolink/app/ui/settings/IndependentConnectionUiTest.kt` — new equivalent source wiring/regression assertions in Gradle.
- `app/src/test/java/com/openautolink/app/ui/settings/IndependentConnectionDraftIntegrationTest.kt` — new real DataStore/draft transaction and disk-reopen integration tests.
- `app/src/test/java/com/openautolink/app/ui/settings/CarPlaySettingsPlacementTest.kt` — advanced-network placement expectation updated to the wireless-and-advanced gate.

Documentation:

- `docs/independent-connection-ui-handoff.md` — this file.

Only the owned source/test files were rsynced to the host mirror, using the shared build lock. No credentials/assets/build trees/devices/uploads/commits were touched.

## Verification actually run

Four source-UI assertion RED → GREEN slices were exercised before their UI implementation: independent selectors; common exact-generation USB confirmation; wireless-only setup; confirmation precedence over other modal UI. An initial missing-substring test error was corrected and rerun as an assertion failure before implementation. Final local and host Python runs each returned **4 tests, all passing**:

```sh
python3 app/src/test/independent_connection_ui_contract.py -v
```

Final focused host Gradle gate returned **BUILD SUCCESSFUL in 1m24s**. XML parsed while holding the same shared lock: **40 tests, 0 failures, 0 errors, 0 skipped**.

| Suite | Tests |
|---|---:|
| IndependentConnectionUiTest | 4 |
| IndependentConnectionDraftIntegrationTest | 4 |
| CarPlaySettingsPlacementTest | 10 |
| CarPlaySafeEditorHostTest | 5 |
| CarPlayAtomicCommitTest | 6 |
| CarPlaySettingsTransactionTest | 10 |
| CarPlayNetworkControlsTest | 1 |

The disk-backed integration suite verifies: disabled CarPlay connection edits stay pending until global Save without enabling CarPlay/changing AA; connection edits survive Video/Display edits and merge into the latest record; opposite AA/CP normal modes persist across DataStore close/reopen; failed commit retains the draft without restart/close. These are regression tests against the real backend contract supplied by its owner, not UI-owned backend implementation changes.

Host gate: SSH `lance-host` using `/home/hermeswebui/.hermes/home/.ssh/config`, mirror `/mnt/Temp/Hermes_Work/oal-carplay-integration`, JDK `/home/lance/.local/jdks/temurin-17`, SDK `/home/lance/.local/android-sdk`:

```sh
flock -w 300 /mnt/Temp/Hermes_Work/.oal-gradle.lock \
  env JAVA_HOME=/home/lance/.local/jdks/temurin-17 \
      ANDROID_HOME=/home/lance/.local/android-sdk \
  timeout 420 ./gradlew :app:testTabletDebugUnitTest \
    --tests '*IndependentConnection*' \
    --tests '*CarPlaySettingsPlacementTest' \
    --tests '*CarPlaySafeEditorHostTest' \
    --tests '*CarPlayAtomicCommitTest' \
    --tests '*CarPlaySettingsTransactionTest' \
    --tests '*CarPlayNetworkControlsTest' \
    --max-workers=1 --no-daemon --console=plain \
    -Dorg.gradle.jvmargs='-Xmx768m -XX:MaxMetaspaceSize=384m -XX:ActiveProcessorCount=2' \
    -Pkotlin.compiler.execution.strategy=in-process
```

No source/test compilation exclusions were used. Complete app test sources compiled; selected suites executed. Existing Kotlin/JVM deprecation/nullability/intersection warnings remain.

Earlier gate attempts encountered the in-progress backend runtime `usbAdmission` named-argument mismatch and one new-test suspension call inside a nonsuspending restart callback. The backend mismatch subsequently disappeared as the owner integrated its files; the test callback was corrected. Neither compile failure was counted as behavioral RED. No remaining focused-gate blocker.

## Parent acceptance left

Parent owns the completed-tree combined release/full regression gate. Physical validation was explicitly out of scope: rendered dialog/controls, AA WPP remaining healthy with enabled USB CarPlay, Android/iPhone hot-plug Switch/Stay during live AA/CP, detach/stale-generation behavior, exact temporary restoration, USB/VPN/Trust flow, safe editor interaction, and car wireless support are not proven by these source/host tests.
