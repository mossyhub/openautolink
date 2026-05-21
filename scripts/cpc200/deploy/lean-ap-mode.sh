#!/bin/sh
# lean-ap-mode.sh — neutralize CarPlay/HiCar/BT services so the CPC200 acts
# as a plain WiFi AP. iPhone joining this AP will NOT see it as a CarPlay
# adapter (no BT SDP advertising, no iAP2, no mDNS).
#
# Keeps running: hostapd, udhcpd, dbus-daemon, dropbear (ssh), boa (optional).
#
# Strategy:
#   1. Set lockfile/exitfile so phone_link_deamon.sh respawn loop exits.
#   2. Kill the respawner shell scripts themselves.
#   3. Neutralize binaries in /tmp/bin/ (replace with no-op stub so
#      respawners see "test -e" succeed and don't recopy from /usr/sbin/).
#   4. kill -9 anything still running, in retry rounds.
#
# Transient only — /tmp is tmpfs, reboot returns to stock behavior.

set -u
say() { echo "[lean-ap] $*"; }

STUB=/tmp/bin/.noop
printf '#!/bin/sh\nexit 0\n' > "$STUB"
chmod +x "$STUB"

say "signaling phone_link_deamon exit"
for link in CarPlay AndroidAuto HiCar ICCOA CarLife; do
    touch /tmp/.${link}_daemon_exited
    rm  -f /tmp/.${link}_daemon_started
done

say "killing respawner shell scripts"
for p in $(ps | awk '/phone_link_deamon\.sh/ && !/awk/ {print $1}'); do
    kill -9 "$p" 2>/dev/null
done

say "neutralizing /tmp/bin/ binaries"
for bin in AppleCarPlay ARMiPhoneIAP2 ARMHiCar ARMAndroidAuto ARMadb-driver \
           bluetoothDaemon hcid mdnsd CarLife iccoa; do
    if [ -e /tmp/bin/$bin ]; then
        cp "$STUB" /tmp/bin/$bin
    fi
done

say "hard-killing running processes (3 rounds)"
for round in 1 2 3; do
    for proc in bluetoothDaemon hcid hciattach ARMHiCar ARMAndroidAuto \
                AppleCarPlay ARMiPhoneIAP2 ARMadb-driver mdnsd; do
        for pid in $(ps | awk -v n="$proc" '$0 ~ n && !/awk/ && !/grep/ {print $1}'); do
            kill -9 "$pid" 2>/dev/null
        done
    done
    sleep 1
done

hciconfig hci0 down 2>/dev/null
rfkill block bluetooth 2>/dev/null

sleep 2
say "post-state:"
ps | grep -iE 'carplay|iap2|hicar|bluetooth|hcid|hciattach|adb-driver|mdnsd|hostapd|udhcpd' | grep -v grep

if pidof hostapd >/dev/null && pidof udhcpd >/dev/null; then
    say "OK — hostapd + udhcpd still running. AP is up."
else
    say "WARN — AP services not running, AP may be down"
fi

say "done. iPhone joining this AP will see a plain WiFi network."
say "reboot to restore stock behavior."
