#!/bin/bash
# build-bridge-wsl.sh — Cross-compile the bridge binary in WSL for ARM64.
#
# Copies source into WSL's native filesystem for ~10x faster builds
# (avoids the slow /mnt/ 9P bridge), then copies the result back.
#
# Usage (from WSL or invoked by deploy-bridge.ps1):
#   bash scripts/build-bridge-wsl.sh
#   bash scripts/build-bridge-wsl.sh clean    # full rebuild
set -eu

# Where the repo lives on the Windows side (may be /mnt/d/...)
REPO_ROOT="${REPO_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"

# Fast native WSL filesystem paths
WSL_SRC="$HOME/openautolink-src"
WSL_BUILD="$HOME/openautolink-build"

# Output goes back to the Windows filesystem for deploy-bridge.ps1
OUTPUT_DIR="${REPO_ROOT}/build-bridge-arm64"

if [ "${1:-}" = "clean" ]; then
    echo ">>> Clean build requested"
    rm -rf "$WSL_BUILD"
fi

# ── Sync sources into WSL native filesystem ──────────────────────────
# Use --checksum so only files with actual content changes get updated.
# This preserves timestamps on unchanged files, letting CMake skip
# recompilation of protobuf and aasdk (which take the bulk of build time).
echo "=== Cross-compiling bridge for ARM64 ==="
echo "  Syncing sources to WSL filesystem..."

mkdir -p "$WSL_SRC/bridge/openautolink" "$WSL_SRC/external" "$WSL_SRC/native"

# Bridge headless code — changes often, always sync
rsync -a --checksum --delete "$REPO_ROOT/bridge/openautolink/headless/" "$WSL_SRC/bridge/openautolink/headless/"
# Native helpers shared with the direct app SDR path.
rsync -a --checksum --delete "$REPO_ROOT/native/include/" "$WSL_SRC/native/include/"

# aasdk and openauto — submodules that rarely change.
# Only sync if the submodule HEAD changed (or not yet synced).
_sync_if_changed() {
    local src="$1" dst="$2" name="$3"
    local src_head dst_head=""
    src_head=$(git -C "$src" rev-parse HEAD 2>/dev/null || echo "unknown")
    if [ -f "$dst/.synced_head" ]; then
        dst_head=$(cat "$dst/.synced_head")
    fi
    if [ "$src_head" != "$dst_head" ] || [ ! -d "$dst" ]; then
        echo "  Syncing $name ($src_head)..."
        rsync -a --checksum --delete "$src/" "$dst/"
        echo "$src_head" > "$dst/.synced_head"
    else
        echo "  $name unchanged ($src_head), skipping sync"
    fi
}
_sync_if_changed "$REPO_ROOT/external/opencardev-aasdk" "$WSL_SRC/external/opencardev-aasdk" "aasdk"
_sync_if_changed "$REPO_ROOT/external/opencardev-openauto" "$WSL_SRC/external/opencardev-openauto" "openauto"

HEADLESS_DIR="${WSL_SRC}/bridge/openautolink/headless"
AASDK_DIR="${WSL_SRC}/external/opencardev-aasdk"
OPENAUTO_DIR="${WSL_SRC}/external/opencardev-openauto"

echo "  Source: ${HEADLESS_DIR}"
echo "  Build:  ${WSL_BUILD}"
echo ""

mkdir -p "$WSL_BUILD"

# CMake toolchain file for cross-compilation
TOOLCHAIN="${WSL_BUILD}/aarch64-toolchain.cmake"
cat > "$TOOLCHAIN" << 'EOF'
set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR aarch64)

set(CMAKE_C_COMPILER aarch64-linux-gnu-gcc)
set(CMAKE_CXX_COMPILER aarch64-linux-gnu-g++)

set(CMAKE_FIND_ROOT_PATH /usr/aarch64-linux-gnu /usr)
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)

# ARM64 library path
set(CMAKE_LIBRARY_PATH /usr/lib/aarch64-linux-gnu)
set(CMAKE_INCLUDE_PATH /usr/include)
EOF

cd "$WSL_BUILD"

# Read base version from secrets/version.properties if available
BASE_BRIDGE_VERSION="dev"
VERSION_FILE="${REPO_ROOT}/secrets/version.properties"
if [ -f "$VERSION_FILE" ]; then
    VER=$(grep '^versionName=' "$VERSION_FILE" | cut -d= -f2)
    if [ -n "$VER" ]; then
        BASE_BRIDGE_VERSION="$VER"
    fi
fi

# Local/dev builds must never collide with GitHub release numbering.
# Encode local provenance directly in the compiled bridge version.
LOCAL_SHA=$(git -C "$REPO_ROOT" rev-parse --short=8 HEAD 2>/dev/null || echo "nogit")
BRIDGE_VERSION="${BASE_BRIDGE_VERSION}-local.${LOCAL_SHA}"
echo "  Bridge version: ${BRIDGE_VERSION}"
echo "  Build source: local"

if [ ! -f CMakeCache.txt ]; then
    echo ">>> Configuring CMake..."
    # CMake 4.x policy compat for older sub-projects (issue #11)
    export CMAKE_POLICY_VERSION_MINIMUM=3.5
    cmake "$HEADLESS_DIR" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
        -DCMAKE_BUILD_TYPE=Release \
        -DPI_AA_ENABLE_AASDK_LIVE=ON \
        -DPI_AA_AASDK_SOURCE_DIR="$AASDK_DIR" \
        -DPI_AA_OPENAUTO_SOURCE_DIR="$OPENAUTO_DIR" \
        -DSKIP_BUILD_ABSL=ON \
        -DSKIP_BUILD_PROTOBUF=ON \
        -DBUILD_AASDK_STATIC=ON \
        -DOAL_BRIDGE_VERSION="$BRIDGE_VERSION" \
        -DOAL_BUILD_SOURCE="local"
    echo ""
else
    # Always update version in cached builds — it's compiled into the binary
    # via add_compile_definitions and won't update unless we tell CMake.
    CACHED_VER=$(grep '^OAL_BRIDGE_VERSION:' CMakeCache.txt | cut -d= -f2)
    CACHED_SRC=$(grep '^OAL_BUILD_SOURCE:' CMakeCache.txt | cut -d= -f2)
    if [ "$CACHED_VER" != "$BRIDGE_VERSION" ] || [ "$CACHED_SRC" != "local" ]; then
        echo ">>> Updating cached bridge metadata: version/source"
        cmake . -DOAL_BRIDGE_VERSION="$BRIDGE_VERSION" -DOAL_BUILD_SOURCE="local" >/dev/null 2>&1
    fi
fi

echo ">>> Building..."
cmake --build . --target openautolink-headless -j$(nproc)

echo ""
echo ">>> Stripping binary..."
aarch64-linux-gnu-strip -o openautolink-headless-stripped openautolink-headless

ls -lh openautolink-headless-stripped

# ── Copy result back to Windows filesystem ───────────────────────────
echo ""
echo ">>> Copying binary to ${OUTPUT_DIR}/"
mkdir -p "$OUTPUT_DIR"
cp openautolink-headless-stripped "$OUTPUT_DIR/"

echo ""
echo "=== Build complete ==="
echo "  Binary: ${OUTPUT_DIR}/openautolink-headless-stripped"
echo "  Deploy: scripts/deploy-bridge.ps1  (from PowerShell)"
