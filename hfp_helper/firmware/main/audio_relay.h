#pragma once

/*
 * audio_relay — two ring buffers between the SCO PCM callbacks and
 * the WiFi UDP transport.
 *
 *   downlink: hfp_client RX → ring → transport_wifi UDP send
 *   uplink:   transport_wifi UDP recv → ring → hfp_client TX
 *
 * Both rings are bounded; oldest frames drop when full (live audio,
 * keeping latest is more valuable than retaining stale).
 */

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

void   audio_relay_init(void);

/* Producer: BT controller callback. Non-blocking; drops oldest if full. */
void   audio_relay_push_downlink(const uint8_t *pcm, size_t len);

/* Consumer: WiFi UDP send task. Blocks up to timeout_ms for data. */
size_t audio_relay_pop_downlink(uint8_t *out, size_t cap, uint32_t timeout_ms);

/* Producer: WiFi UDP recv task. Non-blocking; drops oldest if full. */
void   audio_relay_push_uplink(const uint8_t *pcm, size_t len);

/* Consumer: BT outgoing data callback. Non-blocking; returns bytes filled. */
size_t audio_relay_pull_uplink(uint8_t *out, size_t cap);

#ifdef __cplusplus
}
#endif
