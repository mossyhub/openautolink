#!/bin/bash
# install.sh — One-shot setup for OpenAutoLink on any ARM64 Linux SBC
# Downloads the bridge binary and all config from GitHub, installs everything.
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/mossyhub/openautolink/main/bridge/sbc/install.sh | sudo bash
#   # — or —
#   wget -qO- https://raw.githubusercontent.com/mossyhub/openautolink/main/bridge/sbc/install.sh | sudo bash
#   # — or download the file first, inspect it, then run: —
#   sudo bash install.sh
#
# Tested on: Raspberry Pi CM5, Khadas VIM4, ROCK 3A (any ARM64 with WiFi+BT)
set -eu

GITHUB_REPO="mossyhub/openautolink"
INSTALL_DIR="/opt/openautolink"
TMP_DIR="/tmp/openautolink-install"

echo "=== OpenAutoLink Bridge Installer ==="
echo ""

# ── Checks ────────────────────────────────────────────────────────────
if [ "$(id -u)" -ne 0 ]; then
    echo "ERROR: This script must be run as root (use sudo)." >&2
    exit 1
fi

ARCH=$(uname -m)
if [ "$ARCH" != "aarch64" ]; then
    echo "ERROR: This installer only supports ARM64 (aarch64) SBCs." >&2
    echo "  Detected: $ARCH" >&2
    exit 1
fi

# ── 1. System packages ───────────────────────────────────────────────
echo ">>> [1/6] Installing system packages..."
apt-get update -qq
apt-get install -y -qq \
    hostapd dnsmasq \
    bluez libbluetooth-dev python3-dbus python3-gi \
    avahi-daemon avahi-utils \
    curl jq
echo ""

# ── 2. Download latest release from GitHub ────────────────────────────
echo ">>> [2/6] Downloading latest release..."
rm -rf "$TMP_DIR"
mkdir -p "$TMP_DIR"

# Get the latest release tag
LATEST_TAG=$(curl -fsSL "https://api.github.com/repos/${GITHUB_REPO}/releases/latest" | jq -r '.tag_name')
if [ -z "$LATEST_TAG" ] || [ "$LATEST_TAG" = "null" ]; then
    echo "ERROR: Could not determine latest release." >&2
    exit 1
fi
echo "  Latest release: ${LATEST_TAG}"

RELEASE_URL="https://github.com/${GITHUB_REPO}/releases/download/${LATEST_TAG}"
RAW_URL="https://raw.githubusercontent.com/${GITHUB_REPO}/${LATEST_TAG}"

# Download the bridge binary
echo "  Downloading bridge binary..."
if ! curl -fsSL -o "${TMP_DIR}/openautolink-headless" \
    "${RELEASE_URL}/openautolink-headless"; then
    echo "ERROR: Failed to download bridge binary." >&2
    echo "  The release may not have finished building yet." >&2
    echo "  Check: https://github.com/${GITHUB_REPO}/releases/tag/${LATEST_TAG}" >&2
    exit 1
fi

# Download SBC files from the repo at the release tag
# Download SBC files — use explicit local names to avoid collisions
# (the avahi service and systemd service both have the same basename)
declare -A SBC_FILES=(
    ["openautolink.env"]="bridge/sbc/openautolink.env"
    ["openautolink.service"]="bridge/sbc/openautolink.service"
    ["openautolink-bt.service"]="bridge/sbc/openautolink-bt.service"
    ["openautolink-wireless.service"]="bridge/sbc/openautolink-wireless.service"
    ["openautolink-network.service"]="bridge/sbc/openautolink-network.service"
    ["run-openautolink.sh"]="bridge/sbc/run-openautolink.sh"
    ["setup-network.sh"]="bridge/sbc/setup-network.sh"
    ["start-wireless.sh"]="bridge/sbc/start-wireless.sh"
    ["stop-wireless.sh"]="bridge/sbc/stop-wireless.sh"
    ["aa_bt_all.py"]="bridge/openautolink/scripts/aa_bt_all.py"
    ["avahi-openautolink.service"]="bridge/openautolink/headless/avahi/openautolink.service"
)

echo "  Downloading configuration files..."
for local_name in "${!SBC_FILES[@]}"; do
    curl -fsSL -o "${TMP_DIR}/${local_name}" "${RAW_URL}/${SBC_FILES[$local_name]}" 2>/dev/null || true
done
echo ""

# ── 3. Deploy files ──────────────────────────────────────────────────
echo ">>> [3/6] Installing to ${INSTALL_DIR}..."
mkdir -p "${INSTALL_DIR}/bin" "${INSTALL_DIR}/scripts"

# Binary
cp "${TMP_DIR}/openautolink-headless" "${INSTALL_DIR}/bin/"
chmod +x "${INSTALL_DIR}/bin/openautolink-headless"
echo "  Installed openautolink-headless binary"

# Scripts
for script in run-openautolink.sh setup-network.sh \
              start-wireless.sh stop-wireless.sh; do
    [ -f "${TMP_DIR}/${script}" ] && cp "${TMP_DIR}/${script}" "${INSTALL_DIR}/"
