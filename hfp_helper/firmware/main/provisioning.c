/*
 * provisioning.c — STUB for Milestone 2.
 *
 * Real implementation will:
 *  1. Switch the BT controller to BTDM (dual-mode BR/EDR + BLE).
 *  2. Register a GATT server with the custom service UUID (see
 *     docs/design.md → BLE provisioning protocol).
 *  3. Accept writes for WiFi SSID / PSK / optional static IP.
 *  4. On commit, store to NVS and reboot into operating mode.
 *
 * Until M2, Milestone 1 firmware never invokes this — see main.c.
 */

#include "provisioning.h"
#include "esp_log.h"

static const char *TAG = "provisioning";

void provisioning_start(void)
{
    ESP_LOGW(TAG, "stub — Milestone 2 will implement BLE WiFi provisioning");
}
