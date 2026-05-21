/* relay.{h,c} — TCP relay for the CPC200 CarPlay broker.
 *
 * Architecture:
 *   iPhone (joined CPC200 AP)
 *     │  TCP to 192.168.43.1:7000   (broker's AP address, advertised via mDNS)
 *     ▼
 *   Broker accept loop on 0.0.0.0:7000
 *     │  pipes bytes both ways
 *     ▼
 *   AAOS app at <target IP>:7000   (whatever DHCP gave it on the AP)
 *
 * Why a relay and not direct connection:
 *  - The AAOS app's IP on the CPC200 AP is DHCP-assigned and may change.
 *    Advertising the broker's stable gateway IP in mDNS means the user
 *    never has to know the AAOS IP.
 *  - The relay is a natural place to log/trace CarPlay-over-IP bytes for
 *    diagnostics without modifying the app or the phone.
 *
 * Single connection at a time is sufficient — Apple's wireless CarPlay
 * is one phone per session.
 */

#ifndef OAL_RELAY_H
#define OAL_RELAY_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    const char *bind_addr;      /* dotted-quad, NULL or "0.0.0.0" for all */
    uint16_t    bind_port;      /* host byte order, e.g. 7000 */
    const char *target_host;    /* dotted-quad, e.g. "192.168.43.50" */
    uint16_t    target_port;    /* host byte order, e.g. 7000 */
} relay_config_t;

/* Run accept loop until *stop_flag becomes non-zero. Returns 0 on clean
 * shutdown, non-zero on fatal bind error. */
int relay_run(const relay_config_t *cfg, volatile int *stop_flag);

#ifdef __cplusplus
}
#endif
#endif /* OAL_RELAY_H */
