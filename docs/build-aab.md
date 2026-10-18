# Create your personal OAL car app

Use **[oal.mossyg.com](https://oal.mossyg.com)** to create a signed Android App Bundle (`.aab`) for your own Google Play app. No source editing, desktop builder download or Android SDK setup is needed.

## Before you start

- Use a computer with internet access and a current **Chrome or Edge** browser on Windows, macOS or Linux. Browser testing has covered Chromium; Firefox and Safari are not yet qualified.
- Check the [Google Play registration requirements](install-gm.md#1-register-your-play-console-developer-account) first: **US$25 one-time fee**, identity verification, and **access to an Android device for new personal accounts**. An iPhone does not meet that device requirement.
- For CarPlay only, have a compatible `identity.pk8` and `certificate.p7b` pair that you are authorized to include. The builder does not supply these files. Android Auto does not need them. See [CarPlay authentication](carplay-private-mfi-assets.md).

## Create your AAB

1. Open **[oal.mossyg.com](https://oal.mossyg.com)** and wait for the latest release template to pass its checks. Select **Continue to identity**.
2. Choose **Create a new personal app** and keep its generated application ID. Already have a website-built app? Follow [Update the same app](#update-the-same-app) instead.
3. Set a strong backup password and keep it separately in a password manager. Add both authorized CarPlay files if needed, or leave both empty for Android Auto only.
4. Select **Create identity & backup**. Download the encrypted web backup, save a durable private copy and confirm you can find it. Do not rely on the browser tab to preserve your identity.
5. Select **Build AAB** and keep the tab open. When it finishes, download the **updated web backup** and confirm you saved it before downloading the `.aab`. The backup preserves your application ID, upload key and version state for updates.
6. Follow [Install through your own Internal Test](install-gm.md) to upload the AAB to your app's **dedicated Android Automotive OS Internal Test** and install it on the car.

The website creates your bundle; it does not register a Google account, upload to Play or install on the car.

## Update the same app

1. Open the website, choose **Update my existing app**, and restore your latest **web identity backup**. Enter the highest version code ever uploaded to this Play app across all tracks (`0` only if you have never uploaded a bundle).
2. Keep the **same application ID and upload key**. Add your authorized CarPlay pair again if needed, then build from the latest supported template.
3. Confirm the new version code is higher than every code already uploaded to this Play app. An older backup cannot know about later uploads.
4. Download the updated encrypted backup and AAB. Upload the AAB to the **same Play app's dedicated Android Automotive OS Internal Test**, then roll it out as described in [Updating later](install-gm.md#updating-later).

<details>
<summary>Privacy, existing apps and validation limits</summary>

- Signing and optional CarPlay inputs are processed locally in the browser. Keep backups and passwords private; never attach them to issues.
- A web identity backup does not include your CarPlay files. Keep authorized originals separately. Files bundled in an AAB can be extracted from the distributed app.
- Desktop backups and JKS files are not compatible with the web backup format. For an app created by another tool, keep its original builder and accepted package ID, upload key and version history; a new web identity is not a migration. See [existing-app guidance](manual-build.md#existing-apps).
- The website checks the latest stable release and downloads the matching public template mirrored on the site. It stops if that release lacks a compatible template rather than silently using an older one.
- The published **v0.1.531** template passed first-build/update tests on Windows, Linux, Intel Mac and Apple Silicon Mac; the website mirrors the exact public template bytes. These artifact checks and Chromium website tests are **not proof of Google Play acceptance or installation on your car**.

</details>

[Desktop builder preview](desktop-builder.md) · [Advanced source-build guide](manual-build.md)
