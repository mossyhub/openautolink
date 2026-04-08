#!/bin/bash
# run-openautolink.sh — Launch script for the headless binary
# Reads /etc/openautolink.env and passes appropriate CLI flags.
set -u

for f in /etc/openautolink.env /boot/firmware/openautolink.env; do
    [ -f "$f" ] && source "$f"
done

ARGS=(
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

if [ -n "${OAL_AA_WIDTH_MARGIN:-}" ]; then
    ARGS+=(--aa-width-margin="${OAL_AA_WIDTH_MARGIN}")
fi
if [ -n "${OAL_AA_HEIGHT_MARGIN:-}" ]; then
    ARGS+=(--aa-height-margin="${OAL_AA_HEIGHT_MARGIN}")
fi
if [ -n "${OAL_AA_PIXEL_ASPECT_E4:-}" ]; then
    ARGS+=(--aa-pixel-aspect-e4="${OAL_AA_PIXEL_ASPECT_E4}")
fi
if [ -n "${OAL_AA_REAL_DENSITY:-}" ]; then
    ARGS+=(--aa-real-density="${OAL_AA_REAL_DENSITY}")
fi
if [ -n "${OAL_AA_VIEWING_DISTANCE:-}" ]; then
    ARGS+=(--aa-viewing-distance="${OAL_AA_VIEWING_DISTANCE}")
fi
if [ -n "${OAL_AA_DECODER_ADDITIONAL_DEPTH:-}" ]; then
    ARGS+=(--aa-decoder-additional-depth="${OAL_AA_DECODER_ADDITIONAL_DEPTH}")
fi
if [ -n "${OAL_AA_INIT_MARGINS:-}" ]; then
    ARGS+=(--aa-init-margins="${OAL_AA_INIT_MARGINS}")
fi
if [ -n "${OAL_AA_INIT_CONTENT_INSETS:-}" ]; then
    ARGS+=(--aa-init-content-insets="${OAL_AA_INIT_CONTENT_INSETS}")
fi
if [ -n "${OAL_AA_INIT_STABLE_INSETS:-}" ]; then
    ARGS+=(--aa-init-stable-insets="${OAL_AA_INIT_STABLE_INSETS}")
fi
if [ -n "${OAL_AA_RUNTIME_DELAY_MS:-}" ]; then
    ARGS+=(--aa-runtime-delay-ms="${OAL_AA_RUNTIME_DELAY_MS}")
fi
if [ -n "${OAL_AA_RUNTIME_MARGINS:-}" ]; then
    ARGS+=(--aa-runtime-margins="${OAL_AA_RUNTIME_MARGINS}")
fi
if [ -n "${OAL_AA_RUNTIME_CONTENT_INSETS:-}" ]; then
    ARGS+=(--aa-runtime-content-insets="${OAL_AA_RUNTIME_CONTENT_INSETS}")
fi
if [ -n "${OAL_AA_RUNTIME_STABLE_INSETS:-}" ]; then
    ARGS+=(--aa-runtime-stable-insets="${OAL_AA_RUNTIME_STABLE_INSETS}")
fi

# Session mode derived from phone protocol
case "${OAL_PHONE_PROTOCOL:-android-auto}" in
    android-auto) ARGS+=(--session-mode=aasdk-live) ;;
    *)            ARGS+=(--session-mode=aasdk-live) ;;  # default to AA
esac

# BT MAC override (empty = auto-detect from hci0)
if [ -n "${OAL_BT_MAC:-}" ]; then
    ARGS+=(--bt-mac="${OAL_BT_MAC}")
fi

# Session UI flags (P2: AA status bar)
if [ "${OAL_AA_HIDE_CLOCK:-false}" = "true" ]; then
    ARGS+=(--hide-clock)
fi
if [ "${OAL_AA_HIDE_PHONE_SIGNAL:-false}" = "true" ]; then
    ARGS+=(--hide-phone-signal)
fi
if [ "${OAL_AA_HIDE_BATTERY:-false}" = "true" ]; then
    ARGS+=(--hide-battery)
fi

# Wired AA: phone connects via USB host port
if [ "${OAL_PHONE_MODE:-wireless}" = "usb" ]; then
    ARGS+=(--usb)
    echo "[run] Phone mode: USB (wired AA)"
else
    echo "[run] Phone mode: wireless (${OAL_PHONE_PROTOCOL:-android-auto})"
fi

echo "[run] Protocol: OAL (control:${OAL_CAR_TCP_PORT:-5288} audio:$((${OAL_CAR_TCP_PORT:-5288}+1)) video:$((${OAL_CAR_TCP_PORT:-5288}+2)))"

exec /opt/openautolink/bin/openautolink-headless "${ARGS[@]}"
