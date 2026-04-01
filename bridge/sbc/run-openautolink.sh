#!/bin/bash
# run-openautolink.sh — Launch script for the headless binary
# Reads /etc/openautolink.env and passes appropriate CLI flags.
set -u

for f in /etc/openautolink.env /boot/firmware/openautolink.env; do
    [ -f "$f" ] && source "$f"
done

ARGS=(
    --session-mode=aasdk-live
    --tcp-car-port="${OAL_CAR_TCP_PORT:-5288}"
    --tcp-port="${OAL_PHONE_TCP_PORT:-5277}"
    --video-width="${OAL_VIDEO_WIDTH:-2400}"
    --video-height="${OAL_VIDEO_HEIGHT:-960}"
    --video-fps="${OAL_AA_FPS:-60}"
    --video-dpi="${OAL_AA_DPI:-160}"
    --video-codec="${OAL_AA_CODEC:-h264}"
    --aa-resolution="${OAL_AA_RESOLUTION:-1080p}"
    --head-unit-name="${OAL_HEAD_UNIT_NAME:-OpenAutoLink}"
)

# BT MAC override (empty = auto-detect from hci0)
if [ -n "${OAL_BT_MAC:-}" ]; then
    ARGS+=(--bt-mac="${OAL_BT_MAC}")
fi

# Wired AA: phone connects via USB host port
if [ "${OAL_PHONE_MODE:-wireless}" = "usb" ]; then
    ARGS+=(--usb)
    echo "[run] Phone mode: USB (wired AA)"
else
    echo "[run] Phone mode: wireless AA (TCP ${OAL_PHONE_TCP_PORT:-5277})"
fi

echo "[run] Protocol: OAL (control:${OAL_CAR_TCP_PORT:-5288} audio:$((${OAL_CAR_TCP_PORT:-5288}+1)) video:$((${OAL_CAR_TCP_PORT:-5288}+2)))"

exec /opt/openautolink/bin/openautolink-headless "${ARGS[@]}"
