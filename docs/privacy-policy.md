# Privacy Policy — OpenAutoLink

**Last updated:** April 2, 2026

## Overview

OpenAutoLink is an Android Automotive OS (AAOS) app that wirelessly projects Android Auto from your phone onto your car's infotainment screen via a bridge device on your local network. This privacy policy explains what data the app accesses, how it is used, and how it is protected.

## Data Collection

**OpenAutoLink does not collect, store, transmit, or share any personal data with the developer, any third party, or any remote server.** The app operates entirely on your local network between your car's head unit and your bridge device. No data leaves your vehicle's local network.

## Permissions and Their Purpose

### Microphone (`RECORD_AUDIO`)
The app captures audio from the car's built-in microphone to enable hands-free voice commands (e.g., "Hey Google") and phone calls through Android Auto. Microphone audio is sent only to the bridge device on your local network, which forwards it to your phone's Android Auto session. **Audio is never recorded, stored, or transmitted outside your local network.**

### Location (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`)
The app reads GPS location and NMEA satellite data from the car's built-in GNSS receiver. This data is forwarded to your phone via the bridge device to improve Android Auto navigation accuracy (e.g., in tunnels or urban canyons where the phone's GPS signal is weak). **Location data is never stored or transmitted outside your local network.**

### Network (`INTERNET`, `ACCESS_NETWORK_STATE`)
The app connects to the bridge device over your car's local Ethernet or Wi-Fi network using TCP. These permissions are required for the three TCP connections that carry video, audio, and control data between the app and the bridge. **No internet connections are made to external servers**, except optionally checking for app updates from GitHub (if self-update is enabled by the user).

### Install Packages (`REQUEST_INSTALL_PACKAGES`)
Used only for the optional self-update feature. When enabled, the app can download and install updated versions of itself from GitHub Releases. This feature is off by default and must be manually enabled in Settings.

### Vehicle Data (`CAR_SPEED`, `CAR_ENERGY`, `CAR_POWERTRAIN`, `CAR_EXTERIOR_ENVIRONMENT`, `CAR_INFO`)
The app reads vehicle sensor data (speed, gear, battery level, temperature, etc.) from the car's Vehicle HAL to forward to your phone's Android Auto session. This enables the phone to display accurate vehicle information. **Vehicle data is sent only to the bridge device on your local network and is never stored or transmitted externally.**

### Foreground Service (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`)
Required to keep audio playback and the projection session active while the app is in use. The app does not run in the background when not actively projecting.

### Car App Permissions (`NAVIGATION_TEMPLATES`, `ACCESS_SURFACE`)
Required by the Android Automotive Car App Library to render navigation information on the instrument cluster and access the display surface for video rendering.

## Sensor Data

The app reads accelerometer, gyroscope, and magnetic field sensors from the car's head unit hardware to provide inertial measurement data to Android Auto. This improves navigation accuracy during GPS signal loss (tunnels, parking garages). This sensor data is sent only to the bridge device on your local network.

## Data Storage

The app stores only user preferences locally on the device using Android DataStore:
- Bridge connection settings (IP address, port)
- Video/audio configuration (codec, resolution, FPS)
- Display preferences
- Self-update settings

No personal data, location history, audio recordings, or vehicle data is stored.

## Third-Party Services

OpenAutoLink does not integrate with any third-party analytics, advertising, crash reporting, or tracking services.

The optional self-update feature checks `github.com` for new releases. No personal data is sent in this request.

## Data Security

All communication between the app and the bridge device occurs over your car's local network. The app does not expose any network services or accept incoming connections from external networks.

## Children's Privacy

OpenAutoLink does not collect any data from any users, including children.

## Changes to This Policy

If this privacy policy is updated, the new version will be published in the app's GitHub repository and the "Last updated" date will be revised.

## Contact

For questions about this privacy policy, open an issue on the project's GitHub repository:
https://github.com/mossyhub/openautolink
