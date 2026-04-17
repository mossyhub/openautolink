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
echo ">>> [1/8] Installing system packages..."
apt-get update -qq

apt-get install -y -qq \
    hostapd dnsmasq \
    bluez python3-dbus python3-gi \
    avahi-daemon avahi-utils \
    curl jq
echo ""

# ── 2. Download latest release from GitHub ────────────────────────────
echo ">>> [2/8] Downloading latest release..."
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

# Download the relay binary
echo "  Downloading relay binary..."
if ! curl -fsSL -o "${TMP_DIR}/openautolink-relay" \
    "${RELEASE_URL}/openautolink-relay"; then
    echo "ERROR: Failed to download relay binary." >&2
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

)

echo "  Downloading configuration files..."
for local_name in "${!SBC_FILES[@]}"; do
    curl -fsSL -o "${TMP_DIR}/${local_name}" "${RAW_URL}/${SBC_FILES[$local_name]}" 2>/dev/null || true
done
echo ""

# ── 3. Deploy files ──────────────────────────────────────────────────
echo ">>> [3/8] Installing to ${INSTALL_DIR}..."
mkdir -p "${INSTALL_DIR}/bin" "${INSTALL_DIR}/scripts"

# Binary
cp "${TMP_DIR}/openautolink-relay" "${INSTALL_DIR}/bin/"
chmod +x "${INSTALL_DIR}/bin/openautolink-relay"
echo "  Installed openautolink-relay binary"

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

# ── Clean up old headless architecture (pre-direct-AA) ────────────────
# The old architecture ran aasdk on the bridge ("openautolink-headless").
# The new architecture runs aasdk in the car app; the bridge is just a relay.
# Remove all traces of the old setup so nothing conflicts.
OLD_CLEANED=0

# Old binary + auto-update script
for f in "${INSTALL_DIR}/bin/openautolink-headless" \
         "${INSTALL_DIR}/bin/openautolink-headless.bak" \
         "${INSTALL_DIR}/bin/apply-bridge-update.sh"; do
    [ -f "$f" ] && { rm -f "$f"; OLD_CLEANED=1; }
done

# Old Avahi mDNS service (headless published this; relay uses avahi-daemon directly)
if [ -f /etc/avahi/services/openautolink.service ]; then
    rm -f /etc/avahi/services/openautolink.service
    OLD_CLEANED=1
fi

# Old aasdk cert directory (aasdk now runs in-app with embedded certs)
if [ -d /etc/aasdk ]; then
    rm -rf /etc/aasdk
    OLD_CLEANED=1
fi

# Old systemd services from earlier iterations
for svc in openautolink-car-net openautolink-eth-ssh; do
    if systemctl list-unit-files "$svc.service" &>/dev/null; then
        systemctl disable --now "$svc" 2>/dev/null || true
        rm -f "/etc/systemd/system/${svc}.service"
        OLD_CLEANED=1
    fi
done

# Strip old headless-only env vars from /etc/openautolink.env if present.
# These were used by the headless binary and are now in the car app's settings.
if [ -f /etc/openautolink.env ]; then
    OLD_VARS=(
        OAL_BRIDGE_UPDATE_MODE
        OAL_PHONE_PROTOCOL
        OAL_CAR_TCP_PORT
        OAL_PHONE_TCP_PORT
        OAL_VIDEO_WIDTH
        OAL_VIDEO_HEIGHT
        OAL_AA_DPI
        OAL_AA_RESOLUTION
        OAL_AA_FPS
        OAL_AA_CODEC
        OAL_AA_WIDTH_MARGIN
        OAL_AA_HEIGHT_MARGIN
        OAL_AA_PIXEL_ASPECT_E4
        OAL_AA_REAL_DENSITY
        OAL_AA_VIEWING_DISTANCE
        OAL_AA_DECODER_ADDITIONAL_DEPTH
        OAL_AA_INIT_MARGINS
        OAL_AA_INIT_CONTENT_INSETS
        OAL_AA_INIT_STABLE_INSETS
        OAL_AA_RUNTIME_DELAY_MS
        OAL_AA_RUNTIME_MARGINS
        OAL_AA_RUNTIME_CONTENT_INSETS
        OAL_AA_RUNTIME_STABLE_INSETS
        OAL_HEAD_UNIT_NAME
        OAL_CAR_MAKE
        OAL_CAR_MODEL
        OAL_CAR_YEAR
        OAL_FUEL_TYPES
        OAL_EV_CONNECTOR_TYPES
        OAL_AA_HIDE_CLOCK
        OAL_AA_HIDE_PHONE_SIGNAL
        OAL_AA_HIDE_BATTERY
    )
    for var in "${OLD_VARS[@]}"; do
        if grep -q "^${var}=" /etc/openautolink.env 2>/dev/null; then
            sed -i "/^${var}=/d" /etc/openautolink.env
            OLD_CLEANED=1
        fi
    done
    # Also remove comment-only lines that referenced old settings
    sed -i '/^#.*OAL protocol:.*control.*audio.*video/d' /etc/openautolink.env 2>/dev/null || true
