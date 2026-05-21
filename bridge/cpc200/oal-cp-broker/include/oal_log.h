/* oal_log.h — version-stamped logging for the CPC200 broker.
 *
 * Mirrors the convention used in bridge/openautolink/headless/oal_log.hpp:
 * every log line starts with "[broker X.Y.Z] " so you can never confuse
 * which binary produced a given log entry.
 */
#ifndef OAL_LOG_H
#define OAL_LOG_H

#include <stdio.h>
#include <time.h>
#include "oal_version.h"

static inline void oal_log_prefix(FILE *stream) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    struct tm tm;
    localtime_r(&ts.tv_sec, &tm);
    fprintf(stream, "[%02d:%02d:%02d.%03ld] [broker %s] ",
            tm.tm_hour, tm.tm_min, tm.tm_sec,
            ts.tv_nsec / 1000000L, OAL_VERSION);
}

#define BLOG_I(fmt, ...) do { \
    oal_log_prefix(stdout); \
    fprintf(stdout, fmt "\n", ##__VA_ARGS__); \
    fflush(stdout); \
} while (0)

#define BLOG_W(fmt, ...) do { \
    oal_log_prefix(stderr); \
    fprintf(stderr, "WARN: " fmt "\n", ##__VA_ARGS__); \
    fflush(stderr); \
} while (0)

#define BLOG_E(fmt, ...) do { \
    oal_log_prefix(stderr); \
    fprintf(stderr, "ERROR: " fmt "\n", ##__VA_ARGS__); \
    fflush(stderr); \
} while (0)

#endif /* OAL_LOG_H */
