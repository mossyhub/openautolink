/*
 * hfp_helper: BT HFP HF + WiFi UDP relay for OpenAutoLink.
 *
 * Milestone 1: boot → NVS init → Bluedroid init → HFP-HF register →
 *              wait for phone pairing → accept SCO → log PCM frame count.
 */

#include <stdio.h>
#include <string.h>

#include "esp_log.h"
#include "esp_err.h"
#include "esp_system.h"
#include "esp_event.h"
#include "nvs_flash.h"

#include "esp_bt.h"
#include "esp_bt_main.h"
#include "esp_gap_bt_api.h"

#include "hfp_client.h"
#include "audio_relay.h"
#include "provisioning.h"
#include "transport_wifi.h"

static const char *TAG = "main";

/*
 * Decide initial mode based on whether NVS already has WiFi credentials.
 * Milestone 1: we ignore this and always go straight to HFP — provisioning
 * code is a stub.
 */
static bool nvs_has_wifi_creds(void)
{
    // TODO(M2): read NVS namespace "oal_hfp", key "wifi_ssid".
    return false;
}

void app_main(void)
{
    ESP_LOGI(TAG, "OpenAutoLink HFP Helper booting (firmware %s)", "0.1.0");

    /* NVS — needed by Bluedroid and WiFi for persistent storage. */
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ESP_ERROR_CHECK(nvs_flash_init());
    } else {
        ESP_ERROR_CHECK(err);
    }

    /* Event loop — used by WiFi, mDNS, and our HFP/BLE callbacks. */
    ESP_ERROR_CHECK(esp_event_loop_create_default());

    /* Audio ring buffers between SCO and (eventually) WiFi UDP. */
    audio_relay_init();

    /* BR/EDR-only controller config for Milestone 1; will become dual-mode
     * when BLE provisioning lands in M2. */
    esp_bt_controller_config_t bt_cfg = BT_CONTROLLER_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_bt_controller_init(&bt_cfg));
    ESP_ERROR_CHECK(esp_bt_controller_enable(ESP_BT_MODE_CLASSIC_BT));
    ESP_ERROR_CHECK(esp_bluedroid_init());
    ESP_ERROR_CHECK(esp_bluedroid_enable());

    /* Make us discoverable + connectable as "OpenAutoLink Helper". */
    ESP_ERROR_CHECK(esp_bt_gap_set_device_name("OpenAutoLink Helper"));
    ESP_ERROR_CHECK(esp_bt_gap_set_scan_mode(
        ESP_BT_CONNECTABLE, ESP_BT_GENERAL_DISCOVERABLE));

    /* Fixed SSP I/O capability for now — DisplayYesNo so the phone shows a
     * 6-digit code we can echo on serial for the user to confirm. */
    esp_bt_io_cap_t iocap = ESP_BT_IO_CAP_IO;
    esp_bt_gap_set_security_param(ESP_BT_SP_IOCAP_MODE, &iocap, sizeof(iocap));

    /* Register the HFP Hands-Free client + SCO PCM handler. */
    hfp_client_start();

    /* Milestones 2+ — stubs print "not implemented" today. */
    if (!nvs_has_wifi_creds()) {
        provisioning_start();   /* No-op in M1 */
    } else {
        transport_wifi_start(); /* No-op in M1 */
    }

    ESP_LOGI(TAG, "Boot complete. Discoverable as 'OpenAutoLink Helper'.");
    ESP_LOGI(TAG, "Pair from your phone's Bluetooth settings.");
}
