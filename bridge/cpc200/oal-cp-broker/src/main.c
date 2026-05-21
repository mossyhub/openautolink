/* main.c — CPC200 Wireless CarPlay Broker entry point.
 *
 * Current state:
 *   --probe        : print version + diagnostics, exit 0.
 *   --mdns-only    : start mDNSResponder (mdnsd) if needed and advertise
 *                    `_carplay._tcp` on --port until SIGTERM/SIGINT.
 *   (no args)      : not implemented yet — exits with 77 (EX_NOPERM-ish).
 *
 * Real work still to land:
 *   1. open hci0, register CarPlay SDP profile on RFCOMM ch 8
 *   2. accept iPhone RFCOMM connection
 *   3. run iAP2 link-layer SYN/ACK
 *   4. relay MFi auth challenges to 127.0.0.1:5290
 *   5. respond to StartCarPlay with AAOS app's IP
 */

#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/utsname.h>
#include <sys/wait.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <pthread.h>

#include "mdns_publish.h"
#include "oal_log.h"
#include "relay.h"
#include "udp_discover.h"

/* Pick the local IPv4 the kernel would use to reach `target` (dotted-quad).
 * Uses a UDP socket + connect() — no packets are sent. Returns malloc'd
 * dotted-quad string, or NULL on failure. */
static char *autopick_local_ip(const char *target) {
    if (!target) return NULL;
    int fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) return NULL;
    struct sockaddr_in dst;
    memset(&dst, 0, sizeof dst);
    dst.sin_family = AF_INET;
    dst.sin_port   = htons(9);
    if (inet_pton(AF_INET, target, &dst.sin_addr) != 1) { close(fd); return NULL; }
    if (connect(fd, (struct sockaddr *)&dst, sizeof dst) < 0) { close(fd); return NULL; }
    struct sockaddr_in src;
    socklen_t slen = sizeof src;
    if (getsockname(fd, (struct sockaddr *)&src, &slen) < 0) { close(fd); return NULL; }
    close(fd);
    char buf[INET_ADDRSTRLEN] = {0};
    if (!inet_ntop(AF_INET, &src.sin_addr, buf, sizeof buf)) return NULL;
    return strdup(buf);
}

static const char *USAGE =
    "Usage: oal-cp-broker [options]\n"
    "  --probe           Print version + diagnostics, exit.\n"
    "  --mdns-only       Advertise _carplay._tcp; do not run relay.\n"
    "  --relay-only      Run TCP relay; do not advertise mDNS.\n"
    "  (no mode flag)    Run BOTH mDNS and TCP relay (recommended).\n"
    "  --advertise IP    IP to publish in mDNS. Default: auto-pick the local IP\n"
    "                    on the interface that reaches --target.\n"
    "  --target IP|auto  IP of the AAOS app to relay to. REQUIRED for relay modes.\n"
    "                    Use 'auto' to wait for a UDP-broadcast announce from the\n"
    "                    AAOS app on port 5278 (GM AP random subnets).\n"
    "  --port N          TCP port for both listen and target (default 5000, RTSP entry).\n"
    "  --mfi HOST:P      MFi auth daemon address (default 127.0.0.1:5290).\n"
    "  --name NAME       mDNS service name (default OpenAutoLink).\n"
    "  --deviceid HEX12  TXT deviceid (default 001122334455).\n"
    "  --help            Show this help.\n";

typedef struct {
    const char *advertise_ip;   /* what mDNS publishes (broker AP IP) */
    const char *target_ip;      /* what relay forwards to (AAOS app IP) */
    int         target_port;    /* same port for listen + target by default */
    const char *mfi_host;
    int         mfi_port;
    const char *service_name;
    const char *deviceid;
    int         probe_only;
    int         mdns_only;
    int         relay_only;
} broker_config_t;

static void default_config(broker_config_t *c) {
    /* No subnet defaults: the real GM car AP randomizes the subnet on every
     * boot. advertise_ip is auto-picked from the outbound route to target_ip
     * if left NULL. target_ip must be supplied by --target (or, in a future
     * revision, learned via UDP broadcast discovery mirroring AA's pattern). */
    c->advertise_ip = NULL;
    c->target_ip    = NULL;
    /* Port 5000 = RTSP/pair-setup entry point per wireless_carplay.md.
     * Port 7000 is the video stream that opens *after* pair-setup. */
    c->target_port  = 5000;
    c->mfi_host     = "127.0.0.1";
    c->mfi_port     = 5290;
    c->service_name = "OpenAutoLink";
    c->deviceid     = "001122334455";
    c->probe_only   = 0;
    c->mdns_only    = 0;
    c->relay_only   = 0;
}

