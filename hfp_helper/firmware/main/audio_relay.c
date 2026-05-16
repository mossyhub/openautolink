/*
 * audio_relay.c — bounded ring buffers for SCO ↔ WiFi PCM relay.
 *
 * Each direction is a FreeRTOS StreamBuffer-equivalent built on a fixed-size
 * byte queue. We keep this simple: a 16 KB buffer per direction is enough for
 * ~500 ms of 16 kHz mono PCM at worst, which is far more than the live-audio
 * latency budget — long before we'd actually buffer that much, we drop.
 */

#include "audio_relay.h"

#include <string.h>

#include "freertos/FreeRTOS.h"
#include "freertos/stream_buffer.h"
#include "esp_log.h"

static const char *TAG = "audio_relay";

#define RING_BYTES (16 * 1024)

/* Trigger level of 320 bytes = one 20ms 16kHz mono frame. */
#define TRIGGER_BYTES 320

static StreamBufferHandle_t s_downlink;
static StreamBufferHandle_t s_uplink;

void audio_relay_init(void)
{
    s_downlink = xStreamBufferCreate(RING_BYTES, TRIGGER_BYTES);
    s_uplink   = xStreamBufferCreate(RING_BYTES, TRIGGER_BYTES);
    configASSERT(s_downlink);
    configASSERT(s_uplink);
    ESP_LOGI(TAG, "Ring buffers up (%d B each)", RING_BYTES);
}

/* Non-blocking push that drops oldest if full. */
static void push_dropping(StreamBufferHandle_t sb, const uint8_t *data, size_t len)
{
    /* Try to send with zero timeout. If short, drop bytes from the front
     * (oldest) until our payload fits, then send. */
    size_t sent = xStreamBufferSend(sb, data, len, 0);
    if (sent == len) return;

    /* Not enough room. Discard up to len-sent oldest bytes, then retry. */
    size_t to_drop = len - sent;
    uint8_t scratch[256];
    while (to_drop > 0) {
        size_t chunk = to_drop > sizeof(scratch) ? sizeof(scratch) : to_drop;
        size_t got = xStreamBufferReceive(sb, scratch, chunk, 0);
        if (got == 0) break;
        to_drop -= got;
    }
    xStreamBufferSend(sb, data + sent, len - sent, 0);
}

void audio_relay_push_downlink(const uint8_t *pcm, size_t len)
{
    push_dropping(s_downlink, pcm, len);
}

size_t audio_relay_pop_downlink(uint8_t *out, size_t cap, uint32_t timeout_ms)
{
    return xStreamBufferReceive(s_downlink, out, cap, pdMS_TO_TICKS(timeout_ms));
}

void audio_relay_push_uplink(const uint8_t *pcm, size_t len)
{
    push_dropping(s_uplink, pcm, len);
}

size_t audio_relay_pull_uplink(uint8_t *out, size_t cap)
{
    return xStreamBufferReceive(s_uplink, out, cap, 0);
}
