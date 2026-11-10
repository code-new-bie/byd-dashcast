/* Time and logging shared by the two instrument programs. Every log line starts with the
 * monotonic time in seconds, so a switch or a session can be timed step by step. */
#ifndef DASHCAST_NATIVE_LOG_H
#define DASHCAST_NATIVE_LOG_H

#include "qnx.h"

typedef unsigned long long ms_t;

enum { LOG_LINE = 192 };

/* nsec / 1000000 without a 64-bit division: multiply by 2^52/10^6 rounded up and shift. */
static unsigned millis_of(long nsec)
{
    return (unsigned)(((ms_t)(unsigned long)nsec * 4503599628ULL) >> 52);
}

static ms_t now_ms(void)
{
    struct timespec now;

    clock_gettime(CLOCK_MONOTONIC, &now);
    return (ms_t)(unsigned long)now.tv_sec * 1000 + millis_of(now.tv_nsec);
}

static int stamp(char *line, size_t room)
{
    struct timespec now;
    int length;

    clock_gettime(CLOCK_MONOTONIC, &now);
    length = snprintf(line, room, "%ld.%03u ", (long)now.tv_sec, millis_of(now.tv_nsec));
    return length > 0 && (size_t)length < room ? length : 0;
}

/* The prefix is written first so that each line still reaches the log in one write. */
#define LOG(...)                                                                       \
    do {                                                                               \
        char log_line_[LOG_LINE];                                                      \
        int log_start_ = stamp(log_line_, sizeof log_line_);                           \
        int log_size_ = snprintf(log_line_ + log_start_, sizeof log_line_ - (size_t)log_start_, __VA_ARGS__); \
        if (log_size_ > 0) {                                                           \
            if (log_size_ >= (int)sizeof log_line_ - log_start_)                       \
                log_size_ = (int)sizeof log_line_ - log_start_ - 1;                    \
            write(2, log_line_, (size_t)(log_start_ + log_size_));                     \
        }                                                                              \
    } while (0)

static void say(const char *text)
{
    LOG("%s", text);
}

#endif
