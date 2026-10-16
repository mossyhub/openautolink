# Advanced build fallback: Docker

The [desktop builder](build-aab.md) is the novice workflow. This separate command-line fallback is for users who already run Docker or need its existing-keystore import. It does **not** require editing source files. Desktop packages are currently a packaging preview; see the build guide for availability.

This guide ends at a signed car AAB. Upload and installation use only the [dedicated Android Automotive OS Internal Test workflow](install-gm.md).

## Supported host and prerequisites

- **x86-64 Linux** with Docker Engine, or **x86-64 Windows using WSL** with Docker Desktop and integration enabled for that WSL distribution.
- Bash, `curl`, and `flock` (normally from `util-linux`) on the Linux/WSL host.
- A working Docker daemon and internet access.

The published builder image does not support ARM64 hosts. This fallback is **not a native macOS build recipe**; do not run Linux NDK dependency scripts on macOS expecting them to select Mac tools automatically. It also does not describe a mixed WSL-native-dependencies/Windows-Gradle build.

Java, Git, Gradle, Android SDK/NDK, CMake, OpenSSL, and native Android dependencies are provided or managed inside the builder. The bootstrap uses a pinned builder-image digest. Source selection defaults to `main`, unlike the desktop's stable-release workflow.

## First build

Review the [bootstrap source](../build-aab.sh) before executing downloaded code. In a Linux or WSL terminal, run:

```bash
curl -fsSL https://raw.githubusercontent.com/mossyhub/openautolink/main/build-aab.sh | bash
```

The setup asks for:

1. A unique application ID, not the upstream `com.openautolink.app`.
2. A signing-certificate label/name and two-letter country code.
3. The highest version code already uploaded to your Play app (`0` for a new app).
4. An optional existing upload keystore, alias, and hidden passwords; leave the keystore input empty to generate a key for a new app.

The bootstrap manages its checkout and dependencies, builds an unsigned AAB, then uses a separate network-disabled signing container. It verifies the bundle and writes the signed output, checksum, and metadata under:

```text
~/.openautolink-builder/output/
```

Choose `latest.aab` from that folder for Play upload after a successful build. Check `latest.json` for application ID, source revision, version, and signer fingerprint. Do not treat an old `latest.aab` left after a failed build as a new result.

This container path uses verified native dependency archives for both `arm64-v8a` and `x86_64`; you do not manually clone submodules or run the native dependency scripts here.

## Keep and back up the state

The default state directory is:

```text
~/.openautolink-builder/
├── config/       # package identity and successful build state
├── secrets/      # upload keystore and local passwords
├── source/       # builder-owned checkout
├── cache/        # cached dependencies and Gradle files
├── staging/      # unsigned build handoff
├── home/         # container home
└── output/       # verified signed bundles and metadata
```

**Before the first upload, back up `config/` and `secrets/` together in encrypted private storage.** This older builder stores its local secrets with restrictive permissions; it does not provide the desktop wizard's encrypted backup/restore interface. Do not email or commit the directory. Anyone with the key and passwords can sign under your upload identity.

The source checkout is disposable and builder-owned. Do not edit files in it; subsequent runs reset tracked files to the requested ref. Losing the upload key or resetting identity state can interrupt updates to an existing Play app.

## Repeat build or select a source revision

Run the first-build command again to reuse the saved identity and produce the next version from `main`.

To select a specific stable tag or commit rather than `main`, review that revision and replace `YOUR_VERIFIED_RELEASE_TAG` below with its **actual** tag. The uppercase token is an example placeholder, not a published release:

```bash
curl -fsSL https://raw.githubusercontent.com/mossyhub/openautolink/main/build-aab.sh \
  | OAL_REF='YOUR_VERIFIED_RELEASE_TAG' bash
```

Do not enter an arbitrary moving tag if you need a reproducible build; retain the resolved commit recorded in the output metadata. A source override does not change the bootstrap or pinned tool image.

Show saved non-secret settings:

```bash
curl -fsSL https://raw.githubusercontent.com/mossyhub/openautolink/main/build-aab.sh \
  | bash -s -- --show-config
```

Choose a different state folder for a **separate new app**:

```bash
curl -fsSL https://raw.githubusercontent.com/mossyhub/openautolink/main/build-aab.sh \
  | OAL_BUILDER_STATE_DIR="$HOME/my-oal-builder" bash
```

The version counter advances after successful verification. Always compare the result against the highest version code already uploaded to Play, especially when more than one computer has built the app.

## Existing apps

For an existing Play app, use the **exact application ID**, accepted **upload keystore**, alias, and passwords, plus the highest version code already uploaded. The interactive first-run prompts accept these values without source edits. Do not use a fresh generated key for an existing app unless Google has completed the corresponding upload-key reset.

The container validates an imported keystore before building and verifies the final signer against it. Google Play App Signing and your upload-signing key are distinct; check which upload key Console accepts before importing one.

Desktop identity archives and this container's state directory are different formats. Do not point one builder at the other's folder or assume automatic migration. Preserve the original state until an update built with the intended identity has been verified.

## CarPlay limits

The container bootstrap does not expose the desktop wizard's optional CarPlay file selection. An ordinary fallback build omits the authentication pair and does not enable CarPlay by itself. Do not put private files into its source checkout or assume they can be passed through an undocumented environment variable.

For the exact authorized external Gradle input contract, see [CarPlay authentication files](carplay-private-mfi-assets.md#advanced-external-gradle-input). The upload keystore is not a CarPlay authentication identity.

## Local script checks

For contributors working from an existing repository checkout, these commands check shell syntax and exercise the builder's regression tests without building an AAB:

```bash
bash -n build-aab.sh builder/build-in-container.sh
bash tests/builder/test_builder.sh
```

A passing script test is not a completed Android build or proof of Google Play delivery. Use the successful artifact's metadata, then follow the [Internal Test installation guide](install-gm.md).
