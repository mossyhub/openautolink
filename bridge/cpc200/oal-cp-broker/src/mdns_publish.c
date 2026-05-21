/* mdns_publish.c — minimal raw-UDP mDNS responder for CarPlay.
 *
 * Bind 5353/udp on the wildcard address, join 224.0.0.251, and emit a
 * single canonical response packet (PTR + SRV + TXT + A) on:
 *   - startup (3 unsolicited announcements, 1s apart, RFC 6762 §8.3)
 *   - every 60s thereafter as a refresh
 *   - any inbound packet that mentions our service or hostname
 *
 * We intentionally keep parsing minimal: incoming packets are scanned for
 * a case-insensitive substring match against our regtype, instance name
 * or hostname. If found, we resend the canonical packet. This is enough
 * for iOS Bonjour browsers to see the service.
 */

#include "mdns_publish.h"
#include "oal_log.h"

#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#define MDNS_PORT       5353
#define MDNS_GROUP_S    "224.0.0.251"
#define DNS_TYPE_A      1
#define DNS_TYPE_PTR    12
#define DNS_TYPE_TXT    16
#define DNS_TYPE_SRV    33
#define DNS_CLASS_IN    1
#define DNS_CLASS_CF    0x8001   /* cache-flush bit set on records we own */
#define TTL_HOST        120
#define TTL_OTHER       4500

/* ---------- packet writers ---------- */

static int wname(uint8_t *p, const char *dotted) {
    /* Encode "a.b.c" as <1>a<1>b<1>c<0>. */
    int n = 0;
    const char *s = dotted;
    while (*s) {
        const char *dot = strchr(s, '.');
        int len = dot ? (int)(dot - s) : (int)strlen(s);
        if (len <= 0 || len > 63) return -1;
        p[n++] = (uint8_t)len;
        memcpy(p + n, s, len);
        n += len;
        if (!dot) break;
        s = dot + 1;
    }
    p[n++] = 0;
    return n;
}

static int wu16(uint8_t *p, uint16_t v) { p[0] = v >> 8; p[1] = v & 0xff; return 2; }
static int wu32(uint8_t *p, uint32_t v) { p[0] = v >> 24; p[1] = v >> 16; p[2] = v >> 8; p[3] = v & 0xff; return 4; }

/* Append a TXT entry as <len><bytes>. Returns bytes written or -1. */
static int wtxt_entry(uint8_t *p, int *cap, const char *kv) {
    int len = (int)strlen(kv);
    if (len > 255 || *cap < len + 1) return -1;
    p[0] = (uint8_t)len;
    memcpy(p + 1, kv, len);
    *cap -= len + 1;
    return len + 1;
}

