/* mfi_probe.c — Probe I²C buses for Apple MFi Authentication Coprocessor
 * Cross-compile: arm-linux-gnueabihf-gcc -static -o mfi_probe mfi_probe.c
 * Run on CPC200: ./mfi_probe
 */
#include <stdio.h>
#include <stdlib.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <string.h>
#include <errno.h>

#define I2C_SLAVE 0x0703

/* Apple MFi Auth Coprocessor register map (from MFi spec R30+) */
#define REG_DEVICE_VERSION        0x00
#define REG_FIRMWARE_VERSION      0x01
#define REG_AUTH_PROTO_MAJOR      0x02
#define REG_AUTH_PROTO_MINOR      0x03
#define REG_DEVICE_ID             0x04
#define REG_ERROR_CODE            0x05
#define REG_CHALLENGE_RESPONSE_LEN 0x10
#define REG_CHALLENGE_RESPONSE     0x11
#define REG_CHALLENGE_DATA_LEN    0x12
#define REG_CHALLENGE_DATA         0x13
#define REG_CERTIFICATE_DATA_LEN  0x20
#define REG_CERTIFICATE_DATA       0x21
#define REG_SELF_TEST_STATUS      0x30

static int i2c_read_reg(int fd, unsigned char reg, unsigned char *buf, int len) {
    /* Write register address, then read data */
    if (write(fd, &reg, 1) != 1) return -1;
    return read(fd, buf, len);
}

static void probe_address(const char *bus_path, int addr) {
    int fd = open(bus_path, O_RDWR);
    if (fd < 0) {
        printf("  %s: open failed: %s\n", bus_path, strerror(errno));
        return;
    }

    if (ioctl(fd, I2C_SLAVE, addr) < 0) {
        printf("  %s addr 0x%02X: ioctl failed: %s\n", bus_path, addr, strerror(errno));
        close(fd);
        return;
    }

    unsigned char buf[8];
    memset(buf, 0, sizeof(buf));

    /* Try reading device version register */
    int n = i2c_read_reg(fd, REG_DEVICE_VERSION, buf, 1);
    if (n <= 0) {
        printf("  %s addr 0x%02X: no response (n=%d, %s)\n", bus_path, addr, n, strerror(errno));
        close(fd);
        return;
    }

    printf("  %s addr 0x%02X: *** DEVICE FOUND ***\n", bus_path, addr);
    printf("    Device Version:    0x%02X\n", buf[0]);

    /* Read more registers */
    if (i2c_read_reg(fd, REG_FIRMWARE_VERSION, buf, 1) > 0)
        printf("    Firmware Version:  0x%02X\n", buf[0]);

    if (i2c_read_reg(fd, REG_AUTH_PROTO_MAJOR, buf, 1) > 0)
        printf("    Auth Proto Major:  0x%02X\n", buf[0]);

    if (i2c_read_reg(fd, REG_AUTH_PROTO_MINOR, buf, 1) > 0)
        printf("    Auth Proto Minor:  0x%02X\n", buf[0]);

    if (i2c_read_reg(fd, REG_DEVICE_ID, buf, 4) > 0)
        printf("    Device ID:         0x%02X 0x%02X 0x%02X 0x%02X\n",
               buf[0], buf[1], buf[2], buf[3]);

    if (i2c_read_reg(fd, REG_ERROR_CODE, buf, 1) > 0)
        printf("    Error Code:        0x%02X\n", buf[0]);

    if (i2c_read_reg(fd, REG_SELF_TEST_STATUS, buf, 1) > 0)
        printf("    Self-Test Status:  0x%02X\n", buf[0]);

    /* Try reading certificate data length */
    if (i2c_read_reg(fd, REG_CERTIFICATE_DATA_LEN, buf, 2) > 0)
        printf("    Certificate Len:   %d bytes\n", (buf[0] << 8) | buf[1]);

    printf("    *** Apple MFi Authentication Coprocessor confirmed! ***\n");

    close(fd);
}

int main() {
    printf("=== MFi Chip Probe ===\n\n");

    const char *buses[] = {"/dev/i2c-0", "/dev/i2c-1", "/dev/i2c-2", NULL};
    int addrs[] = {0x10, 0x11, 0x20, -1};  /* Common MFi addresses */

    for (int b = 0; buses[b]; b++) {
        if (access(buses[b], F_OK) != 0) continue;
        printf("Bus: %s\n", buses[b]);
        for (int a = 0; addrs[a] >= 0; a++) {
            probe_address(buses[b], addrs[a]);
        }
        printf("\n");
    }

    /* Also do a full scan of common addresses */
    printf("=== Full I²C scan (addresses 0x03-0x77) ===\n");
    for (int b = 0; buses[b]; b++) {
        if (access(buses[b], F_OK) != 0) continue;
        printf("Bus: %s\n  ", buses[b]);
        int found = 0;
        for (int addr = 0x03; addr <= 0x77; addr++) {
            int fd = open(buses[b], O_RDWR);
            if (fd < 0) break;
            if (ioctl(fd, I2C_SLAVE, addr) < 0) { close(fd); continue; }
            unsigned char dummy;
            int r = read(fd, &dummy, 1);
            close(fd);
            if (r == 1) {
                printf("0x%02X ", addr);
                found++;
            }
        }
        if (found == 0) printf("(none)");
        printf("\n  Found %d device(s)\n\n", found);
    }

    return 0;
}
