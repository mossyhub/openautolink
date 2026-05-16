#pragma once

/*
 * provisioning — BLE GATT server that lets the OpenAutoLink companion app
 * send WiFi credentials (and optional AAOS-app static IP) on first run.
 */

#ifdef __cplusplus
extern "C" {
#endif

void provisioning_start(void);

#ifdef __cplusplus
}
#endif
