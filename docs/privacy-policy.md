# Privacy Policy — OpenAutoLink

**Last updated:** October 21, 2026

## Overview

OpenAutoLink consists of an Android Automotive OS car app and an Android phone companion. The apps support Android Auto and Apple CarPlay projection sessions across the local connection between the phone and vehicle. OpenAutoLink has no advertising or analytics SDK and does not sell user data.

Most operation is local. Two optional features can contact an external server: manual EV-profile refresh and invited diagnostic-log upload. Both are described below.

## Local projection data

While a projection session is active, OpenAutoLink can process:

- Android Auto or CarPlay video, audio, and control messages;
- microphone audio used for voice commands;
- touch and steering-wheel input;
- navigation and media metadata;
- Bluetooth and Wi-Fi connection information;
- vehicle properties made available by AAOS, such as speed, gear, energy, range, charge state, tire data, and exterior conditions;
- head-unit location and motion sensors used by Android Auto.

This data is used to operate the projection session and related vehicle integrations. It travels between the phone companion and car app over the local vehicle/phone network. OpenAutoLink does not send live projection media or vehicle telemetry to the developer.

## Permissions and their purpose

### Microphone

The car app uses `RECORD_AUDIO` for supported Android Auto voice features and CarPlay microphone transport to the connected phone. No microphone audio is saved as a recording or uploaded to the developer. Normal projection can retain bounded numeric capture/playback and retirement observations; opt-in parked diagnostics retain numeric summaries, not audio. Live experiments transmit microphone audio only through the normal CarPlay session to the connected iPhone. These observations use the existing manual diagnostic-log upload path, never automatic audio upload. On the validated GM call topology, native Bluetooth hands-free service carries phone-call voice audio.

### Location and Wi-Fi

The apps use location and Wi-Fi permissions for network discovery, SSID visibility, Wi-Fi scanning, connection management, and available vehicle/head-unit location data. Android requires location permission for several Wi-Fi APIs even when the app is finding a nearby local network.

### Bluetooth

Bluetooth connect, scan, and advertise permissions support companion auto-start, phone/vehicle identity, and the factory-style Wireless WPP bootstrap used by current Android Auto releases.

### Vehicle data

AAOS car permissions expose only properties allowed by the vehicle platform. OpenAutoLink uses available values for Android Auto sensors, energy estimates, cluster integration, diagnostics, and settings. Restricted vehicle properties remain inaccessible to an ordinary Play-installed app.

### Foreground service, notifications, wake lock, and boot

These permissions keep an active projection/connection service alive, show its state, and allow the companion's configured auto-start behavior.

### Storage and logging

When file logging is enabled, the apps write diagnostic logs under their app-controlled external storage area or removable media selected by the car app. Logs can contain connection state, device/platform information, app settings relevant to diagnosis, protocol events, and vehicle values used by the session. They are not uploaded unless the optional upload feature is enabled, configured, and invoked.

## Data stored locally

The apps store configuration needed for operation, including:

- transport and display preferences;
- known-phone identity and friendly labels;
- selected Bluetooth auto-start devices;
- optional legacy Wi-Fi entries and their passwords;
- WPP access-point details entered in the car app;
- EV-model settings and learned-rate state;
- logging preferences and locally generated log files;
- optional invited-upload configuration.

Wi-Fi passwords and invitation credentials are stored in app-private storage. Do not include them in public bug reports.

## Optional external network features

### EV profile refresh

EV profiles are bundled with the car APK and work offline. If the user enables and manually invokes profile refresh, the car app downloads an updated profile JSON from the configured HTTPS source, validates it, and caches it locally.

No background profile download is required for projection.

### Diagnostic-log upload

Diagnostic upload is off by default and requires a configured HTTPS endpoint and invitation token. When the user invokes it, the selected app packages recent diagnostic log files and uploads that archive to the configured maintainer endpoint.

The upload can contain whatever appears in those diagnostic files. Review logs before sharing when possible. The upload implementation rejects non-HTTPS endpoints and does not follow redirects with the invitation token.

Invited-user logs are stored under an owner-specific namespace and are kept outside the public automated issue-triage path. Retention and access are controlled by the maintainer of the configured endpoint.

## Third-party services

OpenAutoLink does not include third-party advertising, analytics, or crash-reporting SDKs.

The user may independently interact with:

- Google Play for installation and updates;
- Android Auto and phone applications selected by the user;
- GitHub when downloading an official phone APK or source code;
- the configured EV-profile source when refreshing profiles;
- the configured diagnostic endpoint when uploading logs.

Those services operate under their own privacy policies.

## Data security

- Live projection communication is intended for the local phone/vehicle network.
- External profile refresh and log upload require HTTPS.
- Diagnostic upload credentials are independently revocable and are not meant for public sharing.
- The car app disables Android backup; companion backup rules exclude sensitive upload credentials and operational preferences where required by the current migration policy.

No system can guarantee absolute security. Keep the phone, vehicle account, Play Console account, signing key, access-point credentials, and invitation token protected.

## Children's privacy

OpenAutoLink is not directed at children and does not knowingly collect children's personal information.

## Changes

Material changes to this policy will be published in this repository with an updated date.

## Contact

For privacy questions, [open a GitHub issue](https://github.com/mossyhub/openautolink/issues).
