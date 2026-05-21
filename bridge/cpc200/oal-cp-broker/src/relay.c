/* relay.c — bidirectional TCP pipe between iPhone and AAOS app. */

#include "relay.h"
#include "oal_log.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

#define RELAY_BUF_BYTES (32 * 1024)

static int set_nonblock(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0) return -1;
    return fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

static void set_tcp_opts(int fd) {
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);
    setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &one, sizeof one);
}

static int dial_target(const char *host, uint16_t port) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) {
        BLOG_E("relay: socket(target): %s", strerror(errno));
        return -1;
    }
    struct sockaddr_in sa;
    memset(&sa, 0, sizeof sa);
    sa.sin_family = AF_INET;
    sa.sin_port   = htons(port);
    if (inet_pton(AF_INET, host, &sa.sin_addr) != 1) {
        BLOG_E("relay: bad target IP '%s'", host);
        close(fd);
        return -1;
    }
    if (connect(fd, (struct sockaddr *)&sa, sizeof sa) < 0) {
        BLOG_W("relay: connect %s:%u failed: %s", host, (unsigned)port, strerror(errno));
        close(fd);
        return -1;
    }
    set_tcp_opts(fd);
    return fd;
}

/* Drain readable side, write to other side. Returns 0 on clean EOF,
 * -1 on error. Both fds must be non-blocking. */
static int pump_once(int from_fd, int to_fd, uint64_t *bytes_out, const char *label) {
    uint8_t buf[RELAY_BUF_BYTES];
    ssize_t n = read(from_fd, buf, sizeof buf);
    if (n == 0) {
        BLOG_I("relay: %s EOF (total %llu B)", label, (unsigned long long)*bytes_out);
        return 0;
    }
    if (n < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) return 1;
        BLOG_W("relay: %s read: %s", label, strerror(errno));
        return -1;
    }
    /* Write may partial under back-pressure; loop to drain. */
    ssize_t off = 0;
    while (off < n) {
        ssize_t w = write(to_fd, buf + off, (size_t)(n - off));
        if (w < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                /* peer is slow; spin tiny — pragmatic for one-pair relay */
                struct pollfd p = { .fd = to_fd, .events = POLLOUT };
                poll(&p, 1, 50);
                continue;
            }
            if (errno == EINTR) continue;
            BLOG_W("relay: %s write: %s", label, strerror(errno));
            return -1;
        }
        off += w;
    }
    *bytes_out += (uint64_t)n;
    return 1;
}

static void pipe_pair(int iphone_fd, int aaos_fd, const char *iphone_addr) {
    set_nonblock(iphone_fd);
    set_nonblock(aaos_fd);
    set_tcp_opts(iphone_fd);

    uint64_t bytes_up = 0;   /* iphone -> aaos  */
    uint64_t bytes_dn = 0;   /* aaos   -> iphone */

    struct pollfd fds[2];
    fds[0].fd = iphone_fd; fds[0].events = POLLIN;
    fds[1].fd = aaos_fd;   fds[1].events = POLLIN;

    BLOG_I("relay: pair active iphone=%s <-> aaos", iphone_addr);

    while (1) {
        int rc = poll(fds, 2, 30000);
        if (rc < 0) {
            if (errno == EINTR) continue;
            BLOG_W("relay: poll: %s", strerror(errno));
            break;
        }
        if (rc == 0) continue;  /* idle keepalive; let TCP_KEEPALIVE handle dead peers */

        if (fds[0].revents & (POLLIN | POLLHUP | POLLERR)) {
            int r = pump_once(iphone_fd, aaos_fd, &bytes_up, "iphone->aaos");
            if (r <= 0) break;
        }
        if (fds[1].revents & (POLLIN | POLLHUP | POLLERR)) {
            int r = pump_once(aaos_fd, iphone_fd, &bytes_dn, "aaos->iphone");
            if (r <= 0) break;
        }
    }

    BLOG_I("relay: pair closed up=%llu dn=%llu",
           (unsigned long long)bytes_up, (unsigned long long)bytes_dn);
    close(iphone_fd);
    close(aaos_fd);
}

int relay_run(const relay_config_t *cfg, volatile int *stop_flag) {
    int srv = socket(AF_INET, SOCK_STREAM, 0);
    if (srv < 0) {
        BLOG_E("relay: socket(): %s", strerror(errno));
        return 1;
    }
    int one = 1;
    setsockopt(srv, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);

    struct sockaddr_in sa;
    memset(&sa, 0, sizeof sa);
    sa.sin_family = AF_INET;
    sa.sin_port   = htons(cfg->bind_port);
    if (!cfg->bind_addr || strcmp(cfg->bind_addr, "0.0.0.0") == 0) {
        sa.sin_addr.s_addr = htonl(INADDR_ANY);
    } else if (inet_pton(AF_INET, cfg->bind_addr, &sa.sin_addr) != 1) {
        BLOG_E("relay: bad bind addr '%s'", cfg->bind_addr);
        close(srv);
        return 1;
    }
    if (bind(srv, (struct sockaddr *)&sa, sizeof sa) < 0) {
        BLOG_E("relay: bind :%u: %s", (unsigned)cfg->bind_port, strerror(errno));
        close(srv);
        return 1;
    }
    if (listen(srv, 4) < 0) {
        BLOG_E("relay: listen: %s", strerror(errno));
        close(srv);
        return 1;
    }
    BLOG_I("relay: listening on %s:%u -> %s:%u",
           cfg->bind_addr ? cfg->bind_addr : "0.0.0.0",
           (unsigned)cfg->bind_port, cfg->target_host, (unsigned)cfg->target_port);

    /* Accept loop with periodic poll() so we can observe stop_flag. */
    while (!*stop_flag) {
        struct pollfd pf = { .fd = srv, .events = POLLIN };
        int pr = poll(&pf, 1, 500);
        if (pr < 0) {
            if (errno == EINTR) continue;
            BLOG_E("relay: accept poll: %s", strerror(errno));
            break;
        }
        if (pr == 0) continue;

        struct sockaddr_in caddr;
        socklen_t caddr_len = sizeof caddr;
        int cli = accept(srv, (struct sockaddr *)&caddr, &caddr_len);
        if (cli < 0) {
            if (errno == EINTR) continue;
            BLOG_W("relay: accept: %s", strerror(errno));
            continue;
        }
        char ipbuf[INET_ADDRSTRLEN] = {0};
        inet_ntop(AF_INET, &caddr.sin_addr, ipbuf, sizeof ipbuf);
        BLOG_I("relay: iphone connected from %s:%u",
               ipbuf, (unsigned)ntohs(caddr.sin_port));

        int tgt = dial_target(cfg->target_host, cfg->target_port);
        if (tgt < 0) {
            BLOG_W("relay: refusing %s: target %s:%u unreachable",
                   ipbuf, cfg->target_host, (unsigned)cfg->target_port);
            close(cli);
            continue;
        }
        pipe_pair(cli, tgt, ipbuf);  /* blocks until either side closes */
    }

    close(srv);
    BLOG_I("relay: accept loop exit");
    return 0;
}
