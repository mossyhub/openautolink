#!/bin/bash
# setup-wsl-cross-compile.sh — One-time setup for cross-compiling
# the bridge binary on WSL (x86_64) targeting ARM64 SBC.
#
# Run inside WSL: bash scripts/setup-wsl-cross-compile.sh
set -eu

echo "=== OpenAutoLink WSL Cross-Compile Setup ==="
echo ""

# 1. Add ARM64 as foreign architecture (may already be done)
if ! dpkg --print-foreign-architectures | grep -q arm64; then
    echo ">>> Adding arm64 architecture..."
    sudo dpkg --add-architecture arm64
    sudo apt-get update -qq
else
    echo ">>> arm64 architecture already registered"
fi

# 2. Install cross-compiler and ARM64 libraries
echo ">>> Installing cross-compilation toolchain..."
sudo apt-get install -y -qq \
    g++-aarch64-linux-gnu \
    cmake make git \
    libboost-system-dev:arm64 \
    libboost-log-dev:arm64 \
    libboost-filesystem-dev:arm64 \
    libboost-thread-dev:arm64 \
    libssl-dev:arm64 \
    libusb-1.0-0-dev:arm64 \
    libprotobuf-dev:arm64 \
    libabsl-dev:arm64 \
    protobuf-compiler

echo ""
echo ">>> Verifying toolchain..."
aarch64-linux-gnu-g++ --version | head -1
cmake --version | head -1

echo ""
echo "=== Setup complete ==="
echo ""
echo "Build with:  scripts/build-bridge-wsl.sh"
echo "Deploy with: scripts/deploy-bridge.ps1 (from PowerShell)"
