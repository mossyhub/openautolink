#pragma once

/*
 * transport_wifi — UDP relay between audio_relay rings and the AAOS app.
 *
 * Milestone 3+: connects to provisioned WiFi, does mDNS discovery for
 * `_oal-hfp._udp.local`, then starts two tasks (one per direction).
 */

#ifdef __cplusplus
extern "C" {
#endif

void transport_wifi_start(void);

#ifdef __cplusplus
}
#endif
