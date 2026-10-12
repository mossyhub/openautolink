# Private internal MFi asset bundling

## Certification and provenance notice

OAL's CarPlay receiver is not an Apple-certified product. Internal test builds reuse an existing accessory authentication identity, not a newly issued MFi identity for OAL. The upstream receiver describes its identity as recovered from public Carlinkit firmware; that is upstream's stated provenance, not an independent certification or redistribution grant. A bundled private key is extractable. Future iOS acceptance, reliability across head units and suitability for general distribution remain unresolved.

## Source and release policy

The Git repository and source archives exclude accessory credentials and Android signing keys. Tests use synthetic identities. Ordinary source/CI builds omit runtime identity assets; authorized local internal builds explicitly select an external asset directory. On first CarPlay use, the app validates and installs bundled credentials into backup-excluded storage without ADB, preserving existing valid credentials. Phone trust is established separately by the normal protocol exchange.

Builders may obtain compatible runtime assets from an APK they already possess and are authorized to inspect. Extract `assets/offline-mfi/identity.pk8` and `assets/offline-mfi/certificate.p7b` into the external layout below. These are accessory authentication files, not the Android APK signing key. Extraction does not confer redistribution rights. Do not submit the files in issues, pull requests or source archives.

Owner-authorized internal builds may set the exact Gradle property:

```sh
./gradlew :app:assembleTabletDebug -PoalCarPlayMfiAssetsDir=/absolute/external/private-asset-root
# Same property applies to :app:bundleRelease / :app:assembleRelease.
```

The external root must contain **exactly**:

```text
private-asset-root/
  offline-mfi/
    identity.pk8
    certificate.p7b
```

Do not put these files in this repository, source assets, Gradle properties checked into Git, or generated source directories. The canonical root must be outside the repository; the two files must be nonempty regular files, each at most 16 KiB, with no symlinked offline-mfi directory or credential files. Partial, extra, absent or oversized specified inputs fail build configuration. Missing property is an ordinary unbundled build. Assets are supplied directly from the external directory; no source-tree staging task is used. Android build intermediates still contain copies in ignored build outputs.

**Extractability:** APK/AAB assets are extractable by anyone with the artifact. This feature is not encryption, hardware-backed MFi storage, distribution permission or proof of iPhone acceptance. Owner-authorized internal test releases may contain these credentials; recipients can extract them. Ordinary source/CI builds omit them. This is not a claim of Apple certification or redistribution permission. Keep artifacts, intermediates and build caches private; do not use remote/shared build caches or verbose diagnostic uploads for private builds. Removing the property removes the bundle from subsequently merged assets, but does not securely erase old artifacts/caches. The owner must manage their retention. Only parent handles the real private input and device installation.

## First use / preservation

`OalCarPlayRuntime` invokes `CarPlayMfiAssetBootstrap(context.noBackupFilesDir, openAsset).install()` immediately before `LocalMfiAuthenticationClient.load`, after normal network/password prerequisites. Asset listing is lazy, so existing valid private storage does not open/read the bundle. This runs on the runtime's existing worker, not a new application/main-thread initializer.

Bootstrap shares `CarPlayCredentialImporter.LOCK` with SAF import. It preserves an existing validated identity, rejects corrupt existing storage rather than silently replacing it, and recovers an importer's prior whole-directory publication before considering fallback. On absent storage, it rejects partial assets and delegates bounded, fsync'd staged import and whole-directory publication to `CarPlayCredentialImporter`. Validation uses the existing P-256 certificate/key matching proof; a packaged pair is not trusted just because the build included it. Storage remains `noBackupFilesDir/offline-mfi`. No private bytes or provider/crypto exception messages are published via logs or StateFlow; only finite result enums leave the helper. SAF explicit replacement remains available.

## Verified execution

On lance-host, JDK17/Android SDK, shared `/mnt/Temp/Hermes_Work/.oal-gradle.lock`:

- Executed assertion RED then GREEN for missing helper, preservation behavior, Gradle property wiring and runtime hook.
- `:app:testTabletDebugUnitTest --tests '*CarPlayMfiAsset*Test' --tests '*CarPlayCredentialImport*Test'`: **16 tests, zero failures/errors**. Includes actual generated synthetic P-256 signing, missing/partial/malformed/mismatched/oversized assets, preservation/corruption, publication recovery, symlink rejection, stream cleanup and SAF regression coverage.
- Actual Gradle synthetic external-input probe rejected a partial layout, merged both files in tabletDebug and release, then verified removing the property removed stale merged identity directories in both variants.
- Compilation of runtime production and unit-test Kotlin succeeded. Existing carplay-lab targetSdk deprecation warning remains.

No owner credentials read, copied or printed; no APK/AAB build, device install, upload or commit performed here. Parent still owns full-suite, package/device and physical-phone verification.
