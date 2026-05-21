#!/bin/sh
# install-wpa.sh — switch CPC200 from AP mode to WiFi client mode.
#
# Run on CPC200 (not in WSL):
#   1. SCP this script + wpa_supplicant + wpa_cli + wpa_supplicant.conf to CPC200
#   2. Run: sh /tmp/install-wpa.sh
#
# What it does:
#   1. Stops stock hostapd / udhcpd (kills AP)
#   2. Installs our static wpa_supplicant + wpa_cli to /tmp/bin/
#   3. Starts wpa_supplicant against /etc/wpa_supplicant.conf
#   4. Runs udhcpc on wlan0 to get a DHCP lease from the joined network
#
# WARNING: This will drop your SSH session if you're connected via the AP.
# Make sure you have a way back in (serial console, or be on the network you're
# joining via another route).

set -e

CONF=/etc/wpa_supplicant.conf

if [ ! -f /tmp/wpa_supplicant ] || [ ! -f /tmp/wpa_cli ]; then
    echo "error: /tmp/wpa_supplicant and /tmp/wpa_cli must be uploaded first"
    exit 1
fi

if [ ! -f "${CONF}" ]; then
    echo "error: ${CONF} not found. Upload wpa_supplicant.conf first."
    exit 1
fi

chmod +x /tmp/wpa_supplicant /tmp/wpa_cli
mkdir -p /tmp/bin
cp /tmp/wpa_supplicant /tmp/wpa_cli /tmp/bin/

echo "==> stopping AP mode"
killall hostapd 2>/dev/null || true
killall udhcpd 2>/dev/null || true
sleep 1

echo "==> bringing wlan0 down + up (clear AP state)"
ifconfig wlan0 down
sleep 1
ifconfig wlan0 up

echo "==> starting wpa_supplicant"
/tmp/bin/wpa_supplicant -B -i wlan0 -D nl80211 -c "${CONF}"
sleep 3

echo "==> wpa_cli status:"
/tmp/bin/wpa_cli -i wlan0 status || true

echo "==> requesting DHCP lease"
udhcpc -i wlan0 -t 10 -q || {
    echo "warn: DHCP failed. Check wpa_cli status above; SSID may be wrong."
    exit 1
}

echo
echo "==> success"
ifconfig wlan0
