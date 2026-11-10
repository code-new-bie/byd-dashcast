/* TEST FIXTURE ONLY: the QNX message channel into the decoder's mdevpush input, linked into the Linux test build.
 *
 *   FAKE_LOG                every call is appended here
 *   FAKE_NAME_OPEN_FAILS    the first N name_open calls fail, as while the decoder starts up
 *   FAKE_NAME_CLOSE_FAILS   name_close fails
 *   FAKE_PUSH_BUSY          the first N sends answer "queue full"
 *   FAKE_PUSH_ERROR_AT      send number N fails hard
 */
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct part { const void *base; unsigned size; };

static int opens, sends;

static int number(const char *name)
{
    const char *text = getenv(name);

    return text ? atoi(text) : 0;
}

static void note(const char *format, ...)
{
    FILE *log = fopen(getenv("FAKE_LOG"), "a");
    va_list args;

    va_start(args, format);
    vfprintf(log, format, args);
    va_end(args);
    fputc('\n', log);
    fclose(log);
}

int name_open(const char *name, int flags)
{
    (void)flags;
    if (++opens <= number("FAKE_NAME_OPEN_FAILS"))
        return -1;
    note("name_open %s", name);
    return 33;
}

int name_close(int connection)
{
    note("name_close %d", connection);
    return getenv("FAKE_NAME_CLOSE_FAILS") ? -1 : 0;
}

int MsgSendvs_r(int connection, const void *send_parts, int send_count, void *reply, int reply_size)
{
    const struct part *parts = send_parts;
    unsigned head[6], total = 0;
    unsigned long long pts = 0;
    const unsigned char *payload;
    int i;

    (void)connection;
    (void)reply;
    (void)reply_size;
    ++sends;
    if (sends <= number("FAKE_PUSH_BUSY"))
        return -16;
    if (sends == number("FAKE_PUSH_ERROR_AT"))
        return -5;
    memcpy(head, parts[0].base, sizeof head);
    for (i = 0; i < send_count; i++)
        total += parts[i].size;
    if (send_count == 3)
        memcpy(&pts, parts[1].base, sizeof pts);
    payload = parts[send_count - 1].base;
    note("push kind=%u declared=%u total=%u pts=%llu bytes=%02x%02x%02x%02x", head[4], head[5], total, pts, payload[0], payload[1],
         payload[2], payload[3]);
    return 0;
}
