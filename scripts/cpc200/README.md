# CPC200 Bridge — Build & Deploy

The CPC200 (rooted "AutoKit" / "Magic-Car-Link-1.00") acts as our BT iAP2 +
MFi signing bridge for CarPlay. This directory hosts everything we deploy
to it: the bridge binary (forthcoming), the runtime config, and supporting
tools like a cross-compiled `wpa_supplicant` for putting the CPC200 into
WiFi-client mode so it joins the car/home hotspot instead of running its
own AP.

## Target

- Hardware: i.MX6 UltraLite, ARMv7 Cortex-A7, hard-float capable
- Kernel: Linux 3.14.52 (yes, ancient)
- libc: glibc 2.20
- Dynamic linker path: `/lib/ld-linux.so.3` (not the `-armhf` path)
- WiFi driver: NXP/Marvell `moal`/`mlan` with cfg80211/nl80211

**Build target**: `arm-linux-gnueabihf` (armhf) **statically linked**.
Static linking avoids the dynamic-linker-path mismatch and works as long as
modern glibc's syscalls don't require kernel features missing from 3.14 —
the existing `mfi_auth_daemon` is statically linked with this toolchain and
runs fine, so this should hold.

## What's here

```
scripts/cpc200/
├── README.md                          # this file
├── build-wpa.sh                       # WSL cross-compile recipe (do not run blind)
├── config/
│   ├── wpa_supplicant.build.config    # build-time .config for wpa_supplicant
│   ├── wpa_supplicant.conf.template   # runtime config (fill in WiFi creds)
│   └── oal-bridge.conf.template       # bridge runtime config (WiFi info + targets)
├── deploy/
│   ├── install-wpa.sh                 # CPC200-side: switch hostapd → wpa_supplicant
│   └── start-bridge.sh                # CPC200-side: run bridge with config
└── bin/
    └── .gitkeep                       # compiled binaries land here
```

## Cross-compile prerequisites (WSL)

```bash
sudo apt install -y arm-linux-gnueabihf-gcc-13 make wget pkg-config
```

## Workflow (when ready)

1. `bash scripts/cpc200/build-wpa.sh` — downloads sources, builds in WSL,
   places binaries in `scripts/cpc200/bin/`
2. Commit binaries to repo (they're small — ~300 KB each)
3. SCP everything to CPC200, run `deploy/install-wpa.sh` to switch modes

## Status

- **Not built yet.** Cross-compile recipe is ready to run, but we are
  staying in CPC200 AP mode for early development.