/* Build the canonical response packet. Returns total length or -1. */
static int build_packet(uint8_t *buf, int cap,
                        const mdns_publish_config_t *cfg,
                        uint32_t ipv4_be)
{
    char service_full[128];    /* e.g. _carplay._tcp.local */
    char instance_full[256];   /* e.g. OpenAutoLink._carplay._tcp.local */
    char host_full[128];       /* e.g. openautolink.local */

    snprintf(service_full,  sizeof(service_full),  "%s.local", cfg->regtype);
    snprintf(instance_full, sizeof(instance_full), "%s.%s.local", cfg->service_name, cfg->regtype);
    snprintf(host_full,     sizeof(host_full),     "%s.local", cfg->hostname);

    if (cap < 12) return -1;
    int n = 0;
    /* Header: txid=0, flags=0x8400 (response + AA), qd=0, an=4, ns=0, ar=0. */
    n += wu16(buf + n, 0x0000);
    n += wu16(buf + n, 0x8400);
    n += wu16(buf + n, 0);     /* qdcount */
    n += wu16(buf + n, 5);     /* ancount: meta-PTR + PTR + SRV + TXT + A */
    n += wu16(buf + n, 0);
    n += wu16(buf + n, 0);

    /* --- meta-PTR: _services._dns-sd._udp.local IN PTR service_full ---
     * Lets Bonjour browsers that enumerate service types discover us. */
    int r = wname(buf + n, "_services._dns-sd._udp.local"); if (r < 0) return -1; n += r;
    n += wu16(buf + n, DNS_TYPE_PTR);
    n += wu16(buf + n, DNS_CLASS_IN);
    n += wu32(buf + n, TTL_OTHER);
    int rdlen_off = n; n += 2;
    int rd_start = n;
    r = wname(buf + n, service_full); if (r < 0) return -1; n += r;
    wu16(buf + rdlen_off, (uint16_t)(n - rd_start));

    /* --- PTR: service_full IN PTR instance_full --- */
    r = wname(buf + n, service_full); if (r < 0) return -1; n += r;
    n += wu16(buf + n, DNS_TYPE_PTR);
    n += wu16(buf + n, DNS_CLASS_IN);
    n += wu32(buf + n, TTL_OTHER);
    rdlen_off = n; n += 2;
    rd_start = n;
    r = wname(buf + n, instance_full); if (r < 0) return -1; n += r;
    wu16(buf + rdlen_off, (uint16_t)(n - rd_start));

    /* --- SRV: instance_full IN SRV 0 0 port host_full --- */
    r = wname(buf + n, instance_full); if (r < 0) return -1; n += r;
    n += wu16(buf + n, DNS_TYPE_SRV);
    n += wu16(buf + n, DNS_CLASS_CF);
    n += wu32(buf + n, TTL_HOST);
    rdlen_off = n; n += 2;
    rd_start = n;
    n += wu16(buf + n, 0);              /* priority */
    n += wu16(buf + n, 0);              /* weight */
    n += wu16(buf + n, cfg->port);      /* port */
    r = wname(buf + n, host_full); if (r < 0) return -1; n += r;
    wu16(buf + rdlen_off, (uint16_t)(n - rd_start));

    /* --- TXT: instance_full IN TXT {kv...} --- */
    r = wname(buf + n, instance_full); if (r < 0) return -1; n += r;
    n += wu16(buf + n, DNS_TYPE_TXT);
    n += wu16(buf + n, DNS_CLASS_CF);
    n += wu32(buf + n, TTL_OTHER);
    rdlen_off = n; n += 2;
    rd_start = n;
    int txt_cap = cap - n;
    char kv[300];
    snprintf(kv, sizeof(kv), "deviceid=%s", cfg->deviceid ? cfg->deviceid : "");
    r = wtxt_entry(buf + n, &txt_cap, kv); if (r < 0) return -1; n += r;
    snprintf(kv, sizeof(kv), "features=%s", cfg->features ? cfg->features : "0x0");
    r = wtxt_entry(buf + n, &txt_cap, kv); if (r < 0) return -1; n += r;
    snprintf(kv, sizeof(kv), "model=%s", cfg->model ? cfg->model : "Unknown,1");
    r = wtxt_entry(buf + n, &txt_cap, kv); if (r < 0) return -1; n += r;
    snprintf(kv, sizeof(kv), "srcvers=%s", cfg->srcvers ? cfg->srcvers : "1.0");
    r = wtxt_entry(buf + n, &txt_cap, kv); if (r < 0) return -1; n += r;
    snprintf(kv, sizeof(kv), "vv=%s", cfg->vv ? cfg->vv : "2");
    r = wtxt_entry(buf + n, &txt_cap, kv); if (r < 0) return -1; n += r;
    /* TXT must be non-empty; if list is empty add a single 0 byte. */
    if (n == rd_start) buf[n++] = 0;
    wu16(buf + rdlen_off, (uint16_t)(n - rd_start));

    /* --- A: host_full IN A ipv4 --- */
    r = wname(buf + n, host_full); if (r < 0) return -1; n += r;
    n += wu16(buf + n, DNS_TYPE_A);
    n += wu16(buf + n, DNS_CLASS_CF);
    n += wu32(buf + n, TTL_HOST);
    n += wu16(buf + n, 4);              /* rdlen */
    /* ipv4 already in network byte order. */
    memcpy(buf + n, &ipv4_be, 4); n += 4;

    return n;
}

/* Lower-case substring search. */
static int contains_ci(const uint8_t *hay, int n, const char *needle) {
    int m = (int)strlen(needle);
    if (m == 0 || n < m) return 0;
    for (int i = 0; i <= n - m; i++) {
        int j = 0;
        while (j < m) {
            uint8_t a = hay[i + j];
            uint8_t b = (uint8_t)needle[j];
            if (a >= 'A' && a <= 'Z') a = (uint8_t)(a + 32);
            if (b >= 'A' && b <= 'Z') b = (uint8_t)(b + 32);
            if (a != b) break;
            j++;
        }
        if (j == m) return 1;
    }
    return 0;
}

int mdns_publish_run(const mdns_publish_config_t *cfg, volatile int *stop_flag) {
    if (!cfg || !cfg->service_name || !cfg->regtype || !cfg->hostname || !cfg->ipv4) {
        BLOG_E("mdns: config missing required fields");
        return 1;
    }

    uint32_t ipv4_be = 0;
    if (inet_pton(AF_INET, cfg->ipv4, &ipv4_be) != 1) {
        BLOG_E("mdns: invalid ipv4 '%s'", cfg->ipv4);
        return 1;
    }

    int s = socket(AF_INET, SOCK_DGRAM, 0);
    if (s < 0) { BLOG_E("mdns: socket: %s", strerror(errno)); return 1; }

    int yes = 1;
    setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));
#ifdef SO_REUSEPORT
    setsockopt(s, SOL_SOCKET, SO_REUSEPORT, &yes, sizeof(yes));
