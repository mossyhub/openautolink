# CarPlay network settings integration verification

## Outcome and boundaries

- Advanced network controls use the existing shared memory-only CarPlay draft and global Save & Reconnect action. No local Apply button and no Android Auto settings changes.
- Auto/IPv4/IPv6, exact optional local address, optional AP phone IP, AP frequency and WPA2/WPA3 are exposed. Shared LAN requires an explicit numeric phone IP. Manual HU Bluetooth MAC is explicitly distinct from AP BSSID and selected phone bond MAC; Android's 02:00:00:00:00:00 placeholder is rejected. This UI does not guess or copy identity/network values from Android Auto.
- Runtime config mapping uses saved CarPlay apFrequencyMhz, not AA wppChannelMhz, and names appended networkFamily/apSecurity/localAddress arguments. Disabled configuration cannot produce a mapping snapshot. Existing runtime still owns real interface/index/address and immutable persisted-controller admission.
- Unknown AP phone IP is saveable, but readiness explicitly states that one persisted trusted controller is required; it does not claim fresh provisioning or phone ingress readiness.
- All network fields survive atomic DataStore publication and serialization, with latest unedited CarPlay fields and independent AA preferences preserved. Failed validation retains pending network edits and prevents restart/close. Secret memory/atomic non-secret publication semantics are unchanged; the separate encrypted-secret file is not asserted crash-atomic with DataStore.
- No receiver/runtime network, appearance or reporter files edited. No device, release or commit operations. Hardware ingress, rendered Compose behavior, fresh trust provisioning and phone acceptance remain unverified.

## Executed TDD

Compiling assertion RED followed by GREEN for network family, AP radio profile, configured endpoints/scopes, unknown-AP readiness, runtime mapping/disabled mapping, and Advanced UI wiring. Existing generic draft behavior was characterized rather than unnecessarily rewritten.

One initial host run was a compilation blocker, not behavioral RED: the mirror lacked a sibling's existing OalCarPlayGnssSnapshotAdapter.kt; copying that source without editing it allowed the compiling RED rerun. A stale lexical placement guard depended on the old Phone IP label; it was updated to verify every network field remains Advanced-only. The new global-action lexical guard was corrected to inspect its actual owner AppNavHost.kt rather than SettingsScreen.kt. These fixture corrections are not represented as feature REDs.

## Final focused gate

Host: lance-host, mirror /mnt/Temp/Hermes_Work/oal-carplay-integration.
Every Gradle invocation and XML receipt read occurred under flock /mnt/Temp/Hermes_Work/.oal-gradle.lock, with JAVA_HOME=/home/lance/.local/jdks/temurin-17 and ANDROID_HOME=/home/lance/.local/android-sdk.

```sh
./gradlew :app:testTabletDebugUnitTest \
  --tests '*CarPlayNetwork*' --tests '*CarPlaySettings*' \
  --tests '*CarPlayAtomicCommitTest' --tests '*CarPlayCoordinatorTest' \
  --tests '*CarPlayOwnershipTest' --tests '*CarPlayPhysicalDisplaySettingsTest' \
  --tests '*CarPlayAutoScaleSettingsTest' \
  :app:compileDebugKotlin :app:compileTabletDebugKotlin
```

Final result: BUILD SUCCESSFUL; 54 tests, zero failures/errors/skips. Both compilation targets succeeded (final invocation up-to-date after earlier actual compilation during TDD). Existing unrelated Kotlin warnings remain. Full library/application suites were not run by this worker.

Exact selected classes and XML counts:
- data.CarPlaySettingsTest: 7
- data.CarPlayNetworkSettingsTest: 2
- data.CarPlayPhysicalDisplaySettingsTest: 5
- data.CarPlayAutoScaleSettingsTest: 1
- ui.settings.CarPlaySettingsTransactionTest: 8
- ui.settings.CarPlaySettingsPlacementTest: 10
- ui.settings.CarPlayAtomicCommitTest: 5
- ui.settings.CarPlayNetworkDraftTest: 4
- ui.settings.CarPlayNetworkRuntimeMappingTest: 2
- ui.settings.CarPlayNetworkControlsTest: 1
- carplay.CarPlayCoordinatorTest: 5
- carplay.CarPlayOwnershipTest: 4

All classes are under com.openautolink.app. XML receipts are app/build/test-results/testTabletDebugUnitTest/TEST-<fully-qualified-class>.xml. Final host log: /mnt/Temp/Hermes_Work/network-settings-final.log. Intermediate host logs use /mnt/Temp/Hermes_Work/network-settings-{red,green}-{family,radio,endpoints,readiness,mapping,controls}.log.

New tests:
- CarPlayNetworkDraftTest.invalidNetworkFamilyCannotBeCommitted
- CarPlayNetworkDraftTest.invalidApRadioProfileCannotBeCommitted
- CarPlayNetworkDraftTest.configuredEndpointsUseSelectedFamilyAndDeferInterfaceOwnershipToRuntime
- CarPlayNetworkDraftTest.unknownApPeerIsSaveableButNeverAdvertisedAsProvisionedReadiness
- CarPlayNetworkRuntimeMappingTest.savedCarPlayNetworkConfigNeverBorrowsAndroidAutoWppSettings
- CarPlayNetworkRuntimeMappingTest.disabledConfigurationCannotProduceRuntimeSnapshot
- CarPlayNetworkControlsTest.advancedNetworkControlsEditSharedDraftWithNoSectionApplyOrAaWrites (source contract, not Compose execution)
- CarPlayAtomicCommitTest.everyNetworkFieldCommitsAtomicallyWithoutClobberingLatestCarPlayOrAa (real preference DataStore)
- CarPlayAtomicCommitTest.malformedNetworkDraftKeepsAtomicRecordAndDoesNotRestart (real preference DataStore)

## Files changed by this worker

Production, under app/src/main/java/com/openautolink/app/:
- ui/settings/CarPlaySettings.kt
- ui/settings/SettingsViewModel.kt (CarPlay edit validation only)
- data/CarPlaySettingsPolicy.kt
- ui/projection/ProjectionViewModel.kt (createCarPlayRuntime config construction only)

Tests, under app/src/test/java/com/openautolink/app/:
- ui/settings/CarPlayNetworkDraftTest.kt (new)
- ui/settings/CarPlayNetworkRuntimeMappingTest.kt (new)
- ui/settings/CarPlayNetworkControlsTest.kt (new)
- ui/settings/CarPlayAtomicCommitTest.kt
- ui/settings/CarPlaySettingsPlacementTest.kt
- data/CarPlaySettingsTest.kt

Handoff: docs/carplay-network-settings-verification.md (this file). Outside the repo, /home/hermeswebui/.hermes/profiles/dev/cache/scratch/carplay_settings_gate.py is a reusable scoped mirror/locked Gradle/XML runner.
