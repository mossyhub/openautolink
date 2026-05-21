#!/usr/bin/env bash
# build.sh — cross-compile oal-cp-broker for the CPC200 (ARMv7 hard-float).
#
# Run from WSL (Ubuntu 24.04). Produces a fully-static binary in bin/.
# No dynamic deps: we link libc/libgcc statically and implement our own
# mDNS responder (raw UDP), so the CPC200 firmware's stripped libdns_sd
# and glibc-2.18 version mismatch are non-issues.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

REPO_ROOT="$(cd ../../.. && pwd)"
VERSION_FILE="$REPO_ROOT/secrets/version.properties"
if [[ -f "$VERSION_FILE" ]]; then
    OAL_VERSION="$(grep '^versionName=' "$VERSION_FILE" | cut -d= -f2 | tr -d '[:space:]')"
else
    OAL_VERSION="0.0.0-novfile"
fi
echo "[build] version = $OAL_VERSION"

CC="${CC:-arm-linux-gnueabihf-gcc}"
if ! command -v "$CC" >/dev/null 2>&1; then
    echo "[build] ERROR: $CC not found. Run scripts/setup-wsl-cross-compile.sh first." >&2
    exit 1
fi
echo "[build] CC = $($CC --version | head -1)"

mkdir -p bin

SRC=(src/main.c src/mdns_publish.c src/relay.c src/udp_discover.c)
CFLAGS=(
    -std=c11
    -Wall -Wextra -Wno-unused-parameter
    -O2 -g
    -Iinclude
    -D_DEFAULT_SOURCE
    # CPC200 glibc is 2.18 (no 64-bit time_t). Force the legacy 32-bit ABI
    # so the toolchain doesn't pull in newer __*_time64 variants.
    -U_FILE_OFFSET_BITS -U_TIME_BITS
    -D_FILE_OFFSET_BITS=32 -D_TIME_BITS=32
    -DOAL_VERSION="\"$OAL_VERSION\""
    -march=armv7-a -mfpu=vfpv4 -mfloat-abi=hard
)
LDFLAGS=(
    -static
    # pthread for the concurrent mDNS + relay threads. Use whole-archive
    # so the static linker resolves pthread_create properly with glibc.
    -Wl,--whole-archive -lpthread -Wl,--no-whole-archive
)

echo "[build] compiling..."
"$CC" "${CFLAGS[@]}" "${SRC[@]}" "${LDFLAGS[@]}" -o bin/oal-cp-broker
arm-linux-gnueabihf-strip --strip-unneeded bin/oal-cp-broker 2>/dev/null || true

ls -la bin/oal-cp-broker
file bin/oal-cp-broker || true
echo "[build] done."
