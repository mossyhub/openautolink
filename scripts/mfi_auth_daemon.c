/* mfi_auth_daemon.c — MFi signing service for OpenAutoLink CarPlay
 *
 * Runs on rooted CPC200. Listens on TCP, proxies MFi auth
 * challenge/response to the Apple Authentication Coprocessor
 * on I²C bus 1, address 0x11.
 *
 * Protocol (all little-endian):
 *   Client sends: [cmd:1] [len:2] [data:len]
 *   Server sends: [status:1] [len:2] [data:len]
 *
 * Commands:
 *   0x01 = PING           → responds 0x01 + "OK"
 *   0x02 = GET_CERT        → reads certificate from chip
 *   0x03 = SIGN_CHALLENGE  → signs challenge, returns signature
 *   0x04 = GET_INFO        → returns chip version info
 *
 * Cross-compile:
 *   arm-linux-gnueabihf-gcc -static -o mfi_auth_daemon mfi_auth_daemon.c
 *
 * Run on CPC200:
 *   /tmp/mfi_auth_daemon 5290
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/ioctl.h>
#include <netinet/in.h>
#include <arpa/inet.h>

#define I2C_SLAVE       0x0703
#define I2C_BUS         "/dev/i2c-1"
#define MFI_ADDR        0x11
#define DEFAULT_PORT    5290
#define MAX_PAYLOAD     2048

/* MFi Auth Coprocessor 3.0 register map */
#define REG_DEVICE_VERSION         0x00
#define REG_FIRMWARE_VERSION       0x01
#define REG_AUTH_PROTO_MAJOR       0x02
#define REG_AUTH_PROTO_MINOR       0x03
#define REG_DEVICE_ID              0x04
#define REG_ERROR_CODE             0x05
#define REG_AUTH_CONTROL_STATUS    0x10
#define REG_CHALLENGE_RESPONSE_LEN 0x11
#define REG_CHALLENGE_RESPONSE     0x12
#define REG_CHALLENGE_DATA_LEN     0x20
#define REG_CHALLENGE_DATA         0x21
#define REG_ACCESSORY_CERT_LEN     0x30
#define REG_ACCESSORY_CERT_PAGE1   0x31
#define REG_ACCESSORY_CERT_PAGE2   0x32
#define REG_ACCESSORY_CERT_PAGE3   0x33
#define REG_SELF_TEST_STATUS       0x40

/* Commands from client */
#define CMD_PING            0x01
#define CMD_GET_CERT        0x02
#define CMD_SIGN_CHALLENGE  0x03
#define CMD_GET_INFO        0x04

/* Response status */
#define STATUS_OK           0x00
#define STATUS_ERROR        0xFF

static int g_i2c_fd = -1;
static volatile int g_running = 1;

static void signal_handler(int sig) {
    (void)sig;
    g_running = 0;
}

static int i2c_open(void) {
    int fd = open(I2C_BUS, O_RDWR);
    if (fd < 0) {
        perror("open i2c");
        return -1;
    }
    if (ioctl(fd, I2C_SLAVE, MFI_ADDR) < 0) {
        perror("ioctl I2C_SLAVE");
        close(fd);
        return -1;
    }
    return fd;
}

static int i2c_write_reg(int fd, unsigned char reg, const unsigned char *data, int len) {
    unsigned char buf[256];
    if (len + 1 > (int)sizeof(buf)) return -1;
    buf[0] = reg;
    memcpy(buf + 1, data, len);
    return write(fd, buf, len + 1);
}

static int i2c_read_reg(int fd, unsigned char reg, unsigned char *data, int len) {
    if (write(fd, &reg, 1) != 1) return -1;
    return read(fd, data, len);
}

/* Send a framed response: [status:1][len:2 LE][data:len] */
static int send_response(int sock, unsigned char status,
                         const unsigned char *data, int len) {
    unsigned char hdr[3];
    hdr[0] = status;
    hdr[1] = len & 0xFF;
    hdr[2] = (len >> 8) & 0xFF;
    if (send(sock, hdr, 3, 0) != 3) return -1;
    if (len > 0 && send(sock, data, len, 0) != len) return -1;
    return 0;
}

/* Read exactly n bytes from socket */
static int recv_exact(int sock, unsigned char *buf, int n) {
    int total = 0;
    while (total < n) {
        int r = recv(sock, buf + total, n - total, 0);
        if (r <= 0) return -1;
        total += r;
    }
    return total;
}

static void handle_ping(int sock) {
    send_response(sock, STATUS_OK, (const unsigned char *)"OK", 2);
}

