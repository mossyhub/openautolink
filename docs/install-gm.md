# Install OAL on your car through Internal Test

On a locked GM Android Automotive OS head unit, use **your own Google Play Console app and its dedicated Android Automotive OS Internal Test**. The GitHub car APK cannot be sideloaded onto these head units. Complete setup while parked.

## Before you begin

- [Create your signed AAB on the website](build-aab.md) and save its **encrypted web identity backup**, application ID and version code.
- Have a computer or phone browser for Play Console and the tester link.
- Check the **exact Google account email in the car's Play Store**. Use that same account for tester enrollment and web installation; the developer-account owner can be different.
- Give **the car itself working internet**, such as home Wi-Fi or a phone hotspot. A local car hotspot without upstream internet is not enough. [An OnStar data plan is not required](../README.md#car-internet-no-onstar-data-plan-required).

## 1. Register your Play Console developer account

Already have a verified account? Sign in and complete any outstanding account requirements.

Otherwise, open **[Play Console registration](https://play.google.com/apps/publish/signup)**:

1. Use the Google account that will own your app. You must be **at least 18**; choose the account type that truthfully fits you, normally **Personal** for an individual.[1]
2. Review the agreement and Google's **US$25 one-time registration fee**. Supply real identity/contact details and complete the verification tasks Google requests.[1]
3. **New personal accounts need access to an Android device** for verification through Google's Play Console mobile app. **iPhone owners: arrange a qualifying Android device and check Google's instructions before paying.** An iPhone does not qualify; do not assume the car's head unit can perform this verification.[1]
4. Continue when Console lets your account create and manage the Internal Test. The OAL builder cannot perform or bypass Google verification.

## 2. Create your free Play Console app

1. Select **Home → Create app**.[6]
2. Choose a language and app name, such as **OpenAutoLink Personal**. Select **App** and **Free**.[6]
3. Supply a contact email if requested. Read and answer the declarations truthfully, then select **Create app**.[6]
4. If asked for a package name/application ID, copy the exact ID from your builder. Otherwise, Console establishes it from the first AAB. Check that both match: the package name is fixed after upload.[4]

![Google Play Console create-app form](screenshots/play-create-app.png)

<details>
<summary>Which setup tasks are required?</summary>

Internal testing can begin before all app setup is complete; internal-test-only apps are exempt from the Data safety section. Complete tasks that Console actually requires, not a full production launch checklist.[4] Review your own build and the [OAL privacy policy](privacy-policy.md) before answering declarations. Supply genuine app captures if requested.

</details>

## 3. Select dedicated Android Automotive OS Internal testing

1. Open **Test and release → Advanced settings → Form factors**.[2]
2. Select **+ Add form factor → Android Automotive OS** if it is not already listed, then **Manage** beside it.[2]
3. Under **Releases**, select **Use a dedicated release track for Android Automotive OS**, then **Save**. This choice cannot later be changed.[2]
4. Open **Test and release → Testing → Internal testing** and choose **Android Automotive OS** (sometimes labeled **Android Automotive OS only**) in the form-factor selector.[2][4]

**Confirm both Android Automotive OS and Internal testing before uploading.** Do not select mobile or Android Auto. The separate car-quality review/distribution opt-in is not required for this Internal Test.[2][3]

![Advanced settings: Android Automotive OS form factor](screenshots/play-form-factors.png)

![Use a dedicated release track for Android Automotive OS](screenshots/play-aaos-dedicated.png)

## 4. Upload and roll out your AAB

![Internal testing with Automotive OS only selected](screenshots/play-internal-test.png)

1. On **Android Automotive OS → Internal testing**, select **Create new release**.[5]
2. For the first release, review **Play App Signing** and use Google's generated app-signing key option for your new app.[5][7]
3. Under **App bundles → Upload**, select your finished **signed `.aab`**, not the unsigned template, an APK or your backup. Wait for processing and check its application ID and version code.[5]
4. Enter a release name and accurate release notes. Save, continue to review, and resolve blocking errors.[5]
5. Confirm the destination is **Android Automotive OS → Internal testing**, then select **Start rollout to internal testing** (or the equivalent confirmation). Check the **Releases** tab: the release must be available to testers, not just saved as a draft.[5]

<details>
<summary>Upload key versus app-signing key</summary>

Your builder identity contains the **upload key** used to sign the AAB. Google manages the **app-signing key** used for APKs delivered to the car. Neither is the optional CarPlay authentication pair. Preserve the accepted upload identity and its backup for updates.[7]

</details>

## 5. Add and select your tester list

On the **dedicated Android Automotive OS Internal testing** track:

1. Open **Testers → Create email list**. Name it, add the exact Google account email from the car's Play Store, and save/create the list.[4]
2. Back in the tester table, **select the checkbox for that list**. Creating it alone does not enable it.[4]
3. Add a feedback email or URL if requested, then **Save changes**. Reopen the tab and confirm the list is selected and contains the car account.[4]

## 6. Join the test with the car's Google account

1. In **Testers → How testers join your test → Join on web**, copy the **complete opt-in URL** supplied by Console.[4]
2. Open it on your computer or phone. Check that the browser is signed in to the **same Google account as the car's Play Store and tester list**.
3. Select **Become a tester** and confirm the page says you are a tester. Adding the email in Console was not enough.[4]
4. Follow the page's **download it on Google Play** link, keeping that same account selected. Save the opt-in link privately; do not guess it or rely on Play Store search.[4]

![Join on the web: copy your own app's tester opt-in link](screenshots/play-join-link.png)

## 7. Install on the car from Google Play's website

1. Turn on the parked car's infotainment system, connect **the car** to internet, and confirm its Play Store uses the tester account.
2. On the Google Play web page reached in step 6, select **Install** or **Install on more devices**.[8]
3. In the device picker, select **your car**, not your phone. If it is missing or incompatible, use [Troubleshooting](#troubleshooting) below.[8]
4. Confirm installation and keep the car online. Wait until **OpenAutoLink appears in the car's app launcher**, then open it. A web confirmation or queued request is not proof it installed.

## 8. Finish permissions and phone setup

1. While parked, open **Settings → Apps → OpenAutoLink → Permissions** on the car. Grant supported microphone and car-data permissions needed for your features, then return to OAL.
2. Follow the setup for your phone: [wireless Android Auto with the companion](wireless-wpp.md), [USB Android Auto](../README.md#android-auto-setup), or [CarPlay](../README.md#carplay-setup). USB needs no companion; CarPlay requires your authorized authentication pair in the build.

<details>
<summary>Missing vehicle data or permissions</summary>

Available permissions and vehicle properties vary by firmware. `CAR_ENERGY` and `CAR_SPEED` are runtime permissions on inspected Android 14 / GM firmware; `CAR_INFO` is normally install-granted. Privileged/signature-only permissions cannot be granted to an ordinary Play app.

OAL rechecks permissions when you return, but surviving-process refresh still needs vehicle validation. If values remain absent, inspect **Diagnostics → VHAL Property Status**; force-stop/reopen is a fallback. Installation does not establish support for every vehicle-data or cluster feature. See [Compatibility](compatibility.md).

</details>

## Updating later

1. [Build an update with your latest web identity backup](build-aab.md#update-the-same-app). Keep the **same application ID and upload key**, use a version code higher than all previous uploads, and save the updated backup.
2. In the **same Play Console app → dedicated Android Automotive OS → Internal testing**, upload and roll out the new AAB as in step 4.[5]
3. Keep the car online under the tester account. Select **Update** in its Play Store or allow automatic updates, then check the installed OAL version.

Keep the same Play app and tester membership; creating an AAB does not itself update the car.

## Troubleshooting

<details>
<summary>Cannot join, install or receive an update</summary>

- **Cannot join:** check the Google account in the invitation, web listing and car. Confirm the tester email is in a selected, saved list on the Automotive Internal Test, then use its exact **Join on web** link.[4]
- **No installable release:** confirm the signed car AAB is on the dedicated Automotive Internal Test and rollout completed. Read Console's actual errors/status; a draft is not a rollout.[5]
- **Car missing/incompatible:** open its Play Store while online under the tester account. Check the Automotive selector and the bundle's supported devices in Console. An Install button or selectable phone does not establish car eligibility. Do not change the package ID or key to fix compatibility.
- **Waiting for changes:** test links and updates can take several hours; new apps can temporarily show a placeholder name. Keep the car online and recheck Console's processing status. A sleeping/offline car can leave remote installation queued; delivery time is not guaranteed.[4]
- **Signing/version rejection:** restore the correct identity and use a higher version code. For a lost upload key, follow Google's upload-key recovery process rather than generating a random replacement.[7]

</details>

<details>
<summary>About the screenshots and sources</summary>

Screenshots are genuine captures of the creation form and an existing OAL app's Console pages, not mockups. They demonstrate navigation, **not proof of this website-built app's Play acceptance or car installation**. Use your own app identity and tester link; labels and processing states may vary. No tester emails are shown.

### Sources

[1] https://support.google.com/googleplay/android-developer/answer/6112435
[2] https://support.google.com/googleplay/android-developer/answer/13295490
[3] https://developer.android.com/training/cars/distribute
[4] https://support.google.com/googleplay/android-developer/answer/9845334
[5] https://support.google.com/googleplay/android-developer/answer/9859348
[6] https://support.google.com/googleplay/android-developer/answer/9859152
[7] https://support.google.com/googleplay/android-developer/answer/9842756
[8] https://support.google.com/googleplay/answer/14274288?hl=en — Install apps on other devices

</details>