static int parse_args(int argc, char **argv, broker_config_t *c) {
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--probe") == 0) {
            c->probe_only = 1;
        } else if (strcmp(argv[i], "--mdns-only") == 0) {
            c->mdns_only = 1;
        } else if (strcmp(argv[i], "--relay-only") == 0) {
            c->relay_only = 1;
        } else if (strcmp(argv[i], "--advertise") == 0 && i + 1 < argc) {
            c->advertise_ip = argv[++i];
        } else if (strcmp(argv[i], "--target") == 0 && i + 1 < argc) {
            c->target_ip = argv[++i];
        } else if (strcmp(argv[i], "--port") == 0 && i + 1 < argc) {
            c->target_port = atoi(argv[++i]);
        } else if (strcmp(argv[i], "--name") == 0 && i + 1 < argc) {
            c->service_name = argv[++i];
        } else if (strcmp(argv[i], "--deviceid") == 0 && i + 1 < argc) {
            c->deviceid = argv[++i];
        } else if (strcmp(argv[i], "--mfi") == 0 && i + 1 < argc) {
            char *colon = strchr(argv[++i], ':');
            if (colon) {
                *colon = '\0';
                c->mfi_host = argv[i];
                c->mfi_port = atoi(colon + 1);
            }
        } else if (strcmp(argv[i], "--help") == 0 || strcmp(argv[i], "-h") == 0) {
            fputs(USAGE, stdout);
            return 1;
        } else {
            fprintf(stderr, "unknown arg: %s\n%s", argv[i], USAGE);
            return -1;
        }
    }
    return 0;
}

static void run_probe(const broker_config_t *c) {
    struct utsname u;
    if (uname(&u) == 0) {
        BLOG_I("host: %s %s %s", u.nodename, u.sysname, u.release);
    }
    BLOG_I("advertise IP:    %s", c->advertise_ip ? c->advertise_ip : "(auto)");
    BLOG_I("target AAOS app: %s:%d", c->target_ip ? c->target_ip : "(unset)", c->target_port);
    BLOG_I("MFi daemon:      %s:%d", c->mfi_host, c->mfi_port);
    BLOG_I("probe OK");
}

/* Stop the system mDNS daemon so we can bind 5353/udp ourselves. */
static void stop_system_mdnsd(void) {
    if (access("/var/run/mdnsd", F_OK) != 0) {
        BLOG_I("mdnsd not running, nothing to stop");
        return;
    }
    BLOG_I("stopping system mdnsd (we run our own mDNS responder)");
    pid_t pid = fork();
    if (pid == 0) {
        execl("/usr/bin/killall", "killall", "mdnsd", (char *)NULL);
        execl("/bin/killall",     "killall", "mdnsd", (char *)NULL);
        _exit(127);
    }
    if (pid > 0) waitpid(pid, NULL, 0);
    /* Give it a moment to release the port. */
    for (int i = 0; i < 20 && access("/var/run/mdnsd", F_OK) == 0; i++) {
        usleep(100 * 1000);
    }
}

static volatile int g_stop = 0;

static void on_signal(int sig) {
    (void)sig;
    g_stop = 1;
}

static void install_signal_handlers(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = on_signal;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT,  &sa, NULL);
    /* Ignore SIGPIPE so a closed relay peer never kills us. */
    signal(SIGPIPE, SIG_IGN);
}

static void build_mdns_config(const broker_config_t *c, mdns_publish_config_t *pc) {
    memset(pc, 0, sizeof *pc);
    pc->service_name = c->service_name;
    pc->regtype      = "_carplay._tcp";
    pc->port         = (uint16_t)c->target_port;
    pc->deviceid     = c->deviceid;
    /* Values per external/carlink_native_ref/.../wireless_carplay.md mDNS section.
     * Real CarPlay receivers publish model=A15W, features=0x5A7FFFFH, srcvers=320.17, vv=2. */
    pc->model        = "A15W";
    pc->features     = "0x5A7FFFFH";
    pc->srcvers      = "320.17";
    pc->vv           = "2";
    pc->hostname     = "openautolink";
    pc->ipv4         = c->advertise_ip;
}

static int run_mdns_only(const broker_config_t *c) {
    stop_system_mdnsd();
    install_signal_handlers();
    mdns_publish_config_t pc;
    build_mdns_config(c, &pc);
    BLOG_I("publishing _carplay._tcp as '%s' -> %s:%u",
           pc.service_name, pc.ipv4, (unsigned)pc.port);
    int rc = mdns_publish_run(&pc, &g_stop);
    BLOG_I("mDNS responder exited rc=%d", rc);
    return rc == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}