static void handle_get_info(int sock) {
    unsigned char info[6];
    memset(info, 0, sizeof(info));
    i2c_read_reg(g_i2c_fd, REG_DEVICE_VERSION, &info[0], 1);
    i2c_read_reg(g_i2c_fd, REG_FIRMWARE_VERSION, &info[1], 1);
    i2c_read_reg(g_i2c_fd, REG_AUTH_PROTO_MAJOR, &info[2], 1);
    i2c_read_reg(g_i2c_fd, REG_AUTH_PROTO_MINOR, &info[3], 1);
    i2c_read_reg(g_i2c_fd, REG_ERROR_CODE, &info[4], 1);
    i2c_read_reg(g_i2c_fd, REG_SELF_TEST_STATUS, &info[5], 1);
    send_response(sock, STATUS_OK, info, 6);
}

static void handle_get_cert(int sock) {
    /* Read certificate length */
    unsigned char len_buf[2];
    if (i2c_read_reg(g_i2c_fd, REG_ACCESSORY_CERT_LEN, len_buf, 2) < 2) {
        send_response(sock, STATUS_ERROR, (const unsigned char *)"cert len read failed", 20);
        return;
    }
    int cert_len = (len_buf[0] << 8) | len_buf[1];
    if (cert_len <= 0 || cert_len > MAX_PAYLOAD) {
        send_response(sock, STATUS_ERROR, (const unsigned char *)"bad cert len", 12);
        return;
    }

    /* Read certificate pages (128 bytes per page) */
    unsigned char cert[MAX_PAYLOAD];
    int offset = 0;
    int pages = (cert_len + 127) / 128;
    unsigned char page_regs[] = {REG_ACCESSORY_CERT_PAGE1, REG_ACCESSORY_CERT_PAGE2, REG_ACCESSORY_CERT_PAGE3};
    for (int p = 0; p < pages && p < 3; p++) {
        int to_read = cert_len - offset;
        if (to_read > 128) to_read = 128;
        if (i2c_read_reg(g_i2c_fd, page_regs[p], cert + offset, to_read) < to_read) {
            send_response(sock, STATUS_ERROR, (const unsigned char *)"cert page read failed", 21);
            return;
        }
        offset += to_read;
    }

    printf("[mfi] Certificate read: %d bytes\n", cert_len);
    send_response(sock, STATUS_OK, cert, cert_len);
}

static void handle_sign(int sock, const unsigned char *challenge, int challenge_len) {
    /* Write challenge length */
    unsigned char len_buf[2];
    len_buf[0] = (challenge_len >> 8) & 0xFF;
    len_buf[1] = challenge_len & 0xFF;
    if (i2c_write_reg(g_i2c_fd, REG_CHALLENGE_DATA_LEN, len_buf, 2) < 0) {
        send_response(sock, STATUS_ERROR, (const unsigned char *)"write challenge len failed", 25);
        return;
    }

    /* Write challenge data */
    if (i2c_write_reg(g_i2c_fd, REG_CHALLENGE_DATA, challenge, challenge_len) < 0) {
        send_response(sock, STATUS_ERROR, (const unsigned char *)"write challenge data failed", 27);
        return;
    }

    /* Trigger signing: write 1 to auth control/status register */
    unsigned char trigger = 0x01;
    if (i2c_write_reg(g_i2c_fd, REG_AUTH_CONTROL_STATUS, &trigger, 1) < 0) {
        send_response(sock, STATUS_ERROR, (const unsigned char *)"trigger sign failed", 19);
        return;
    }

    /* Poll for completion (status register bit 4) */
    unsigned char status;
    int retries = 100;
    do {
        usleep(10000); /* 10ms */
        if (i2c_read_reg(g_i2c_fd, REG_AUTH_CONTROL_STATUS, &status, 1) < 1) {
            send_response(sock, STATUS_ERROR, (const unsigned char *)"poll status failed", 18);
            return;
        }
    } while (!(status & 0x10) && --retries > 0);

    if (retries == 0) {
        send_response(sock, STATUS_ERROR, (const unsigned char *)"sign timeout", 12);
        return;
    }

    /* Check for errors */
    unsigned char err;
    i2c_read_reg(g_i2c_fd, REG_ERROR_CODE, &err, 1);
    if (err != 0) {
        printf("[mfi] Sign error code: 0x%02X\n", err);
        send_response(sock, STATUS_ERROR, &err, 1);
        return;
    }

    /* Read response length */
    unsigned char resp_len_buf[2];
    if (i2c_read_reg(g_i2c_fd, REG_CHALLENGE_RESPONSE_LEN, resp_len_buf, 2) < 2) {
        send_response(sock, STATUS_ERROR, (const unsigned char *)"read resp len failed", 20);
        return;
    }
    int resp_len = (resp_len_buf[0] << 8) | resp_len_buf[1];
    if (resp_len <= 0 || resp_len > MAX_PAYLOAD) {
        send_response(sock, STATUS_ERROR, (const unsigned char *)"bad resp len", 12);
        return;
    }

    /* Read signed response */
    unsigned char response[MAX_PAYLOAD];
    if (i2c_read_reg(g_i2c_fd, REG_CHALLENGE_RESPONSE, response, resp_len) < resp_len) {
        send_response(sock, STATUS_ERROR, (const unsigned char *)"read response failed", 20);
        return;
    }

    printf("[mfi] Challenge signed: %d bytes in, %d bytes out\n", challenge_len, resp_len);
    send_response(sock, STATUS_OK, response, resp_len);
}