done
chmod +x "${INSTALL_DIR}"/*.sh 2>/dev/null || true

# BT script
[ -f "${TMP_DIR}/aa_bt_all.py" ] && \
    cp "${TMP_DIR}/aa_bt_all.py" "${INSTALL_DIR}/scripts/"

# Env file (don't overwrite if user already has one)
if [ ! -f /etc/openautolink.env ]; then
    cp "${TMP_DIR}/openautolink.env" /etc/openautolink.env
    echo "  Created /etc/openautolink.env (edit this to configure)"
else
    echo "  /etc/openautolink.env exists — not overwriting"
fi

# Avahi service (mDNS discovery)
if [ -d /etc/avahi/services ] && [ -f "${TMP_DIR}/avahi-openautolink.service" ]; then
    cp "${TMP_DIR}/avahi-openautolink.service" /etc/avahi/services/openautolink.service
    echo "  Installed Avahi mDNS service"
fi

# SSL certificates for Android Auto TLS handshake
# aasdk has embedded certs (JVC Kenwood AA cert) that work with all phones.
# Do NOT generate custom certs — they cause SSL handshake failures.
# If /etc/aasdk/ exists with custom certs from a previous install, remove them.
if [ -d "/etc/aasdk" ]; then
    echo "  Removing custom certs (aasdk uses embedded certs)"
    rm -rf "/etc/aasdk"
fi
echo ""

# ── 4. USB gadget + kernel modules (only if not using external-nic) ──
echo ">>> [4/7] Checking car network mode..."
source /etc/openautolink.env 2>/dev/null || true
if [ "${OAL_CAR_NET_MODE:-external-nic}" != "external-nic" ]; then
    if [ -f /boot/firmware/config.txt ]; then
        grep -q "^dtoverlay=dwc2" /boot/firmware/config.txt || \
            echo "dtoverlay=dwc2,dr_mode=peripheral" >> /boot/firmware/config.txt
        echo "  Raspberry Pi: dwc2 overlay configured"
    elif [ -f /boot/config.txt ]; then
        grep -q "^dtoverlay=dwc2" /boot/config.txt || \
            echo "dtoverlay=dwc2,dr_mode=peripheral" >> /boot/config.txt
        echo "  Raspberry Pi (legacy): dwc2 overlay configured"
    else
        echo "  Non-RPi platform — see docs for USB gadget setup"
    fi
    for mod in libcomposite usb_f_ecm usb_f_mass_storage; do
        grep -q "^${mod}$" /etc/modules 2>/dev/null || echo "$mod" >> /etc/modules
    done
    echo "  Kernel modules configured"
else
    echo "  Skipped (external-nic mode)"
fi
echo ""

# ── 5. Hostname + mDNS ───────────────────────────────────────────────
echo ">>> [5/7] Setting hostname..."
hostnamectl set-hostname openautolink 2>/dev/null || true
grep -q "openautolink" /etc/hosts || echo "127.0.1.1 openautolink" >> /etc/hosts
echo "  Hostname: openautolink"
echo ""

# ── 6. Service user ──────────────────────────────────────────────────
echo ">>> [6/7] Setting up openautolink user..."
if ! id openautolink &>/dev/null; then
    useradd -m -s /bin/bash -G sudo openautolink
    echo "openautolink:openautolink" | chpasswd
    echo "  Created user 'openautolink' (change password with: passwd openautolink)"
else
    echo "  User 'openautolink' already exists"
fi
# Passwordless sudo for deploy scripts
echo "openautolink ALL=(ALL) NOPASSWD: ALL" > /etc/sudoers.d/openautolink
chmod 440 /etc/sudoers.d/openautolink
echo "  Passwordless sudo configured"
echo ""

# ── 7. Systemd services ──────────────────────────────────────────────
echo ">>> [7/7] Installing systemd services..."
for svc in openautolink.service openautolink-network.service \
           openautolink-wireless.service openautolink-bt.service; do
    if [ -f "${TMP_DIR}/${svc}" ]; then
        cp "${TMP_DIR}/${svc}" /etc/systemd/system/
    fi
done

# Disable legacy services if they exist from a previous install
systemctl disable openautolink-car-net openautolink-eth-ssh 2>/dev/null || true

systemctl daemon-reload
systemctl enable openautolink-network openautolink openautolink-wireless 2>/dev/null || true
systemctl enable openautolink-bt 2>/dev/null || true

# Clean up
rm -rf "$TMP_DIR"

echo ""
echo "=== Installation complete ==="
echo ""
echo "  Binary:  ${INSTALL_DIR}/bin/openautolink-headless"
echo "  Config:  /etc/openautolink.env"
echo "  SSH user: openautolink (passwordless sudo)"
echo "  Version: ${LATEST_TAG}"
echo ""
echo "  ┌──────────────────────────────────────────────────────────────┐"
echo "  │  IMPORTANT: After reboot, the onboard Ethernet adapter     │"
echo "  │  will be reconfigured for the car connection and the WiFi  │"
echo "  │  radio becomes a phone hotspot. Internet access on the     │"
echo "  │  SBC will no longer be available.                          │"
echo "  │                                                            │"
echo "  │  Make any config changes NOW, before rebooting.            │"
echo "  │                                                            │"
echo "  │  After reboot, you can still SSH into the SBC via the      │"
echo "  │  WiFi AP (connect to the OpenAutoLink SSID, then SSH to    │"
echo "  │  192.168.43.1).                                            │"
echo "  └──────────────────────────────────────────────────────────────┘"
echo ""
echo "  Next steps:"
echo "    1. Edit /etc/openautolink.env to match your setup"
echo "       (video resolution, codec, display insets, country code)"
echo "    2. Reboot: sudo reboot"
echo "    3. The bridge starts automatically on boot"
echo ""
echo "  To update later:"
echo "    Temporarily reconnect the SBC to a network with internet,"
echo "    then run this installer again."
echo ""
