#pragma once

/*
 * Bluetooth Hands-Free unit (HF) — pairs with the phone (which is the
 * Audio Gateway), accepts incoming SCO connections during phone calls,
 * and pushes PCM frames into the audio_relay ring buffers.
 */

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Register HFP HF profile and bind callbacks. Idempotent. */
void hfp_client_start(void);

/* Force a disconnect from the current phone (debug / forced re-pair). */
void hfp_client_disconnect(void);

/*
 * Returns true when an SCO link is currently active for a phone call.
 * Used by the LED task and by audio_relay to know whether to forward.
 */
bool hfp_client_sco_active(void);

#ifdef __cplusplus
}
#endif
