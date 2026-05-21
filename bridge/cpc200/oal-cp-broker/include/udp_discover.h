/* udp_discover.{h,c} — passive UDP listener that learns the AAOS app's
 * current IP and port from periodic broadcast announces. Solves the GM
 * car AP random-subnet problem: the AAOS app advertises wherever it ended
 * up after DHCP, the broker picks it up, no static config needed.
 *
 * Wire format (single UDP datagram, ASCII):
 *   "OAL-CP-ANNOUNCE v=<ver> ip=<dotted> port=<n>\n"
 * Port: UDP/5278 (broadcast destination 255.255.255.255).
 *
 * Mirrors the pattern PhoneDiscovery uses for AA (UDP broadcast from the
 * peer that knows its own IP), but inverted: AAOS announces, broker listens.
 */

#ifndef OAL_UDP_DISCOVER_H
#define OAL_UDP_DISCOVER_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define OAL_CP_ANNOUNCE_PORT 5278
#define OAL_CP_ANNOUNCE_MAGIC "OAL-CP-ANNOUNCE"

/* Block until a valid announce packet is received or *stop_flag != 0.
 * On success, fills `out_ip` (caller-supplied buffer of >= INET_ADDRSTRLEN
 * bytes) and `out_port`, returns 0. Returns -1 on stop, -2 on bind error. */
int udp_discover_wait_first(int listen_port,
                            char *out_ip, int out_ip_cap,
                            uint16_t *out_port,
                            volatile int *stop_flag);

#ifdef __cplusplus
}
#endif
#endif