#endif

    struct sockaddr_in bind_addr = {0};
    bind_addr.sin_family = AF_INET;
    bind_addr.sin_addr.s_addr = htonl(INADDR_ANY);
    bind_addr.sin_port = htons(MDNS_PORT);
    if (bind(s, (struct sockaddr *)&bind_addr, sizeof(bind_addr)) < 0) {
        BLOG_E("mdns: bind 5353: %s (is mdnsd still running?)", strerror(errno));
        close(s);
        return 1;
    }

    struct ip_mreq mreq = {0};
    mreq.imr_multiaddr.s_addr = inet_addr(MDNS_GROUP_S);
    mreq.imr_interface.s_addr = ipv4_be;   /* join on our chosen iface */
    if (setsockopt(s, IPPROTO_IP, IP_ADD_MEMBERSHIP, &mreq, sizeof(mreq)) < 0) {
        BLOG_E("mdns: IP_ADD_MEMBERSHIP: %s", strerror(errno));
        close(s);
        return 1;
    }

    /* Multicast out the chosen interface, ttl=255 (mDNS), no loopback. */
    struct in_addr iface_addr = { ipv4_be };
    setsockopt(s, IPPROTO_IP, IP_MULTICAST_IF, &iface_addr, sizeof(iface_addr));
    unsigned char ttl = 255;
    setsockopt(s, IPPROTO_IP, IP_MULTICAST_TTL, &ttl, sizeof(ttl));
    unsigned char loop = 0;
    setsockopt(s, IPPROTO_IP, IP_MULTICAST_LOOP, &loop, sizeof(loop));

    uint8_t pkt[1500];
    int pkt_len = build_packet(pkt, sizeof(pkt), cfg, ipv4_be);
    if (pkt_len <= 0) {
        BLOG_E("mdns: failed to build packet (len=%d)", pkt_len);
        close(s);
        return 1;
    }

    struct sockaddr_in dst = {0};
    dst.sin_family = AF_INET;
    dst.sin_addr.s_addr = inet_addr(MDNS_GROUP_S);
    dst.sin_port = htons(MDNS_PORT);

    BLOG_I("mdns: advertising %s.%s.local on %s:%u (pkt=%dB)",
           cfg->service_name, cfg->regtype, cfg->ipv4, cfg->port, pkt_len);

    /* Unsolicited announcements: 3 in quick succession. */
    for (int i = 0; i < 3; i++) {
        if (sendto(s, pkt, pkt_len, 0, (struct sockaddr *)&dst, sizeof(dst)) < 0) {
            BLOG_E("mdns: sendto: %s", strerror(errno));
        } else {
            BLOG_I("mdns: announce %d/3", i + 1);
        }
        struct timespec ts = { 1, 0 };
        nanosleep(&ts, NULL);
    }

    /* Pre-compute substrings to look for in incoming packets. */
    char needle_type[64];
    snprintf(needle_type, sizeof(needle_type), "%s", cfg->regtype);  /* "_carplay._tcp" */

    time_t last_announce = time(NULL);

    while (!(stop_flag && *stop_flag)) {
        struct pollfd pfd = { s, POLLIN, 0 };
        int pr = poll(&pfd, 1, 1000);
        if (pr < 0) {
            if (errno == EINTR) continue;
            BLOG_E("mdns: poll: %s", strerror(errno));
            break;
        }
        if (pr > 0 && (pfd.revents & POLLIN)) {
            uint8_t rx[1500];
            struct sockaddr_in src;
            socklen_t slen = sizeof(src);
            int rn = recvfrom(s, rx, sizeof(rx), 0, (struct sockaddr *)&src, &slen);
            if (rn > 12) {
                if (src.sin_addr.s_addr == ipv4_be) continue;
                uint16_t flags    = (uint16_t)((rx[2] << 8) | rx[3]);
                uint16_t qdcount  = (uint16_t)((rx[4] << 8) | rx[5]);
                int is_query = (flags & 0x8000) == 0 && qdcount > 0;
                int match_type  = contains_ci(rx, rn, needle_type);
                int match_host  = contains_ci(rx, rn, cfg->hostname);
                int match_meta  = contains_ci(rx, rn, "_services");
                BLOG_I("mdns: rx %dB from %s:%u q=%d qd=%u type=%d host=%d meta=%d",
                       rn, inet_ntoa(src.sin_addr), ntohs(src.sin_port),
                       is_query, (unsigned)qdcount, match_type, match_host, match_meta);
                if (is_query && (match_type || match_host || match_meta)) {
                    sendto(s, pkt, pkt_len, 0, (struct sockaddr *)&dst, sizeof(dst));
                    BLOG_I("mdns: replied to %s:%u",
                           inet_ntoa(src.sin_addr), ntohs(src.sin_port));
                }
            }
        }
        time_t now = time(NULL);
        if (now - last_announce >= 60) {
            sendto(s, pkt, pkt_len, 0, (struct sockaddr *)&dst, sizeof(dst));
            last_announce = now;
        }
    }

    close(s);
    BLOG_I("mdns: stopped");
    return 0;
}
