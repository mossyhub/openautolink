#!/usr/bin/env bash
# Cross-compile wpa_supplicant + wpa_cli for CPC200 (armv7l, glibc, static).
#
# Target: i.MX6 UltraLite, kernel 3.14, glibc 2.20.
# Toolchain: Ubuntu arm-linux-gnueabihf-gcc (armhf, hard-float).
# Strategy: static linking to dodge the dynamic-linker-path mismatch.
#
# Run from WSL:   bash scripts/cpc200/build-wpa.sh
# Output:         scripts/cpc200/bin/wpa_supplicant, wpa_cli
#
# This script is idempotent: re-running re-uses downloads but rebuilds.
# Set CLEAN=1 to wipe the work dir first.

set -euo pipefail

# --- Versions (pinned for reproducibility) -----------------------------------
WPA_VER=2.10
LIBNL_VER=3.7.0

WPA_TARBALL="wpa_supplicant-${WPA_VER}.tar.gz"
WPA_URL="https://w1.fi/releases/${WPA_TARBALL}"
LIBNL_TARBALL="libnl-${LIBNL_VER}.tar.gz"
LIBNL_URL="https://github.com/thom311/libnl/releases/download/libnl${LIBNL_VER//./_}/${LIBNL_TARBALL}"

# --- Paths -------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
WORK_DIR="${SCRIPT_DIR}/build"
STAGE_DIR="${WORK_DIR}/stage"          # static-lib install prefix for libnl
OUT_DIR="${SCRIPT_DIR}/bin"

CROSS=arm-linux-gnueabihf
CC="${CROSS}-gcc"
STRIP="${CROSS}-strip"

# --- Sanity ------------------------------------------------------------------
command -v "${CC}" >/dev/null || {
    echo "error: ${CC} not found. Install with: sudo apt install arm-linux-gnueabihf-gcc-13"
    exit 1
}

if [[ "${CLEAN:-0}" == "1" ]]; then
    rm -rf "${WORK_DIR}"
fi
mkdir -p "${WORK_DIR}" "${STAGE_DIR}" "${OUT_DIR}"

# --- 1. libnl-3 (static) -----------------------------------------------------
cd "${WORK_DIR}"
if [[ ! -f "${LIBNL_TARBALL}" ]]; then
    echo "==> downloading ${LIBNL_TARBALL}"
    wget -q "${LIBNL_URL}"
fi
if [[ ! -d "libnl-${LIBNL_VER}" ]]; then
    tar xf "${LIBNL_TARBALL}"
fi

echo "==> building libnl"
cd "libnl-${LIBNL_VER}"
if [[ ! -f Makefile ]]; then
    ./configure \
        --host="${CROSS}" \
        --prefix="${STAGE_DIR}" \
        --enable-static \
        --disable-shared \
        --disable-cli
fi
make -j"$(nproc)"
make install

# --- 2. wpa_supplicant (static) ---------------------------------------------
cd "${WORK_DIR}"
if [[ ! -f "${WPA_TARBALL}" ]]; then
    echo "==> downloading ${WPA_TARBALL}"
    wget -q "${WPA_URL}"
fi
if [[ ! -d "wpa_supplicant-${WPA_VER}" ]]; then
    tar xf "${WPA_TARBALL}"
fi

WPA_SRC="${WORK_DIR}/wpa_supplicant-${WPA_VER}/wpa_supplicant"
cp "${SCRIPT_DIR}/config/wpa_supplicant.build.config" "${WPA_SRC}/.config"

echo "==> building wpa_supplicant"
cd "${WPA_SRC}"
make clean || true

# Inject pkg-config paths so wpa_supplicant finds our static libnl.
# Note: wpa_supplicant uses Makefile heuristics, not autotools; pass via env.
export PKG_CONFIG_PATH="${STAGE_DIR}/lib/pkgconfig"
export PKG_CONFIG="pkg-config --static"
export CC="${CC}"
export LDFLAGS="-static -L${STAGE_DIR}/lib"
export CFLAGS="-I${STAGE_DIR}/include -I${STAGE_DIR}/include/libnl3 -O2"
export EXTRA_CFLAGS="${CFLAGS}"

make -j"$(nproc)" wpa_supplicant wpa_cli

# --- 3. Strip + stage --------------------------------------------------------
"${STRIP}" wpa_supplicant wpa_cli
cp wpa_supplicant wpa_cli "${OUT_DIR}/"

echo
echo "==> done"
ls -lh "${OUT_DIR}/wpa_supplicant" "${OUT_DIR}/wpa_cli"
echo
echo "Verify static linkage (should say 'statically linked'):"
file "${OUT_DIR}/wpa_supplicant" || true
