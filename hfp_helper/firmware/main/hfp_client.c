/*
 * hfp_client.c — Bluetooth HFP Hands-Free unit (HF role).
 *
 * Pairs with the phone (Audio Gateway) and handles call audio:
 *   - Listens for HFP service-level connection from the phone.
 *   - When the phone has a call, the AG opens an SCO link to us; we
 *     receive PCM via ESP_HF_CLIENT_INCOMING_DATA_EVT and write outbound
 *     PCM via esp_hf_client_outgoing_data_ready().
 *
 * Milestone 1: log SCO state transitions and PCM frame counts. The audio
 * itself is dropped on the floor — Milestone 3 wires audio_relay to UDP.
 */

#include "hfp_client.h"

#include <stdatomic.h>
#include <string.h>

#include "esp_log.h"
#include "esp_bt.h"
#include "esp_gap_bt_api.h"
#include "esp_hf_client_api.h"

#include "audio_relay.h"

static const char *TAG = "hfp_client";

static atomic_bool s_sco_active = false;
static atomic_uint s_rx_frame_count = 0;
static uint32_t s_last_logged_frame_count = 0;

/* === GAP callback — pairing / IO capability negotiation === */
static void bt_gap_cb(esp_bt_gap_cb_event_t event, esp_bt_gap_cb_param_t *param)
{
    switch (event) {
    case ESP_BT_GAP_AUTH_CMPL_EVT:
        if (param->auth_cmpl.stat == ESP_BT_STATUS_SUCCESS) {
            ESP_LOGI(TAG, "Auth success with %02x:%02x:%02x:%02x:%02x:%02x — name '%s'",
                     param->auth_cmpl.bda[0], param->auth_cmpl.bda[1], param->auth_cmpl.bda[2],
                     param->auth_cmpl.bda[3], param->auth_cmpl.bda[4], param->auth_cmpl.bda[5],
                     param->auth_cmpl.device_name);
        } else {
            ESP_LOGW(TAG, "Auth failed: status=%d", param->auth_cmpl.stat);
        }
        break;
    case ESP_BT_GAP_CFM_REQ_EVT:
        ESP_LOGI(TAG, "SSP confirm request — passkey on phone: %06lu (auto-accepting)",
                 (unsigned long)param->cfm_req.num_val);
        esp_bt_gap_ssp_confirm_reply(param->cfm_req.bda, true);
        break;
    case ESP_BT_GAP_KEY_NOTIF_EVT:
        ESP_LOGI(TAG, "SSP passkey notification — enter on phone: %06lu",
                 (unsigned long)param->key_notif.passkey);
        break;
    case ESP_BT_GAP_PIN_REQ_EVT:
        ESP_LOGI(TAG, "Legacy PIN request — sending '0000'");
        {
            esp_bt_pin_code_t pin = { '0', '0', '0', '0' };
            esp_bt_gap_pin_reply(param->pin_req.bda, true, 4, pin);
        }
        break;
    case ESP_BT_GAP_MODE_CHG_EVT:
        ESP_LOGD(TAG, "GAP mode change: %d", param->mode_chg.mode);
        break;
    default:
        ESP_LOGD(TAG, "GAP event %d", event);
        break;
    }
}

