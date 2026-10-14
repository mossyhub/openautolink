# One-command personal AAB builder

The supported self-build path requires **Docker only** on an x86-64 Linux host or x86-64 Windows with WSL. The builder handles source updates, Android tooling, native dependencies, package identity, signing, versioning, and AAB verification inside isolated containers.

**Joining my list is the easiest route to builds and updates I manage, but it is not the only installation route.** My new tester intake is paused and wider Community delivery is not ready; anyone can instead use this builder and the [self-publishing guide](install-gm.md#independent-route-self-publish) to build OAL and publish/install through their own **Google Play Developer account**. Google's account verification, review and vehicle availability requirements still apply; a successful build does not guarantee installation eligibility. Source builds require your own compatible private files for CarPlay; see the [authentication/build policy](carplay-private-mfi-assets.md).

## Quick start

### Linux

Install Docker Engine, then run:

```bash
curl -fsSL https://raw.githubusercontent.com/mossyhub/openautolink/main/build-aab.sh | bash
```

### Windows

1. Install Docker Desktop.
2. Enable Docker Desktop integration for your WSL distribution.
3. Open that WSL terminal and run the same command:

```bash
curl -fsSL https://raw.githubusercontent.com/mossyhub/openautolink/main/build-aab.sh | bash
```

The host needs Docker, `curl`, `flock` (normally supplied by `util-linux`), and an x86-64 CPU; Android Studio, Java, Gradle, the Android SDK, NDK, CMake, Git, OpenSSL, and aasdk are supplied or managed by the builder image. ARM64 hosts are not currently published or supported. The bootstrap defaults to an immutable tested GHCR image digest rather than a mutable `latest` tag.

## First run

The setup screen asks for:

- a unique Android application ID, such as `com.yourname.openautolink`;
- the name to place in the personal signing certificate;
- a two-letter country code.
- the highest version code already uploaded to this Play app (`0` for a new app);
- an optional existing upload keystore, alias, and hidden passwords.

Do not use `com.openautolink.app`: that ID belongs to the upstream app. The builder passes your ID through Gradle; it does not rename Kotlin source packages because they are not the Play identity.

The container then:

1. creates a private state directory at `~/.openautolink-builder`;
2. clones current OpenAutoLink source;
3. downloads both native dependency archives from the upstream `native-deps` release;
4. verifies each archive against the SHA-256 digest published by GitHub;
5. imports your existing upload key, or generates a 4096-bit personal key with a random local password;
6. builds a minified, unsigned release AAB without mounting the signing secrets;
7. starts a separate network-disabled signing container with the key mounted read-only;
8. verifies the AAB signature, signer certificate, application ID, version code, and version name;
9. writes the AAB, portable checksum, and build metadata to `~/.openautolink-builder/output`.

## Later builds

Run the same command again:

```bash
curl -fsSL https://raw.githubusercontent.com/mossyhub/openautolink/main/build-aab.sh | bash
```

The builder remembers the package ID, signing identity, signing key, Gradle cache, native-dependency cache, and last successful local version. It ensures the pinned builder image is present, fetches current `main`, and creates the next signed AAB.

**As I push changes, you must pull those upstream changes, rebuild and publish your own updates.** Rerunning the builder handles the source fetch and rebuild, not the Play upload or rollout. Upload each new AAB to your own Play Console app and testing track, then update the app in the vehicle; my Play releases do not update your independently published app.

The version counter advances **only after** the AAB has built and passed verification. A failed build does not consume the next local version code.

## Output

A successful build produces files similar to:

```text
~/.openautolink-builder/output/
├── openautolink-car-0.1.1-1a2b3c4d.aab
├── openautolink-car-0.1.1-1a2b3c4d.aab.sha256
├── openautolink-car-0.1.1-1a2b3c4d.json
├── latest.aab -> openautolink-car-0.1.1-1a2b3c4d.aab
└── latest.json -> openautolink-car-0.1.1-1a2b3c4d.json
```

The JSON records the exact source commit, application ID, version, and signing-certificate SHA-256 fingerprint. Check that metadata before uploading to Play.

## Protect and back up the signing identity

Before uploading the first AAB, back up:

```text
~/.openautolink-builder/secrets/
```

That directory contains the upload key and its generated local password. It is created with restrictive filesystem permissions and is never copied into the builder image or source repository.

**Losing it can prevent future updates to the same Play app.** Anyone who obtains it can sign updates under your upload identity. Keep at least one encrypted offline backup; do not commit, email, or publicly share it.

The fetched source and Gradle build never receive the real secrets directory. Signing runs separately with networking disabled and the key mounted read-only. The trusted builder image still necessarily handles the key during signing, so use a computer and Docker daemon you trust.

## Saved state

```text
~/.openautolink-builder/
├── config/       # package ID, signer label, country, successful version
├── secrets/      # upload key and generated password — back this up
├── source/       # builder-owned OpenAutoLink checkout
├── cache/        # Gradle and verified native dependencies
├── staging/      # unsigned bundle handed to the offline signing step
├── home/         # container user home
└── output/       # verified AABs, checksums, and metadata
```

The checkout is builder-owned. Do not make source edits there; every run resets tracked files to the requested upstream ref.

## Useful commands

Show the saved non-secret configuration:

```bash
curl -fsSL https://raw.githubusercontent.com/mossyhub/openautolink/main/build-aab.sh | bash -s -- --show-config
```

Choose a different package ID while retaining the existing signing key:

```bash
curl -fsSL https://raw.githubusercontent.com/mossyhub/openautolink/main/build-aab.sh | bash -s -- --reset-config
```

The signer name and country are part of the certificate created on the first run; changing labels later cannot rewrite that certificate. To create a different signing identity, use a new state directory and treat it as a separate Play app.

Use a different state location:

```bash
OAL_BUILDER_STATE_DIR="$HOME/my-oal-builder" \
  bash -c "$(curl -fsSL https://raw.githubusercontent.com/mossyhub/openautolink/main/build-aab.sh)"
```

For automation or testing, set `OAL_NONINTERACTIVE=1` with `OAL_APP_ID`, `OAL_SIGNER_NAME`, and `OAL_COUNTRY`.

For an existing Play app, also set `OAL_START_VERSION_CODE` to its highest uploaded version code and provide `OAL_EXISTING_KEYSTORE`, `OAL_KEY_ALIAS`, `OAL_STORE_PASSWORD`, and—when different—`OAL_KEY_PASSWORD`. These values are used only during one-time import; later runs read the protected state directory.

## Migrating an existing self-published app

Use the exact application ID, upload keystore, key alias, and passwords used for the existing Play app. Enter the highest version code already uploaded, not the version name. The first builder output will use the next code.

The container validates the imported keystore and alias before Gradle runs. After building, it extracts the signer certificate from the AAB and requires its SHA-256 fingerprint to match that imported key.

If the original Play app uses a different app-signing arrangement, confirm in Play Console that the key you import is the accepted **upload key**. A new key cannot update the existing app unless Google Play has completed an upload-key reset.

## Uploading to Google Play

Upload `latest.aab` through your own Play Console app and internal or closed testing track. Continue with the self-publishing instructions in [Installing OpenAutoLink on a locked GM vehicle](install-gm.md#independent-route-self-publish).

Keep the same builder state directory for every update. Google Play requires each update to retain the application ID and signing lineage and use a higher version code.

## Troubleshooting

### Docker is not reachable

Start Docker Engine or Docker Desktop. On Windows, verify that Docker Desktop WSL integration is enabled for the distribution where the command runs.

### Package ID rejected

Use at least two lowercase dot-separated segments. Each segment must start with a letter and contain only lowercase letters, digits, or underscores.

### Builder state copied to another machine

Preserve file permissions and copy the entire state directory, especially `secrets/` and `config/build-state.properties`. Run the normal command afterward.

### Build fails while downloading

Run the same command again. Completed Gradle and native archives remain cached. Native archives are not extracted unless their GitHub-published digest verifies.

### Start completely over

Move the existing state directory to a backup location rather than deleting it. A new state directory creates a new signing key and version sequence and should not be used to update an app already published with the old key.
