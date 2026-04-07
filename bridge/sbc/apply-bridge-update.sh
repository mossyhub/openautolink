#!/bin/bash
# apply-bridge-update.sh — Applies a bridge binary update received from the car app.
#
# Called by openautolink-headless when it receives a verified update binary.
# Arguments:
#   $1 — Path to the verified new binary (temp file)
#
# Flow:
#   1. Stop the openautolink service
#   2. Back up the current binary
#   3. Move the new binary into place
#   4. Set permissions
#   5. Start the openautolink service
#
# The service restart causes the app's TCP connections to drop.
# The app's auto-reconnect logic handles the rest.
set -eu

NEW_BINARY="$1"
INSTALL_DIR="/opt/openautolink/bin"
BINARY_NAME="openautolink-headless"
INSTALL_PATH="${INSTALL_DIR}/${BINARY_NAME}"
BACKUP_PATH="${INSTALL_DIR}/${BINARY_NAME}.bak"

if [ ! -f "$NEW_BINARY" ]; then
    echo "[update] ERROR: new binary not found: $NEW_BINARY" >&2
    exit 1
fi

# Verify the new binary is executable (ELF)
if ! file "$NEW_BINARY" | grep -q "ELF"; then
    echo "[update] ERROR: new binary is not a valid ELF executable" >&2
    exit 1
fi

echo "[update] Stopping openautolink service..." >&2

# Back up current binary
if [ -f "$INSTALL_PATH" ]; then
    cp "$INSTALL_PATH" "$BACKUP_PATH"
    echo "[update] Backed up current binary to ${BACKUP_PATH}" >&2
fi

# Move new binary into place
mv "$NEW_BINARY" "$INSTALL_PATH"
chmod 755 "$INSTALL_PATH"
echo "[update] Installed new binary to ${INSTALL_PATH}" >&2

# Restart via systemd (this will kill us, which is fine —
# the service unit restarts the binary)
echo "[update] Restarting openautolink service..." >&2
systemctl restart openautolink.service &

exit 0
