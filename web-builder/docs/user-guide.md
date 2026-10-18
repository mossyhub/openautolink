# Create your personal OAL app in a browser

**Development preview:** the website and its template publication are not yet qualified for public use. The walkthrough describes the implemented browser flow. Do not use the maintainer's car/companion APK as a template or rename it to `.aab`.

This tool prepares an Android App Bundle for **your own Google Play Console app**, on its dedicated **Android Automotive OS Internal testing** track. It does not install directly on your car, create a Play account, accept declarations, or upload to Google for you.

## Before starting

Use a current desktop browser with JavaScript, secure-context WebCrypto, workers, browser storage and locking enabled. Windows/Mac and non-Chromium browser qualification remain pending. You do not install Java, Python, Android SDK or a desktop executable for this browser workflow.

Review the [Play registration and Automotive Internal Test guide](../../docs/install-gm.md) before paying for a developer account. Account/device verification requirements can matter to iPhone-only owners. A successful AAB build does not guarantee Play acceptance or car eligibility.

The browser downloads the latest supported public template and processes your identity and selected private files locally. Keep the tab open until you save your files. There is no account-based key or password recovery. A compromised browser, extension or website could still access inputs; use a computer you trust.

## First build

1. Open the website and check the latest release. If its personalization template is unavailable, stop and retry after publication. The tool will not substitute an older release.
2. Choose **Create a new personal app**. The tool generates your unique application ID and upload-signing key. This is separate from Google's Play app-signing key and from optional CarPlay credentials.
3. Enter and confirm a strong backup password. Save the password separately in your password manager.
4. Leave CarPlay empty for a credential-free build, or select your authorized matching pair as described below.
5. Choose **Create identity & backup**. Download the initial encrypted web backup and confirm you can find it. Put a copy somewhere durable—not only the temporary Downloads folder.
6. Choose **Build AAB**. Work runs locally in a worker. Cancelling or failing keeps the reserved version number; gaps are safe.
7. Download the **updated web backup**, then confirm you saved it and know its password. Only then download your AAB.
8. Follow the [dedicated Automotive Internal Test guide](../../docs/install-gm.md) to create your Play app, upload that AAB, roll out Internal testing, join with the car's Google account, and select your car when installing through Google Play.

Upload the `.aab`, not the backup JSON or a desktop executable. Keep the updated backup for future updates. The backup password is not sent to Google and is not an Android keystore password you need to enter in Play Console.

## Updates and moving computers

Choose **Update my existing app** and restore the latest encrypted backup made by this **web** tool. Enter its password and the highest version code ever uploaded to that Play app, across all tracks. Enter `0` only if you have never uploaded a bundle. This whole-number version code is not the OAL release name.

The next build uses the same application ID and upload key with a higher version. Save its updated backup before downloading/uploading the AAB to the **same** Play app. Supply your authorized CarPlay pair again for each update that needs it.

Browser reservations protect against reuse within this browser origin, not across computers. Old backups cannot know about later uploads. Never clear storage or create another identity to force an update past a mismatch; locate the correct backup and reconcile the Play version instead.

**Desktop-builder backups and JKS files are not compatible with the web backup format.** Existing desktop users should keep using their original desktop identity/work folder. No automatic migration is offered. The desktop builder implementation and release work remain preserved alongside the website.

## Optional CarPlay files

Select both or neither:

- An unencrypted DER PKCS8 P-256 private key.
- One matching certificate, encoded as DER X.509 or DER PKCS7 containing exactly one certificate.

Each file must be nonempty and at most 16 KiB. Confirm you are authorized to use and include the pair. The tool neither supplies these files nor verifies Apple authorization/iPhone acceptance. Format and key matching alone do not prove that CarPlay authentication will succeed.

These files are embedded in the AAB and are extractable from distributed bundles/apps. They are **not included in the encrypted identity backup**. Keep authorized originals separately. An Android Auto build does not need them.

## Recovery and errors

- **Missing latest template:** wait for a compatible public template; do not use a release APK instead.
- **Wrong password/damaged backup:** preserve the original file and locate the correct password/backup. The tool does not generate a replacement identity.
- **Incompatible application ID:** retain the original identity and use its original builder; no version is reserved by the web tool for an incompatible ID.
- **Storage/locking error:** use a supported browser and permit necessary storage. Do not erase an existing version record to bypass a conflict.
- **Build cancelled/failed:** keep your saved backup and retry. The next reservation can skip a version.
- **Tab closed/reloaded:** restore your saved web backup. Private inputs are not deliberately persisted by the site.

Do not attach signing keys, backup passwords, identity archives or CarPlay credentials to issues or support requests. Review screenshots for private information before sharing.