/* === HFP HF callback — connection state, audio state, incoming PCM === */
static void hf_client_cb(esp_hf_client_cb_event_t event, esp_hf_client_cb_param_t *param)
{
    switch (event) {
    case ESP_HF_CLIENT_CONNECTION_STATE_EVT:
        ESP_LOGI(TAG, "HFP connection state %d (peer feat=0x%x, chld=0x%x)",
                 param->conn_stat.state, param->conn_stat.peer_feat, param->conn_stat.chld_feat);
        if (param->conn_stat.state == ESP_HF_CLIENT_CONNECTION_STATE_CONNECTED) {
            ESP_LOGI(TAG, "HFP service-level connection up");
        } else if (param->conn_stat.state == ESP_HF_CLIENT_CONNECTION_STATE_DISCONNECTED) {
            ESP_LOGI(TAG, "HFP disconnected — ready for next pair");
            atomic_store(&s_sco_active, false);
        }
        break;

    case ESP_HF_CLIENT_AUDIO_STATE_EVT:
        switch (param->audio_stat.state) {
        case ESP_HF_CLIENT_AUDIO_STATE_CONNECTED:
        case ESP_HF_CLIENT_AUDIO_STATE_CONNECTED_MSBC:
            ESP_LOGI(TAG, "SCO audio CONNECTED (codec=%s)",
                     param->audio_stat.state == ESP_HF_CLIENT_AUDIO_STATE_CONNECTED_MSBC ? "mSBC" : "CVSD");
            atomic_store(&s_sco_active, true);
            atomic_store(&s_rx_frame_count, 0);
            s_last_logged_frame_count = 0;
            break;
        case ESP_HF_CLIENT_AUDIO_STATE_DISCONNECTED:
            ESP_LOGI(TAG, "SCO audio DISCONNECTED (received %lu PCM frames during this call)",
                     (unsigned long)atomic_load(&s_rx_frame_count));
            atomic_store(&s_sco_active, false);
            break;
        default:
            ESP_LOGD(TAG, "SCO state %d", param->audio_stat.state);
            break;
        }
        break;

    case ESP_HF_CLIENT_BVRA_EVT:
        ESP_LOGI(TAG, "Voice recognition: %d", param->bvra.value);
        break;

    case ESP_HF_CLIENT_RING_IND_EVT:
        ESP_LOGI(TAG, "RING from phone");
        break;

    case ESP_HF_CLIENT_CIND_CALL_EVT:
        ESP_LOGI(TAG, "Call indicator: %d", param->call.status);
        break;

    case ESP_HF_CLIENT_CIND_CALL_SETUP_EVT:
        ESP_LOGI(TAG, "Call setup indicator: %d", param->call_setup.status);
        break;

    case ESP_HF_CLIENT_CLIP_EVT:
        ESP_LOGI(TAG, "Caller ID: %s", param->clip.number ? param->clip.number : "(none)");
        break;

    default:
        ESP_LOGD(TAG, "HF event %d", event);
        break;
    }
}

/*
 * Incoming PCM callback — runs on the BT controller / Bluedroid task. Must
 * not block. Hand the data off to audio_relay; M1 just counts frames.
 */
static void hf_client_incoming_data_cb(const uint8_t *buf, uint32_t sz)
{
    audio_relay_push_downlink(buf, sz);

    uint32_t total = atomic_fetch_add(&s_rx_frame_count, 1) + 1;
    /* Log every ~1 s (50 frames at 20 ms each) so the serial console doesn't
     * drown. */
    if (total - s_last_logged_frame_count >= 50) {
        ESP_LOGI(TAG, "SCO RX: %lu frames (%lu bytes/frame)",
                 (unsigned long)total, (unsigned long)sz);
        s_last_logged_frame_count = total;
    }
}

/*
 * Outgoing PCM callback — Bluedroid asks for the next SCO frame to send
 * UPLINK (mic) to the phone. Pull from audio_relay; M1 returns silence.
 */
static uint32_t hf_client_outgoing_data_cb(uint8_t *buf, uint32_t sz)
{
    size_t pulled = audio_relay_pull_uplink(buf, sz);
    if (pulled < sz) {
        memset(buf + pulled, 0, sz - pulled);
    }
    return sz;
}

void hfp_client_start(void)
{
    ESP_ERROR_CHECK(esp_bt_gap_register_callback(bt_gap_cb));
    ESP_ERROR_CHECK(esp_hf_client_register_callback(hf_client_cb));
    ESP_ERROR_CHECK(esp_hf_client_init());
    ESP_ERROR_CHECK(esp_hf_client_register_data_callback(
        hf_client_incoming_data_cb, hf_client_outgoing_data_cb));

    ESP_LOGI(TAG, "HFP HF profile registered");
}

void hfp_client_disconnect(void)
{
    /* TODO: track remote BDA and call esp_hf_client_disconnect(bda). */
    ESP_LOGW(TAG, "hfp_client_disconnect: not implemented yet");
}

bool hfp_client_sco_active(void)
{
    return atomic_load(&s_sco_active);
}
