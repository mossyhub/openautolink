# Installing OpenAutoLink on a locked GM vehicle

GM production AAOS head units do not expose ADB and do not permit APK sideloading. The car app must arrive through Google Play.

## Build and publish through your own account

To use OpenAutoLink, build the app and publish it through your own Google Play Console developer account. Locked GM vehicles require Google Play delivery; APK sideloading is not supported.

CarPlay requires compatible accessory authentication files that you are authorized to use. These are not included in the public build; building the app alone does not enable CarPlay.

As I publish changes, you’ll need to pull the latest source, rebuild, and publish your own updates.

[Build guide](build-aab.md) · [CarPlay authentication](carplay-private-mfi-assets.md)

## Phone and permission setup for an installed app

These steps apply once you have installed your app through Google Play:

1. For wireless Android Auto only, install the matching companion APK from [GitHub Releases](https://github.com/mossyhub/openautolink/releases/latest) on the Android phone. USB Android Auto and USB CarPlay do not require the companion. See the [README's phone-specific setup](../README.md#choose-your-phone-and-connection), including leaving the iPhone unpaired from vehicle Bluetooth for USB CarPlay.
2. While parked, grant supported car permissions in the vehicle's system Settings, then **return to OpenAutoLink**. The process-owned VHAL source rechecks grants and adds newly authorized properties without restarting projection. Continue with [Wireless WPP setup](wireless-wpp.md) or the [README's USB setup](../README.md#android-auto-setup).

`CAR_ENERGY` and `CAR_SPEED` are dangerous permissions on inspected Android 14 / GM firmware; `CAR_INFO` is normal (normally install-granted), not a runtime dialog permission. Mileage, tires and dynamics can be privileged/signature-only and cannot be granted to a normal Play app. Firmware and HAL support vary; granting a permission does not guarantee the vehicle exposes its properties. OpenAutoLink does not repeatedly prompt for car permissions on resume.

Late-grant refresh is an implementation attempt pending vehicle validation. If EV values remain absent after returning, check Diagnostics → VHAL Property Status. A force-stop and reopen remains a fallback workaround until the surviving-process behavior is confirmed on the vehicle; it is not the expected refresh path.

## Independent route: self-publish

You operate your own Play Console app and testing track; Google's review and vehicle availability requirements still apply.

The supported build path is the [one-command personal AAB builder](build-aab.md). On Linux or Windows WSL with Docker available, run:

```bash
curl -fsSL https://raw.githubusercontent.com/mossyhub/openautolink/main/build-aab.sh | bash
```

The first run collects the package and signing identity, creates the key, clones the source, and produces a verified AAB. Later runs reuse those details, fetch current source, and build the next version. Back up `~/.openautolink-builder/secrets/` before uploading the first AAB.

Continue at [Create the Play Console app](#5-create-the-play-console-app) after the builder succeeds. The manual build steps below remain available for contributors who do not want to use the container.

## Manual build path

The remainder of this section describes the underlying manual process. After the one-time setup, updates use the same application ID, signing key, and Play testing track.

## Before you begin

You need:

- a Google Play Console developer account and completed account verification;
- a Windows, Linux, or macOS development computer;
- Git;
- Android Studio or an Android SDK/NDK installation;
- JDK 17 or 21;
- a unique Android application ID you control;
- an upload keystore that you will preserve and back up;
- the Google account used by the vehicle's Play Store added as a tester.

> [!WARNING]
> Keep the keystore, alias, and passwords backed up securely. A future build signed with a different key cannot update the installed app. Do not commit `secrets/`, keystores, passwords, or service credentials.

## 1. Clone and prepare the source

```bash
git clone https://github.com/mossyhub/openautolink.git
cd openautolink
```

Build the Android native dependencies once from the repository root:

```bash
scripts/build-openssl-android.sh
scripts/setup-ndk-deps.sh
scripts/build-aasdk-android.sh
```

On Windows, run the three `.sh` dependency builders from WSL. They create the prebuilt native libraries consumed by the Android CMake build. After that, build the Android project and signed AAB from Windows PowerShell or Android Studio.

## 2. Choose a unique application ID

The default application ID is `com.openautolink.app`. Your Play Console app needs an ID unique to your account, for example:

```text
com.example.openautolink
```

Set it in `app/build.gradle.kts`:

```kotlin
applicationId = findProperty("appId") as? String ?: "com.example.openautolink"
```

Do not change this ID after publishing. Android and Google Play treat a different application ID as a different app.

The Kotlin namespace can remain `com.openautolink.app`; it is not the Play identity.

## 3. Create and preserve an upload key

### Create the key on Windows

From PowerShell in the repository root:

```powershell
.\scripts\create-upload-keystore.ps1
.\scripts\save-signing-credentials.ps1
```

The key is written under the gitignored `secrets/` directory. Back it up outside the repository.

### Create the key on Linux or macOS

Create `secrets/upload-key.jks` with the JDK `keytool` command:

```bash
mkdir -p secrets
keytool -genkeypair \
  -keystore secrets/upload-key.jks \
  -alias upload \
  -keyalg RSA \
  -keysize 2048 \
  -validity 9125
```

Back up the resulting keystore. Pass its passwords through environment variables when building; do not write them into tracked files.

## 4. Build a signed AAB

### Build on Windows

```powershell
.\scripts\bundle-release.ps1
```

The script uses DPAPI-saved credentials when available and increments the per-clone version stored in `secrets/version.properties`.

### Build on Linux or macOS

```bash
export OAL_KEYSTORE_PASS='your-keystore-password'
export OAL_KEY_PASS='your-key-password'
scripts/linux/bundle-release.sh
```

Optional variables:

```bash
export OAL_KEY_ALIAS='upload'
export OAL_KEYSTORE_PATH='secrets/upload-key.jks'
```

The bundle is produced under:

```text
app/build/outputs/bundle/release/
```

See [`scripts/linux/README.md`](../scripts/linux/README.md) for the Linux environment and native dependency setup.

## 5. Create the Play Console app

1. Create a new app in Google Play Console.
2. Use the same unique application ID configured in the build.
3. Complete the required app-content, data-safety, and automotive declarations.
4. Create an **Internal testing** or **Closed testing** release.
5. Upload the signed release AAB.
6. Add the Google account used by the vehicle's Play Store to the tester list.
7. Roll out the release to that testing track.
8. Accept the testing invitation with the same Google account if Play presents one.

Google may require account or app review before the release becomes available. That delay is outside OpenAutoLink.

## 6. Install in the vehicle

1. Sign into the vehicle's Play Store with the tester account.
2. Open the testing listing or find the app after the test release propagates.
3. Install OpenAutoLink.
4. Open **Settings → Apps → OpenAutoLink → Permissions**.
5. Grant **Car information**, microphone, and any other requested permissions needed for the features you use.
6. Open OpenAutoLink and continue with [Wireless WPP setup](wireless-wpp.md) or USB.

## Testing a non-GM AAOS vehicle

Polestar and other AAOS owners whose vehicles do not include Android Auto can share platform questions and reports through XDA or Reddit. Do not assume the GM result transfers: Play delivery, app permissions, Bluetooth/Wi-Fi topology, audio policy, vehicle APIs, and display zoning differ by manufacturer.

When reaching out, include the make, model, model year, market, AAOS version, and whether the vehicle permits APK installation. The first goal is to establish a safe installation path and a sustained rendered session before claiming feature support.

## Updating later

For every update:

1. Pull the desired source revision.
2. Keep the same application ID.
3. Build with the same upload key and alias.
4. Use a higher version code.
5. Upload the new AAB to the same Play Console app and test track.
6. Update from the vehicle's Play Store.

The supplied bundle scripts maintain per-clone version values in the gitignored `secrets/version.properties` file.

## What the release APK is for

GitHub Releases include `openautolink-car-vX.Y.Z.apk` for emulators, development, and AAOS systems that permit sideloading. It is not an alternate installation path for a locked GM production vehicle.

## Common installation failures

### Play says the application ID already exists

Choose a genuinely unique application ID. Do not use `com.openautolink.app` for a new self-published Play app.

### A new build will not update the installed app

Confirm all three match the original publication:

- application ID;
- upload/app signing lineage;
- a version code greater than the installed release.

### The app installs but lacks vehicle data or cluster features

Installation proves only that Play accepted and delivered the package. Grant the car permissions, then check the exact vehicle in [Compatibility](compatibility.md). Vehicle APIs and display integration vary by AAOS platform.

### The app is absent from the vehicle's Play Store

Check tester membership, invitation acceptance, release rollout, review status, application ID, automotive declarations, and whether the vehicle account is the same tester account.
