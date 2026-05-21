#!/bin/sh
# start-bridge.sh — launch OpenAutoLink CarPlay bridge on CPC200.
#
# Placeholder. The actual bridge binary (oal-bridge) is not yet implemented.
# This script will:
#   1. Stop the stock Carlinkit CarPlay daemons (AppleCarPlay, ARMiPhoneIAP2)
#      so they don't fight us for the BT radio and MFi chip.
#   2. Start oal-bridge with the config file.
#
# Run on CPC200:  sh /tmp/start-bridge.sh

set -e

BIN=/tmp/bin/oal-bridge
CONF=/etc/oal-bridge.conf

if [ ! -x "${BIN}" ]; then
    echo "error: ${BIN} not found or not executable"
    exit 1
fi

if [ ! -f "${CONF}" ]; then
    echo "error: ${CONF} not found"
    exit 1
fi

echo "==> stopping stock Carlinkit CarPlay services"
killall AppleCarPlay 2>/dev/null || true
killall ARMiPhoneIAP2 2>/dev/null || true
sleep 1

echo "==> starting oal-bridge"
"${BIN}" -c "${CONF}" >> /tmp/oal-bridge.log 2>&1 &
echo "PID: $!"
sleep 1
ps | grep -v grep | grep oal-bridge
