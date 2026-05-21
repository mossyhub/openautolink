/* mdns_publish.{h,c} — minimal raw-UDP mDNS responder.
 *
 * Bonjour-compatible enough to answer iOS queries for the configured
 * `_<regtype>._tcp.local.` service. No libdns_sd / mdnsd dependency —
 * we bind 5353/udp ourselves and emit PTR + SRV + TXT + A in a single
 * response packet. Caller must ensure no other mDNS daemon is bound
 * (kill /usr/sbin/mdnsd before starting).
 */

#ifndef OAL_MDNS_PUBLISH_H
#define OAL_MDNS_PUBLISH_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    const char *service_name;   /* e.g. "OpenAutoLink" */
    const char *regtype;        /* e.g. "_carplay._tcp" */
    uint16_t    port;           /* host byte order, e.g. 7000 */
    const char *deviceid;       /* TXT: 12-hex MAC, e.g. "0011223344AA" */
    const char *model;          /* TXT: e.g. "OpenAutoLink,1" */
    const char *features;       /* TXT: e.g. "0x44" */
    const char *srcvers;        /* TXT: e.g. "320.17" (AirPlay version) */
    const char *vv;             /* TXT: e.g. "2" (protocol version) */
    const char *hostname;       /* A-record name (without .local), e.g. "openautolink" */
    const char *ipv4;           /* dotted-quad string, e.g. "192.168.43.1" */
} mdns_publish_config_t;

/* Run forever until stop flag is set or SIGTERM/SIGINT is received.
 * Returns 0 on clean shutdown, non-zero on registration failure. */
int mdns_publish_run(const mdns_publish_config_t *cfg, volatile int *stop_flag);

#ifdef __cplusplus
}
#endif

#endif /* OAL_MDNS_PUBLISH_H */
