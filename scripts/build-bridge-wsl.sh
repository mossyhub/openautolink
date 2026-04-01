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
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

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
echo "=== Cross-compiling bridge for ARM64 ==="
echo "  Syncing sources to WSL filesystem..."

mkdir -p "$WSL_SRC/bridge/openautolink" "$WSL_SRC/external"
rsync -a --delete "$REPO_ROOT/bridge/openautolink/headless/" "$WSL_SRC/bridge/openautolink/headless/"
rsync -a --delete "$REPO_ROOT/external/opencardev-aasdk/" "$WSL_SRC/external/opencardev-aasdk/"
rsync -a --delete "$REPO_ROOT/external/opencardev-openauto/" "$WSL_SRC/external/opencardev-openauto/"

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

if [ ! -f CMakeCache.txt ]; then
    echo ">>> Configuring CMake..."
    cmake "$HEADLESS_DIR" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
        -DCMAKE_BUILD_TYPE=Release \
        -DPI_AA_ENABLE_AASDK_LIVE=ON \
        -DPI_AA_AASDK_SOURCE_DIR="$AASDK_DIR" \
        -DPI_AA_OPENAUTO_SOURCE_DIR="$OPENAUTO_DIR" \
        -DSKIP_BUILD_ABSL=ON \
        -DSKIP_BUILD_PROTOBUF=ON
    echo ""
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