static int run_relay_only(const broker_config_t *c) {
    install_signal_handlers();
    relay_config_t rc;
    memset(&rc, 0, sizeof rc);
    rc.bind_addr   = "0.0.0.0";
    rc.bind_port   = (uint16_t)c->target_port;
    rc.target_host = c->target_ip;
    rc.target_port = (uint16_t)c->target_port;
    int r = relay_run(&rc, &g_stop);
    return r == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}

static void *mdns_thread_fn(void *arg) {
    mdns_publish_config_t *pc = (mdns_publish_config_t *)arg;
    int rc = mdns_publish_run(pc, &g_stop);
    BLOG_I("mDNS thread exit rc=%d", rc);
    return NULL;
}

static int run_full(const broker_config_t *c) {
    stop_system_mdnsd();
    install_signal_handlers();

    mdns_publish_config_t pc;
    build_mdns_config(c, &pc);
    BLOG_I("publishing _carplay._tcp as '%s' -> %s:%u",
           pc.service_name, pc.ipv4, (unsigned)pc.port);

    pthread_t mdns_tid;
    if (pthread_create(&mdns_tid, NULL, mdns_thread_fn, &pc) != 0) {
        BLOG_E("pthread_create(mdns) failed: %s", strerror(errno));
        return EXIT_FAILURE;
    }

    relay_config_t rc;
    memset(&rc, 0, sizeof rc);
    rc.bind_addr   = "0.0.0.0";
    rc.bind_port   = (uint16_t)c->target_port;
    rc.target_host = c->target_ip;
    rc.target_port = (uint16_t)c->target_port;
    int rrc = relay_run(&rc, &g_stop);

    g_stop = 1;
    pthread_join(mdns_tid, NULL);
    return rrc == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}

int main(int argc, char **argv) {
    broker_config_t cfg;
    default_config(&cfg);

    int parse = parse_args(argc, argv, &cfg);
    if (parse < 0) return EXIT_FAILURE;
    if (parse > 0) return EXIT_SUCCESS;

    if (cfg.probe_only) {
        BLOG_I("oal-cp-broker probe");
        run_probe(&cfg);
        return EXIT_SUCCESS;
    }

    /* Relay modes require --target. GM car AP randomizes its subnet on
     * every boot — we refuse to bake in a stale default. */
    int needs_target = !cfg.mdns_only;
    if (needs_target && !cfg.target_ip) {
        fprintf(stderr, "--target IP is required for relay modes (no default: GM AP randomizes subnet)\n%s", USAGE);
        return EXIT_FAILURE;
    }

    /* --target auto: passive-listen for the AAOS app's UDP broadcast. */
    static char auto_ip_buf[64];
    if (cfg.target_ip && strcmp(cfg.target_ip, "auto") == 0) {
        install_signal_handlers();
        uint16_t learned_port = 0;
        int rc = udp_discover_wait_first(OAL_CP_ANNOUNCE_PORT,
                                         auto_ip_buf, sizeof auto_ip_buf,
                                         &learned_port, &g_stop);
        if (rc != 0) {
            BLOG_E("discover: gave up (rc=%d, stop=%d)", rc, g_stop);
            return EXIT_FAILURE;
        }
        cfg.target_ip   = auto_ip_buf;
        cfg.target_port = (int)learned_port;
    }

    if (!cfg.advertise_ip) {
        const char *seed = cfg.target_ip ? cfg.target_ip : "8.8.8.8";
        char *picked = autopick_local_ip(seed);
        if (!picked) {
            fprintf(stderr, "could not auto-pick --advertise IP (no route to %s). Pass --advertise explicitly.\n", seed);
            return EXIT_FAILURE;
        }
        cfg.advertise_ip = picked;
        BLOG_I("auto-picked advertise IP %s (route to %s)", cfg.advertise_ip, seed);
    }
    if (cfg.mdns_only && !cfg.target_ip) cfg.target_ip = cfg.advertise_ip;

    BLOG_I("oal-cp-broker starting (advertise=%s target=%s:%d)",
           cfg.advertise_ip, cfg.target_ip, cfg.target_port);

    if (cfg.mdns_only)   { return run_mdns_only(&cfg); }
    if (cfg.relay_only)  { return run_relay_only(&cfg); }
    return run_full(&cfg);
}
