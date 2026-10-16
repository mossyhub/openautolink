# CarPlay authentication files

CarPlay requires a **compatible authentication key and certificate pair that you own or are authorized to use and include in your build**. Public OAL source and ordinary public builds do not supply these files. Building the app, generating an Android signing key, or paying for a Google Play developer account does not create a CarPlay identity.

**OAL is not an Apple-certified CarPlay product.** Local validation only checks the supplied pair; it does not establish Apple authorization, certification, redistribution rights, or acceptance by an iPhone. Future iOS compatibility and behavior across head units are not guaranteed.

## Select the pair in the desktop builder

In the [desktop builder](build-aab.md), use the two optional file pickers:

| File | Required content |
|---|---|
| `identity.pk8` | P-256 private key in PKCS#8 format |
| `certificate.p7b` | Matching single X.509 certificate, in supported DER or PKCS#7 form |

Each must be a nonempty regular file, no larger than **16 KiB**. Supply both or neither. File extensions alone do not establish validity; the private key and certificate must match. Do not substitute an Android keystore, an APK signature, or an unrelated certificate.

- **With an authorized compatible pair:** the builder can bundle it for your personal car app. Successful packaging is not proof that a phone will accept it.
- **Without the pair:** build for Android Auto without bundled CarPlay authentication. CarPlay will not work merely because its controls are visible.

Keep the originals in private storage outside the source checkout. Do not put credentials in issues, pull requests, public release attachments, or screenshots. This guide does not provide sources or acquisition instructions for authentication identities.

## These are not your Android signing keys

The desktop builder generates and preserves an **Android upload-signing key** for your Play submissions. Google Play App Signing manages the key used for the APKs delivered to the car. Those Android signing identities are separate from the two CarPlay authentication files.

Back up the builder's upload identity using its encrypted backup action. Keep authorized CarPlay originals separately; do not assume an identity backup includes external files.

## Bundling does not keep the pair secret

**Anyone who obtains a credential-bearing APK or AAB can extract its bundled assets.** Git exclusion, a signed AAB, Play App Signing, and an encrypted desktop backup do not make embedded files confidential. Include a pair only if your authorization covers that use and exposure, including distribution to your Internal Test recipients.

Keep credential-bearing outputs, build intermediates, and caches private. Do not use shared or remote build caches or upload verbose build archives containing those files. Removing the optional pair from a later build does not securely erase previous artifacts or caches; manage their retention yourself.

## What happens on the car

On first CarPlay use, OAL checks for an existing locally validated identity. If none is installed and a complete bundled pair is present, it validates and imports that pair into private, backup-excluded app storage. It preserves an existing valid identity rather than automatically replacing it with the bundled one.

A partial, malformed, or mismatched pair is not treated as working authentication. Corrupt existing storage is not silently overwritten. Explicit replacement is available through the app's authentication import control while parked and disconnected, where the vehicle's document-provider support permits it.

Importing the pair does not pair or trust an iPhone. Continue with the separate [CarPlay connection and permission setup](../README.md#carplay-setup) after installation.

## Advanced: external Gradle input

Desktop users do not need this section. For an authorized developer build, the existing Gradle property is **`oalCarPlayMfiAssetsDir`**. Point it at an absolute directory **outside the repository** with exactly this layout:

```text
private-asset-root/
└── offline-mfi/
    ├── identity.pk8
    └── certificate.p7b
```

The root must contain only `offline-mfi/`, and that directory must contain only the two named files. The directory and files must not be symlinks. Empty, oversized, extra, or partial inputs fail configuration. Do not copy files into source assets or generated source directories.

For example, the Gradle argument is:

```text
-PoalCarPlayMfiAssetsDir=/absolute/external/private-asset-root
```

This is an argument, not a standalone build command; it applies to `:app:bundleRelease` and APK build tasks. Omitting the property produces an ordinary unbundled build. Android build intermediates still contain copies, even though the input lives outside the checkout.

The [advanced Docker fallback](manual-build.md) is a separate builder and does not expose the desktop's two-file picker. Do not assume passing this Gradle argument to its bootstrap enables private-asset bundling.