static void handle_client(int client_fd) {
    struct sockaddr_in addr;
    socklen_t alen = sizeof(addr);
    getpeername(client_fd, (struct sockaddr *)&addr, &alen);
    printf("[mfi] Client connected: %s\n", inet_ntoa(addr.sin_addr));

    while (g_running) {
        /* Read command header: [cmd:1][len:2 LE] */
        unsigned char hdr[3];
        if (recv_exact(client_fd, hdr, 3) < 0) break;

        unsigned char cmd = hdr[0];
        int payload_len = hdr[1] | (hdr[2] << 8);

        /* Read payload if any */
        unsigned char payload[MAX_PAYLOAD];
        if (payload_len > 0) {
            if (payload_len > MAX_PAYLOAD) break;
            if (recv_exact(client_fd, payload, payload_len) < 0) break;
        }

        switch (cmd) {
            case CMD_PING:
                handle_ping(client_fd);
                break;
            case CMD_GET_INFO:
                handle_get_info(client_fd);
                break;
            case CMD_GET_CERT:
                handle_get_cert(client_fd);
                break;
            case CMD_SIGN_CHALLENGE:
                handle_sign(client_fd, payload, payload_len);
                break;
            default:
                printf("[mfi] Unknown command: 0x%02X\n", cmd);
                send_response(client_fd, STATUS_ERROR,
                             (const unsigned char *)"unknown cmd", 11);
                break;
        }
    }

    printf("[mfi] Client disconnected\n");
    close(client_fd);
}

int main(int argc, char **argv) {
    int port = (argc > 1) ? atoi(argv[1]) : DEFAULT_PORT;

    signal(SIGINT, signal_handler);
    signal(SIGTERM, signal_handler);
    signal(SIGPIPE, SIG_IGN);

    /* Open I²C */
    g_i2c_fd = i2c_open();
    if (g_i2c_fd < 0) {
        fprintf(stderr, "Failed to open MFi chip at %s addr 0x%02X\n", I2C_BUS, MFI_ADDR);
        return 1;
    }

    /* Verify chip is responding */
    unsigned char ver;
    if (i2c_read_reg(g_i2c_fd, REG_DEVICE_VERSION, &ver, 1) < 1) {
        fprintf(stderr, "MFi chip not responding\n");
        close(g_i2c_fd);
        return 1;
    }
    printf("[mfi] MFi Auth Coprocessor v%d.%d at %s:0x%02X\n",
           (ver >> 4) & 0xF, ver & 0xF, I2C_BUS, MFI_ADDR);

    /* Create TCP server */
    int srv = socket(AF_INET, SOCK_STREAM, 0);
    if (srv < 0) { perror("socket"); return 1; }

    int opt = 1;
    setsockopt(srv, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    struct sockaddr_in saddr = {0};
    saddr.sin_family = AF_INET;
    saddr.sin_addr.s_addr = INADDR_ANY;
    saddr.sin_port = htons(port);

    if (bind(srv, (struct sockaddr *)&saddr, sizeof(saddr)) < 0) {
        perror("bind"); return 1;
    }
    if (listen(srv, 2) < 0) {
        perror("listen"); return 1;
    }

    printf("[mfi] Listening on port %d\n", port);

    while (g_running) {
        int client = accept(srv, NULL, NULL);
        if (client < 0) {
            if (g_running) perror("accept");
            continue;
        }
        handle_client(client);
    }

    close(srv);
    close(g_i2c_fd);
    printf("[mfi] Shutdown\n");
    return 0;
}
