# Compatibility and evidence

OpenAutoLink compatibility depends on the head unit, AAOS build, app version, Android Auto version, phone, setup, permissions, networking, audio policy, physical display zoning, and installation path. GM's recent AAOS EVs share substantial infotainment architecture, so an isolated problem on one model is not presumed to be vehicle-specific without comparative evidence.

This project changes quickly. Community reports below are dated observations of the software and setup tested at that time. They are preserved because they are useful evidence, but an old failure does not establish that the same car fails with the current release—and a successful stream does not prove every feature.

## Evidence levels

| Level | Meaning |
|---|---|
| **Maintainer validated** | Repeated real-vehicle use with direct logs and visual confirmation |
| **Owner demonstrated working** | Independent owner supplied a visible successful result |
| **Owner reported working** | Independent owner described a successful install/session, but maintainer reproduction is unavailable |
| **Issue reported** | A user recorded a failure on the tested app/phone/setup; this is not a vehicle incompatibility verdict |
| **Experimental** | Architecture appears possible, but there is no successful real-vehicle evidence |
| **Not yet reported** | No credible real-vehicle result has been received yet |

A build, Play installation, `CONNECTED` status, or process log is not proof of successful projection. A positive result requires a rendered frame and continued video/input behavior; feature-specific support requires observing that feature.

## Vehicle matrix

| Vehicle | Platform information | Status | Evidence and limits |
|---|---|---|---|
| **2024 Chevrolet Blazer EV** | GM AAOS 12L; maintainer vehicle | **Maintainer validated** | Wireless WPP, sustained video, touch, audio, calls, EV data, cluster integration, ignition reconnect, USB, and H.265 startup have been exercised on real hardware. The maintainer has also encountered many defects while continuously changing and testing the software; those failures are not evidence that the Blazer itself is incompatible. |
| **2025 Chevrolet Equinox EV** | GM AAOS 14 reported by testers | **Owner reported working** | An owner [reported installation and a working session](https://xdaforums.com/t/open-source-openautolink-wireless-android-auto-bridge-for-aaos-gm-evs.4785192/post-90708465). An initial audio stop cleared after reconnecting. This is positive evidence for the shared GM platform, not a claim that every feature was audited. |
| **2026 Chevrolet Equinox EV** | GM AAOS 14 reported by testers | **Community testing** | Users earlier reported a [startup/logo stall](https://xdaforums.com/t/open-source-openautolink-wireless-android-auto-bridge-for-aaos-gm-evs.4785192/post-90620330) and [USB freezes](https://xdaforums.com/t/open-source-openautolink-wireless-android-auto-bridge-for-aaos-gm-evs.4785192/post-90686188). These are retained as software/setup issue reports; there is not enough comparative evidence to attribute them to the 2026 Equinox, and newer OpenAutoLink releases may behave differently. |
| **2027 Chevrolet Bolt** | GM AAOS; exact build not captured in the public report | **Owner demonstrated working** | The owner posted [a running OpenAutoLink video](https://www.youtube.com/watch?v=xEYOxJroplQ) and a [first-hand Bolt report](https://www.chevybolt.org/threads/openautolink-the-cheapest-way-to-get-andriod-auto-on-the-27-bolt.63027/). They report a Samsung S24 Ultra with Android Auto 17.3 works well, with occasional manual reconnects and some slowness; broader feature coverage has not been audited. |
| **Other GM AAOS EVs** | AAOS version, SoC, radio, and permissions can vary | **Testing encouraged** | Shared GM architecture makes broad compatibility plausible. Exact behavior still needs owner reports, but lack of a report is not evidence of incompatibility. |
| **Non-GM AAOS vehicles** | Vendor-specific | **Experimental** | The app may install, but Play policy, sideloading, Bluetooth, Wi-Fi topology, VHAL permissions, cluster APIs, and display zones differ. |

## Polestar and other AAOS platforms

Platform questions and owner reports are welcome through the [OpenAutoLink XDA thread](https://xdaforums.com/t/open-source-openautolink-wireless-android-auto-bridge-for-aaos-gm-evs.4785192/) or [Reddit](https://www.reddit.com/user/IPickedThisUserID/).

To use OpenAutoLink, build the app and publish it through your own Google Play Console developer account. Locked GM vehicles require Google Play delivery; APK sideloading is not supported.

CarPlay requires compatible accessory authentication files that you are authorized to use. These are not included in the public build; building the app alone does not enable CarPlay.

As I publish changes, you’ll need to pull the latest source, rebuild, and publish your own updates.

Follow the [build guide](build-aab.md), [Play installation guide](install-gm.md), and [CarPlay authentication policy](carplay-private-mfi-assets.md). Building or publishing does not establish compatibility with an unverified vehicle.

## Phone compatibility

The phone requires:

- Android with Android Auto;
- Bluetooth and Wi-Fi;
- the OpenAutoLink Companion for wireless operation;
- permission to run the companion in the background;
- a usable route to the vehicle access point.

Android Auto 17.4 and newer requires [Wireless WPP](wireless-wpp.md). Phone vendor Wi-Fi and battery-management policies can change connection behavior even when the vehicle is unchanged.

## Feature matrix

A vehicle-level successful stream does not automatically prove every integration:

| Feature | What proves it |
|---|---|
| Video | First frame visibly renders and frames continue |
| Touch | Screen input causes the intended phone-side Android Auto action |
| Audio | Media and guidance are current, audible, and do not accumulate delay |
| Calls | Projected answer/end controls work and both directions of voice audio are confirmed |
| EV data | Android Auto/Maps displays live vehicle battery or energy-model results |
| Cluster navigation | Visible turn guidance updates in the instrument cluster |
| Steering-wheel controls | Mapped button produces the correct current-session action |
| Reconnect | A new ignition/sleep-wake cycle resumes a sustained session without manual repair |
| USB | AOA negotiation completes and a sustained session renders after permission is granted |

## Reporting a new vehicle

Include:

- make, model, model year, market, and trim;
- AAOS version and available radio/head-unit information;
- Android Auto version;
- phone make, model, and Android version;
- OpenAutoLink car and companion versions;
- selected transport;
- whether a frame rendered and for how long;
- touch, audio, microphone/call, cluster, EV-data, and reconnect results separately;
- car and companion logs from the same attempt when possible.

Do not post Wi-Fi passwords, invitation tokens, Play credentials, or signing material.

## Current support language

Use these distinctions in public discussion:

- **Validated:** 2024 Blazer EV.
- **Demonstrated working:** 2027 Bolt.
- **Owner reported working:** 2025 Equinox EV.
- **Community testing with earlier issue reports:** 2026 Equinox EV. The reports are not proven model-specific and may be stale after later fixes.
- **Testing encouraged:** other GM AAOS EVs; the shared platform is promising, but evidence is still being collected.
- **Experimental:** non-GM AAOS vehicles until tested on their different platform integrations.

Do not turn one user's failure into a model blacklist. Record the app version, Android Auto version, phone, transport, setup, and date; then ask whether the same failure reproduces on a current build or another GM vehicle.
