---
description: "Use when building, deploying, or debugging the C++ bridge binary. Covers WSL cross-compilation, SBC deployment via SCP, and iterative dev workflow. Use this skill whenever the user wants to build bridge code, deploy to the SBC, or troubleshoot bridge builds."
---
# Bridge Dev Workflow — WSL Cross-Compile + SBC Deploy

## Overview

The bridge binary (`openautolink-headless`) is an ARM64 Linux binary. For rapid iteration, we cross-compile on WSL (x86_64 → aarch64) and SCP the result to the SBC. This is much faster than building on the SBC itself (CM5 is slow, use `-j1`).

## One-Time Setup

Run in WSL to install the cross-compilation toolchain:

```bash
# From repo root in WSL:
bash scripts/setup-wsl-cross-compile.sh
```

This installs `aarch64-linux-gnu-g++`, ARM64 Boost/OpenSSL/protobuf/libusb libraries, and CMake.

## Build + Deploy (Single Command)

From PowerShell:

```powershell
# Build in WSL + deploy to SBC (all-in-one):
scripts\deploy-bridge.ps1

# Clean rebuild + deploy:
scripts\deploy-bridge.ps1 -Clean

# Deploy only (skip build, use last binary):
scripts\deploy-bridge.ps1 -SkipBuild

# Custom SBC address:
scripts\deploy-bridge.ps1 -SbcHost 10.0.0.1 -SbcUser pi
```

## Build Only (No Deploy)

From WSL:

```bash
bash scripts/build-bridge-wsl.sh        # incremental
bash scripts/build-bridge-wsl.sh clean   # full rebuild
```

Output: `build-bridge-arm64/openautolink-headless-stripped`

## Key Files

| File | Purpose |
|------|---------|
| `scripts/setup-wsl-cross-compile.sh` | One-time WSL toolchain setup |
| `scripts/build-bridge-wsl.sh` | WSL cross-compile script |
| `scripts/deploy-bridge.ps1` | Build + SCP + restart service |
| `build-bridge-arm64/` | Build output dir (gitignored) |

## How It Works

1. **WSL cross-compiles** using `aarch64-linux-gnu-g++` with a CMake toolchain file
2. Binary is built in `build-bridge-arm64/` (on the Windows filesystem, accessible from both WSL and PowerShell)
3. **SSH** stops the service
4. **PowerShell SCPs** the stripped binary directly to `/opt/openautolink/bin/` on the SBC
5. **SSH** restarts the service

## Troubleshooting

### ARM64 package conflicts
If `apt-get install libboost-*:arm64` fails with conflicts, you may need:
```bash
sudo apt-get install -f    # fix broken deps
```

### CMake can't find ARM64 libs
The toolchain file sets `CMAKE_FIND_ROOT_PATH` to `/usr/aarch64-linux-gnu` and `/usr`. If Boost or OpenSSL aren't found, check:
```bash
ls /usr/lib/aarch64-linux-gnu/libboost_system*
ls /usr/lib/aarch64-linux-gnu/libssl*
```

### SSH key not set up
If SCP prompts for a password every time:
```powershell
ssh-copy-id khadas@192.168.137.197
```

### SBC address changes
The SBC gets its SSH IP via DHCP from Windows ICS (192.168.137.x). Find it with:
```powershell
arp -a | Select-String "192.168.137"
```

### Protobuf version mismatch
aasdk's FetchContent downloads its own protobuf. If cross-compile fails on protobuf, the host `protoc` must match. Install the native (x86_64) protobuf-compiler alongside the ARM64 libs:
```bash
sudo apt-get install protobuf-compiler
```

## Build Architecture

```
Windows (PowerShell)
  │
  ├── scripts\deploy-bridge.ps1
  │     │
  │     ├── 1. wsl bash scripts/build-bridge-wsl.sh
  │     │        └── cmake cross-compile (x86_64 host → aarch64 target)
  │     │        └── output: build-bridge-arm64/openautolink-headless-stripped
  │     │
  │     ├── 2. ssh: stop service
  │     │
  │     ├── 3. scp binary → SBC:/opt/openautolink/bin/
  │     │
  │     └── 4. ssh: restart service
  │
  └── SBC running at 192.168.137.x (via Windows ICS)
```