fi

if [ "$OLD_CLEANED" = "1" ]; then
    echo "  Cleaned up old headless-era files and config"
else
    echo "  No old headless files found (clean install)"
fi
echo ""

# ── 4. USB gadget + kernel modules (only if not using external-nic) ──
echo ">>> [4/8] Checking car network mode..."
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
echo ">>> [5/8] Setting hostname..."
hostnamectl set-hostname openautolink 2>/dev/null || true
grep -q "openautolink" /etc/hosts || echo "127.0.1.1 openautolink" >> /etc/hosts
echo "  Hostname: openautolink"
echo ""

# ── 6. Service user ──────────────────────────────────────────────────
echo ">>> [6/8] Setting up openautolink user..."
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
echo ">>> [7/8] Installing systemd services..."
for svc in openautolink.service openautolink-network.service \
           openautolink-wireless.service openautolink-bt.service; do
    if [ -f "${TMP_DIR}/${svc}" ]; then
        cp "${TMP_DIR}/${svc}" /etc/systemd/system/
    fi
done

# We launch hostapd/dnsmasq directly from the OpenAutoLink scripts. Leaving the
# distro package units enabled causes boot-time failures and a degraded system.
systemctl disable --now dnsmasq hostapd 2>/dev/null || true
systemctl reset-failed dnsmasq hostapd 2>/dev/null || true

systemctl daemon-reload
systemctl enable openautolink-network openautolink openautolink-wireless 2>/dev/null || true
systemctl enable openautolink-bt 2>/dev/null || true

# Disable DietPi/ifupdown DHCP on ethernet — our network service manages NICs
if [ -f /etc/network/interfaces ]; then
    if grep -q "^allow-hotplug eth0" /etc/network/interfaces || \
       grep -q "^iface eth0 inet dhcp" /etc/network/interfaces; then
        sed -i 's/^allow-hotplug eth0/#allow-hotplug eth0/' /etc/network/interfaces
        sed -i 's/^iface eth0 inet dhcp/#iface eth0 inet dhcp/' /etc/network/interfaces
        echo "  Disabled DietPi DHCP on eth0 (our network service manages NICs)"
    fi
fi

# Disable Armbian's blanket netplan DHCP-on-all-ethernet config. It makes
# systemd-networkd reclaim eth0/eth1 later in boot and override the static
# addresses assigned by openautolink-network.
if [ -f /etc/netplan/10-dhcp-all-interfaces.yaml ]; then
    mkdir -p /etc/netplan/disabled
    mv /etc/netplan/10-dhcp-all-interfaces.yaml \
       /etc/netplan/disabled/10-dhcp-all-interfaces.yaml.disabled
    netplan generate 2>/dev/null || true
    echo "  Disabled Armbian netplan DHCP-all-ethernet config"
fi

# Clean up
rm -rf "$TMP_DIR"

# ── 8. Apply network now ─────────────────────────────────────────────
echo ">>> [8/8] Applying network configuration..."
echo ""

# Detect the onboard NIC — scan sysfs for the first non-virtual,
# non-USB, non-wireless interface. Most SBCs have exactly one.
ONBOARD_NIC=""
for path in /sys/class/net/*; do
    iface=$(basename "$path")
    case "$iface" in lo|usb*|wlan*|wlp*|enx*) continue ;; esac
    devpath=$(readlink -f "/sys/class/net/$iface/device" 2>/dev/null)
    case "$devpath" in */usb*) continue ;; esac
    ONBOARD_NIC="$iface"
    break
done

source /etc/openautolink.env 2>/dev/null || true
CAR_IP="${OAL_CAR_NET_IP:-192.168.222.222}"

# Start the network service now (assigns car IP to onboard NIC)
systemctl start openautolink-network 2>/dev/null || true

echo ""
echo "=== Installation complete ==="
echo ""
echo "  Binary:   ${INSTALL_DIR}/bin/openautolink-relay"
echo "  Config:   /etc/openautolink.env"
echo "  Hostname: openautolink"
echo "  SSH user: openautolink (passwordless sudo)"
echo "  Version:  ${LATEST_TAG}"
echo ""
echo "  Network (active now):"
echo "    Onboard NIC (${ONBOARD_NIC:-unknown}) -> ${CAR_IP}  (car connection)"
echo "    USB NIC (if plugged in)    -> DHCP        (SSH access)"
echo "    WiFi radio                 -> phone hotspot (after reboot)"
echo ""
echo "  To SSH into the SBC in the car:"
echo "    - Plug a USB Ethernet adapter into the SBC"
echo "    - It picks up DHCP automatically from your network"
echo "    - Or connect to the OpenAutoLink WiFi, SSH to 192.168.43.1"
echo ""
echo "  Updates:"
echo "    Deploy new bridge: scripts/deploy-bridge.ps1 from the dev PC"
echo "    Or manually: scp openautolink-relay openautolink:/opt/openautolink/bin/"
echo ""
echo "  Reboot to start all services: sudo reboot"
echo ""
