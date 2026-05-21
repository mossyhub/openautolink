# oal-cp-broker — CPC200 Wireless CarPlay Broker

C binary that runs on the rooted CPC200 dongle and replaces Carlinkit's
`AppleCarPlay` + `ARMiPhoneIAP2` daemons. Its job:

1. Advertise the CarPlay BT SDP profile on the CPC200's BT controller.
2. Accept the iPhone's RFCOMM connection.
3. Run the iAP2 link-layer handshake.
4. Relay MFi auth challenges to the local `mfi_auth_daemon` (TCP :5290 on loopback).
5. Inject the **AAOS app's IP** into the `StartCarPlay` response so the iPhone
   sends its CarPlay session over WiFi to *our* app, not Carlinkit's services.

The CPC200 must first be in **lean AP mode** (see
`scripts/cpc200/Enter-LeanMode.ps1`) so the stock CarPlay daemons aren't
fighting us for BT and RFCOMM channel 8.

## Status

**Scaffold only.** This PR (2026-05-20) lays down the build system,
version stamping, and a no-op main loop so we can prove the build pipeline.
The actual BT + iAP2 + MFi-relay logic lands in subsequent PRs.

## Build (WSL)

Prereqs: `scripts/setup-wsl-cross-compile.sh` already installs
`arm-linux-gnueabihf-gcc 13.x`. The same toolchain that builds
`scripts/cpc200/bin/wpa_supplicant`.

```bash
cd bridge/cpc200/oal-cp-broker
./build.sh                  # produces ./bin/oal-cp-broker (static armhf)
```

Output is **statically linked** so we don't fight CPC200's old glibc 2.20
or its missing libbluetooth. Expected size ~200KB.

## Deploy

```powershell
# from repo root, with CPC200 reachable
scp -i $env:USERPROFILE\.ssh\id_rsa_cpc200 `
    bridge\cpc200\oal-cp-broker\bin\oal-cp-broker `
    root@192.168.43.1:/tmp/oal-cp-broker
ssh -i $env:USERPROFILE\.ssh\id_rsa_cpc200 root@192.168.43.1 `
    "chmod +x /tmp/oal-cp-broker && /tmp/oal-cp-broker --probe"
```

`--probe` just prints version and exits — used for the build smoke test.

## Layout

```
oal-cp-broker/
├── README.md           (this file)
├── build.sh            WSL cross-compile recipe
├── CMakeLists.txt      build definition
├── include/
│   ├── oal_log.h       version-stamped logging (mirrors bridge/openautolink/headless)
│   └── oal_version.h   reads OAL_VERSION from secrets/version.properties at build time
└── src/
    └── main.c          entry point, arg parsing, lifecycle skeleton
```

## Pitfalls (from CPC200 reverse-engineering)

- **RFCOMM channel 8 is the CarPlay channel.** BlueZ's SAP plugin will steal
  it if enabled. CPC200's `bluetoothDaemon` was Carlinkit's custom BT stack;
  we'll use raw BlueZ APIs from libbluetooth (statically linked).
- **MFi chip is on `/dev/i2c-1:0x11`**, not 0x10. The daemon is already on
  `127.0.0.1:5290` (started by `scripts/mfi_auth_daemon` previously).
- **`/tmp` is tmpfs** — reboot wipes the binary. Deploy script + persistent
  startup hook will be added once the binary actually works.
- **All log output must be version-stamped** (`[broker X.Y.Z] message`) per
  the repo logging convention.
