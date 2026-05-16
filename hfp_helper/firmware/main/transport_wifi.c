/*
 * transport_wifi.c — STUB for Milestone 3+.
 *
 * Real implementation will:
 *  1. Read WiFi creds from NVS (key `oal_hfp/wifi_ssid` + `wifi_psk`).
 *  2. esp_wifi_init + station connect.
 *  3. mdns_init + query `_oal-hfp._udp.local`.
 *  4. Spawn two tasks:
 *      - downlink_tx: pop from audio_relay downlink, wrap in oal_hfp_packet,
 *                     sendto() AAOS app at port 5278.
 *      - uplink_rx:   recvfrom() port 5278, push payload into uplink ring.
 *  5. Keepalive every 1s and reconnect on socket errors.
 */

#include "transport_wifi.h"
#include "esp_log.h"

static const char *TAG = "transport_wifi";

void transport_wifi_start(void)
{
    ESP_LOGW(TAG, "stub — Milestone 3+ will implement UDP relay");
}
