/* udp_discover.c — see udp_discover.h. */

#include "udp_discover.h"
#include "oal_log.h"

#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

/* Parse "OAL-CP-ANNOUNCE v=X ip=A.B.C.D port=N\n". Returns 1 on success. */
static int parse_announce(const char *buf, int len,
                          char *out_ip, int out_ip_cap, uint16_t *out_port) {
    if (len <= (int)sizeof(OAL_CP_ANNOUNCE_MAGIC) - 1) return 0;
    if (strncmp(buf, OAL_CP_ANNOUNCE_MAGIC, sizeof(OAL_CP_ANNOUNCE_MAGIC) - 1) != 0) return 0;

    /* Copy into NUL-terminated scratch so strstr/sscanf are safe. */
    char tmp[512];
    int n = len < (int)sizeof tmp - 1 ? len : (int)sizeof tmp - 1;
    memcpy(tmp, buf, n);
    tmp[n] = '\0';

    const char *ip_key = strstr(tmp, "ip=");
    const char *port_key = strstr(tmp, "port=");
    if (!ip_key || !port_key) return 0;

    char ip[64] = {0};
    unsigned port = 0;
    if (sscanf(ip_key, "ip=%63s", ip) != 1) return 0;
    if (sscanf(port_key, "port=%u", &port) != 1) return 0;
    if (port == 0 || port > 65535) return 0;

    /* Strip any trailing whitespace from ip (sscanf %s stops at WS but
     * be defensive). */
    char *sp = strchr(ip, ' ');  if (sp) *sp = '\0';
    char *nl = strchr(ip, '\n'); if (nl) *nl = '\0';

    struct in_addr dummy;
    if (inet_pton(AF_INET, ip, &dummy) != 1) return 0;

    int need = (int)strlen(ip) + 1;
    if (need > out_ip_cap) return 0;
    memcpy(out_ip, ip, need);
    *out_port = (uint16_t)port;
    return 1;
}

int udp_discover_wait_first(int listen_port,
                            char *out_ip, int out_ip_cap,
                            uint16_t *out_port,
                            volatile int *stop_flag) {
    int fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) {
        BLOG_E("discover: socket: %s", strerror(errno));
        return -2;
    }
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    /* SO_BROADCAST not needed on the receive side — we just need to accept
     * datagrams whose destination was the broadcast address. */

    struct sockaddr_in sa;
    memset(&sa, 0, sizeof sa);
    sa.sin_family      = AF_INET;
    sa.sin_addr.s_addr = htonl(INADDR_ANY);
    sa.sin_port        = htons((uint16_t)listen_port);
    if (bind(fd, (struct sockaddr *)&sa, sizeof sa) < 0) {
        BLOG_E("discover: bind :%d: %s", listen_port, strerror(errno));
        close(fd);
        return -2;
    }

    BLOG_I("discover: waiting for AAOS announce on UDP :%d", listen_port);

    char buf[1024];
    while (!*stop_flag) {
        struct pollfd pf = { .fd = fd, .events = POLLIN };
        int pr = poll(&pf, 1, 500);
        if (pr < 0) {
            if (errno == EINTR) continue;
            BLOG_E("discover: poll: %s", strerror(errno));
            close(fd);
            return -2;
        }
        if (pr == 0) continue;

        struct sockaddr_in src;
        socklen_t srclen = sizeof src;
        ssize_t n = recvfrom(fd, buf, sizeof buf, 0,
                             (struct sockaddr *)&src, &srclen);
        if (n <= 0) continue;

        char src_ip[INET_ADDRSTRLEN] = {0};
        inet_ntop(AF_INET, &src.sin_addr, src_ip, sizeof src_ip);

        if (parse_announce(buf, (int)n, out_ip, out_ip_cap, out_port)) {
            BLOG_I("discover: AAOS at %s:%u (from src %s)",
                   out_ip, (unsigned)*out_port, src_ip);
            close(fd);
            return 0;
        }
        BLOG_W("discover: ignored %zd-byte packet from %s (bad magic/format)", n, src_ip);
    }
    close(fd);
    return -1;
}
