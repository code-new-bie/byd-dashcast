/* dashcast-receiver: takes H.264 from the phone side over one TCP stream and shows it under the HMI.
 *
 *   dashcast-receiver --listen IP PORT --client IP [--ready-fd N]
 *
 * The stream is the DCV1 protocol (see NoAuthVideoProtocol.java). Only a client at the peer address
 * is served, and the decoder is the system's own mm-renderer fed through its mdevpush input.
 * With --ready-fd, one byte is written to that descriptor once the port is listening.
 */
#include "native_log.h"
#include "version.h"

#ifndef PPS_WINDOW_CONTROL
#define PPS_WINDOW_CONTROL "/pps/winmanager_service/control"
#endif
#ifndef BACKGROUND_PAGE
#define BACKGROUND_PAGE "/dev/shmem/dashcast-bg1"
#endif
#ifndef RUN_DIR
#define RUN_DIR "/fs/tmpfs/dashcast"
#endif

enum { T_START = 1, T_FRAME = 2, T_STOP = 3, T_CLOSE = 4, T_HELLO = 7, T_HEARTBEAT = 8, T_BACKGROUND = 10, T_LOG = 11 };
enum { HEADER = 24, ACK = 16, OFFER = 80, LOG_REQUEST = 8, LOG_TAIL = 16384, LOG_VERSION = 2, BG_REPLY = 168, BG_SNAPSHOT = 128, MAX_FRAME = 0x200000, MAX_SEQUENCE = 240000 };
enum { E_PARSER = -2, E_SUBMIT = -3, E_WINDOW = -4 };
enum { HANDSHAKE_MS = 10000, ACK_MS = 1000, IDLE_MS = 1500, HEARTBEAT_MS = 15000, SESSION_MS = 7260000 };
enum { FRAME_WIDTH = 1920, FRAME_HEIGHT = 720, FPS = 30, MAX_PTS_STEP = 2000 };

static volatile int stopped;
static int cleanup_problem;
static unsigned submissions;

static void on_stop(int number)
{
    (void)number;
    stopped = 1;
}

static unsigned big32(const unsigned char *at)
{
    return (unsigned)at[0] << 24 | (unsigned)at[1] << 16 | (unsigned)at[2] << 8 | at[3];
}

static void put32(unsigned char *at, unsigned value)
{
    at[0] = (unsigned char)(value >> 24);
    at[1] = (unsigned char)(value >> 16);
    at[2] = (unsigned char)(value >> 8);
    at[3] = (unsigned char)value;
}

/* ---- Blocking socket helpers with an absolute deadline ---- */

static int wait_fd(int fd, short events, ms_t deadline)
{
    struct pollfd item;
    ms_t now;
    int rc;

    while (!stopped) {
        now = now_ms();
        if (deadline <= now)
            return -1;
        item.fd = fd;
        item.events = events;
        item.revents = 0;
        rc = poll(&item, 1, deadline - now > 100 ? 100 : (int)(deadline - now));
        if (rc > 0)
            return item.revents & events ? 0 : -1;
        if (rc < 0 && errno != EINTR)
            return -1;
    }
    return -1;
}

static int transfer(int fd, unsigned char *data, size_t size, int sending, ms_t deadline)
{
    while (size) {
        ssize_t done;

        if (wait_fd(fd, sending ? POLLOUT : POLLIN, deadline))
            return -1;
        done = sending ? send(fd, data, size, 0) : recv(fd, data, size, 0);
        if (done == 0)
            return -1;
        if (done < 0) {
            if (errno != EAGAIN && errno != EINTR)
                return -1;
            continue;
        }
        size -= (size_t)done;
        data += done;
    }
    return 0;
}

/* ---- Display and decoder state ---- */

enum screen_call { CREATE_CONTEXT, DESTROY_CONTEXT, CREATE_WINDOW, DESTROY_WINDOW, CREATE_GROUP, CREATE_BUFFERS, SET_WINDOW_IV,
                   SET_WINDOW_PV, SET_WINDOW_CV, GET_WINDOW_CV, GET_WINDOW_IV, GET_WINDOW_PV, GET_CONTEXT_IV, GET_CONTEXT_PV,
                   GET_DISPLAY_IV, GET_BUFFER_PV, GET_BUFFER_IV, POST_WINDOW, CREATE_EVENT, DESTROY_EVENT, GET_EVENT, EVENT_IV,
                   EVENT_PV, FLUSH, SCREEN_CALLS };
static const char *const SCREEN_NAMES[SCREEN_CALLS] = {
    "screen_create_context", "screen_destroy_context", "screen_create_window", "screen_destroy_window",
    "screen_create_window_group", "screen_create_window_buffers", "screen_set_window_property_iv",
    "screen_set_window_property_pv", "screen_set_window_property_cv", "screen_get_window_property_cv",
    "screen_get_window_property_iv", "screen_get_window_property_pv", "screen_get_context_property_iv",
    "screen_get_context_property_pv", "screen_get_display_property_iv", "screen_get_buffer_property_pv",
    "screen_get_buffer_property_iv", "screen_post_window", "screen_create_event", "screen_destroy_event",
    "screen_get_event", "screen_get_event_property_iv", "screen_get_event_property_pv", "screen_flush_context"};

enum media_call { MMR_CONNECT, MMR_DISCONNECT, MMR_CREATE, MMR_DESTROY, MMR_CONTEXT_PARAMETERS, MMR_INPUT_PARAMETERS,
                  MMR_OUTPUT_PARAMETERS, MMR_OUTPUT_ATTACH, MMR_INPUT_ATTACH, MMR_PLAY, MEDIA_CALLS };
static const char *const MEDIA_NAMES[MEDIA_CALLS] = {
    "mmr_connect", "mmr_disconnect", "mmr_context_create", "mmr_context_destroy", "mmr_context_parameters",
    "mmr_input_parameters", "mmr_output_parameters", "mmr_output_attach", "mmr_input_attach", "mmr_play"};

enum { PROP_BUFFER_SIZE = 0x05, PROP_DISPLAY = 0x0b, PROP_FORMAT = 0x0e, PROP_ID = 0x14, PROP_POINTER = 0x22, PROP_POSITION = 0x23,
       PROP_RENDER_BUFFERS = 0x25, PROP_SIZE = 0x28, PROP_STRIDE = 0x2c, PROP_EVENT_TYPE = 0x2f, PROP_USAGE = 0x30, PROP_VISIBLE = 0x33,
       PROP_WINDOW = 0x34, PROP_ZORDER = 0x36, PROP_DISPLAY_COUNT = 0x3b, PROP_DISPLAYS = 0x3c };
enum { PUSH_FRAME = 3, MAX_EVENTS_PER_FRAME = 16, WINDOW_WAIT_MS = 2000 };
enum { EVENT_NONE = 0, EVENT_CREATE = 1, FORMAT_RGBA8888 = 8, USAGE_NATIVE = 4, MAX_DISPLAYS = 8, UNDER_HMI = -3, TIMEOUT_SEND_REPLY = 0x50 };

typedef int (*call_fn)(long, long, long, long, long);
typedef void *(*strm_new_fn)(void);
typedef void *(*strm_set_fn)(void *, const char *, const char *);
typedef int (*strm_destroy_fn)(void *);

struct rect { int x, y, width, height, fps; };

static struct display {
    void *screen_library, *media_library, *stream_library;
    call_fn screen_api[SCREEN_CALLS], media_api[MEDIA_CALLS];
    strm_new_fn dict_new;
    strm_set_fn dict_set;
    strm_destroy_fn dict_destroy;
    long context, parent, child, event, connection, media;
    int rect[5], push;
    int have_frame;
    unsigned last_pts;
    ms_t first_frame, video_time;
    char name[64], video_name[80], stream_name[80];
} d = {.push = -1};

static unsigned long long call_limit = 1000000000ULL;

static int timed(call_fn fn, long a, long b, long c, long d_arg, long e)
{
    int rc, saved;

    TimerTimeout(CLOCK_MONOTONIC, TIMEOUT_SEND_REPLY, NULL, &call_limit, NULL);
    rc = fn(a, b, c, d_arg, e);
    saved = errno;
    TimerTimeout(CLOCK_MONOTONIC, 0, NULL, NULL, NULL);
    errno = saved;
    return rc;
}

static int screen(enum screen_call call, long a, long b, long c, long e)
{
    return timed(d.screen_api[call], a, b, c, e, 0);
}

static int set_ints(long window, int property, const int *values)
{
    return screen(SET_WINDOW_IV, window, property, (long)values, 0);
}

static int set_int(long window, int property, int value)
{
    return set_ints(window, property, &value);
}

static int load_all(void **library, const char *name, int flags, const char *const *names, call_fn *api, int count)
{
    int i;

    *library = dlopen(name, flags);
    if (!*library)
        return -1;
    for (i = 0; i < count; i++) {
        api[i] = (call_fn)dlsym(*library, names[i]);
        if (!api[i])
            return -1;
    }
    return 0;
}

static int select_display(void)
{
    long displays[MAX_DISPLAYS], chosen = 0;
    int count = 0, i, size[2];

    if (screen(GET_CONTEXT_IV, d.context, PROP_DISPLAY_COUNT, (long)&count, 0) || (unsigned)(count - 1) > MAX_DISPLAYS - 1 ||
        screen(GET_CONTEXT_PV, d.context, PROP_DISPLAYS, (long)displays, 0))
        return -1;
    for (i = 0; i < count; i++) {
        if (screen(GET_DISPLAY_IV, displays[i], PROP_SIZE, (long)size, 0))
            return -1;
        if (size[0] == FRAME_WIDTH && size[1] == FRAME_HEIGHT) {
            if (chosen)
                return -1;
            chosen = displays[i];
        }
    }
    return chosen ? screen(SET_WINDOW_PV, d.parent, PROP_DISPLAY, (long)&chosen, 0) : -1;
}

/* The full-frame parent sits below the HMI; a window of any other size stays above it. */
static int place_window(long window, const int *rect)
{
    int zorder = (d.rect[2] == FRAME_WIDTH || d.rect[2] == 768) && window == d.parent ? UNDER_HMI : 1;

    if (set_ints(window, PROP_POSITION, rect) || set_ints(window, PROP_SIZE, rect + 2))
        return -1;
    return set_ints(window, PROP_ZORDER, &zorder) ? -1 : 0;
}

static int open_parent(const struct rect *rect)
{
    memset(&d, 0, sizeof d);
    d.push = -1;
    memcpy(d.rect, rect, sizeof d.rect);
    snprintf(d.name, sizeof d.name, "dashcast_%ld", (long)getpid());
    snprintf(d.stream_name, sizeof d.stream_name, "%s", d.name);
    snprintf(d.video_name, sizeof d.video_name, "%s_picture", d.name);
    if (load_all(&d.screen_library, "libscreen.so.1", RTLD_NOW | RTLD_LOCAL, SCREEN_NAMES, d.screen_api, SCREEN_CALLS))
        return -1;
    if (screen(CREATE_CONTEXT, (long)&d.context, 1, 0, 0) || screen(CREATE_WINDOW, (long)&d.parent, d.context, 0, 0) || select_display() ||
        screen(SET_WINDOW_CV, d.parent, PROP_ID, (long)(strlen(d.name) + 1), (long)d.name) || screen(CREATE_GROUP, d.parent, (long)d.name, 0, 0))
        return -1;
    return place_window(d.parent, d.rect);
}

static int clear_parent(void)
{
    int extent[2], stride = 0, y;
    long buffers[3] = {0, 0, 0};
    unsigned char *pixels = NULL;

    extent[0] = d.rect[2];
    extent[1] = d.rect[3];
    if (set_int(d.parent, PROP_VISIBLE, 0) || set_int(d.parent, PROP_FORMAT, FORMAT_RGBA8888) ||
        set_int(d.parent, PROP_USAGE, USAGE_NATIVE) || set_ints(d.parent, PROP_BUFFER_SIZE, extent) ||
        screen(CREATE_BUFFERS, d.parent, 1, 0, 0) || screen(GET_WINDOW_PV, d.parent, PROP_RENDER_BUFFERS, (long)buffers, 0) ||
        screen(GET_BUFFER_PV, buffers[0], PROP_POINTER, (long)&pixels, 0) ||
        screen(GET_BUFFER_IV, buffers[0], PROP_STRIDE, (long)&stride, 0) || !pixels || extent[0] * 4 > stride)
        return -1;
    for (y = 0; y < extent[1]; y++)
        memset(pixels + y * stride, 0, (size_t)extent[0] * 4);
    if (screen(POST_WINDOW, d.parent, buffers[0], 0, 0) || set_int(d.parent, PROP_VISIBLE, 1) || screen(FLUSH, d.context, 0, 0, 0))
        return -1;
    return 0;
}

static int media_parameters(int kind, int output)
{
    static const char *const KEYS[3][2] = {
        {"prefetch_mode", "mmf.SCREEN_WRITER_DISABLE_BACKGROUND"},
        {"VideoDecoderLowLatencyMode", "ColorSpaceMode"},
        {"mmf.SCREEN_WRITER_DISABLE_MEDIACLOCK", "mmf.SCREEN_WRITER_POST_BUFFER_NOSYNC"}};
    static const char *const VALUES[3][2] = {{"-1", "1"}, {"1", "0"}, {"1", "1"}};
    void *dict = d.dict_new(), *next;
    int i;

    if (!dict)
        return -1;
    for (i = 0; i < 2; i++) {
        next = d.dict_set(dict, KEYS[kind][i], VALUES[kind][i]);
        if (!next) {
            d.dict_destroy(dict);
            return -1;
        }
        dict = next;
    }
    if (kind == 0)
        return timed(d.media_api[MMR_CONTEXT_PARAMETERS], d.media, (long)dict, 0, 0, 0);
    if (kind == 1)
        return timed(d.media_api[MMR_INPUT_PARAMETERS], d.media, (long)dict, 0, 0, 0);
    return timed(d.media_api[MMR_OUTPUT_PARAMETERS], d.media, output, (long)dict, 0, 0);
}

static int send_packet(int kind, const void *data, unsigned size, unsigned long long pts);

static int push_configure(void)
{
    unsigned config[8] = {0, 0x48323634, FRAME_WIDTH, FRAME_HEIGHT, 0x18, 0, 0, 0};

    return send_packet(2, config, 0x20, 0);
}

static int start_video(void)
{
    char output_url[256], input_url[256];
    ms_t deadline;
    int index;

    d.stream_library = dlopen("libstrm.so.1", RTLD_NOW | RTLD_GLOBAL);
    if (!d.stream_library || load_all(&d.media_library, "libmmrndclient.so.1", RTLD_NOW | RTLD_LOCAL, MEDIA_NAMES, d.media_api, MEDIA_CALLS))
        return -1;
    d.dict_new = (strm_new_fn)dlsym(d.stream_library, "strm_dict_new");
    d.dict_set = (strm_set_fn)dlsym(d.stream_library, "strm_dict_set");
    d.dict_destroy = (strm_destroy_fn)dlsym(d.stream_library, "strm_dict_destroy");
    if (!d.dict_new || !d.dict_set || !d.dict_destroy)
        return -1;
    d.connection = timed(d.media_api[MMR_CONNECT], 0, 0, 0, 0, 0);
    if (!d.connection)
        return -1;
    d.media = timed(d.media_api[MMR_CREATE], d.connection, (long)d.stream_name, 0, 0x180, 0);
    if (!d.media || media_parameters(0, 0))
        return -1;
    snprintf(output_url, sizeof output_url, "screen:/any?wingrp=%s&winembed=1&winid=%s&nodstviewport=1&initflags=invisible", d.name, d.video_name);
    snprintf(input_url, sizeof input_url, "mdevpush:/dev/name/local/%s?fourcc=H264&video.frame_rate=%u&video.depth=24&video.width=%u&video.height=%u&queue_capacity=2",
             d.stream_name, (unsigned)d.rect[4], FRAME_WIDTH, FRAME_HEIGHT);
    index = timed(d.media_api[MMR_OUTPUT_ATTACH], d.media, (long)output_url, (long)"video", 0, 0);
    if (index < 0 || media_parameters(2, index) || media_parameters(1, 0))
        return -1;
    if (timed(d.media_api[MMR_INPUT_ATTACH], d.media, (long)input_url, (long)"track", 0, 0) ||
        timed(d.media_api[MMR_PLAY], d.media, 0, 0, 0, 0) < 0)
        return -1;
    deadline = now_ms() + 2000;
    for (;;) {
        TimerTimeout(CLOCK_MONOTONIC, TIMEOUT_SEND_REPLY, NULL, &call_limit, NULL);
        d.push = name_open(d.stream_name, 0);
        TimerTimeout(CLOCK_MONOTONIC, 0, NULL, NULL, NULL);
        if (d.push >= 0)
            break;
        usleep(10000);
        if (now_ms() >= deadline)
            return -1;
    }
    return push_configure();
}

static int display_close(void);

static int display_open(const struct rect *rect)
{
    int rc;

    if (cleanup_problem || d.context || d.screen_library)
        return -1;
    rc = open_parent(rect);
    if (!rc)
        rc = clear_parent();
    if (!rc)
        rc = screen(CREATE_EVENT, (long)&d.event, d.context, 0, 0);
    if (!rc)
        rc = start_video();
    if (rc) {
        LOG("VIDEO_WINDOW_OPEN_FAILED errno=%d\n", errno);
        display_close();
    }
    return rc;
}

static int raise_parent(void)
{
    static const unsigned long long limit = 1000000000ULL;
    char line[80];
    int length, fd;
    ssize_t written;
    int below = d.rect[2] == FRAME_WIDTH || d.rect[2] == 768;

    length = snprintf(line, sizeof line, below ? "%s::-3\n" : "%s::12\n", d.name);
    if (length > 0x4f)
        return -1;
    TimerTimeout(CLOCK_MONOTONIC, TIMEOUT_SEND_REPLY, NULL, &limit, NULL);
    fd = open(PPS_WINDOW_CONTROL, O_WRONLY | O_NONBLOCK);
    TimerTimeout(CLOCK_MONOTONIC, 0, NULL, NULL, NULL);
    if (fd < 0)
        return -1;
    TimerTimeout(CLOCK_MONOTONIC, TIMEOUT_SEND_REPLY, NULL, &limit, NULL);
    written = write(fd, line, (size_t)length);
    TimerTimeout(CLOCK_MONOTONIC, 0, NULL, NULL, NULL);
    close(fd);
    return written == length ? 0 : -1;
}

/* mm-renderer creates the video window on its own; it is adopted from the Screen event when it appears. */
static int update_video_window(void)
{
    int polls;

    for (polls = 0; polls < MAX_EVENTS_PER_FRAME; polls++) {
        char id[80];
        long window = 0;
        int type = 0;

        memset(id, 0, sizeof id);
        if (screen(GET_EVENT, d.context, d.event, 0, 0))
            return -1;
        if (screen(EVENT_IV, d.event, PROP_EVENT_TYPE, (long)&type, 0))
            return -1;
        if (type == EVENT_NONE)
            break;
        if (type != EVENT_CREATE)
            continue;
        if (screen(EVENT_PV, d.event, PROP_WINDOW, (long)&window, 0) || timed(d.screen_api[GET_WINDOW_CV], window, PROP_ID, sizeof id, (long)id, 0))
            return -1;
        if (memcmp(id, d.video_name, strlen(d.video_name)) != 0)
            continue;
        {
            int child_rect[4] = {0, 0, d.rect[2], d.rect[3]}, got[8];
            if (place_window(d.parent, d.rect) || place_window(window, child_rect) || set_int(window, PROP_VISIBLE, 1) ||
                screen(FLUSH, d.context, 0, 0, 0) || (!d.child && raise_parent()) || screen(FLUSH, d.context, 0, 0, 0))
                return -1;
            if (screen(GET_WINDOW_IV, d.parent, PROP_POSITION, (long)got, 0) || screen(GET_WINDOW_IV, window, PROP_POSITION, (long)(got + 2), 0) ||
                screen(GET_WINDOW_IV, d.parent, PROP_SIZE, (long)(got + 4), 0) || screen(GET_WINDOW_IV, window, PROP_SIZE, (long)(got + 6), 0))
                return -1;
            if (got[0] != d.rect[0] || got[1] != d.rect[1] || got[2] || got[3] || got[4] != d.rect[2] || got[5] != d.rect[3] ||
                got[6] != d.rect[2] || got[7] != d.rect[3]) {
                errno = 5;
                return -1;
            }
            d.child = window;
            say("WINDOW_ADOPTED\n");
        }
    }
    /* Frames are being accepted but the decoder never made its window: give up rather than show nothing. */
    if (!d.child && d.first_frame && now_ms() - d.first_frame > WINDOW_WAIT_MS)
        return -1;
    return 0;
}

static int send_packet(int kind, const void *data, unsigned size, unsigned long long pts)
{
    struct iov { const void *base; unsigned size; } parts[3];
    unsigned head[6];
    ms_t give_up = 0, now;
    int count = 0, rc;

    if (size - 1 >= MAX_FRAME) {
        errno = 22;
        return -1;
    }
    memset(head, 0, sizeof head);
    head[4] = (unsigned)kind;
    head[5] = kind == PUSH_FRAME ? size + 8 : size;
    parts[count].base = head;
    parts[count++].size = 0x18;
    if (kind == PUSH_FRAME) {
        parts[count].base = &pts;
        parts[count++].size = 8;
    }
    parts[count].base = data;
    parts[count++].size = size;
    for (;;) {
        TimerTimeout(CLOCK_MONOTONIC, TIMEOUT_SEND_REPLY, NULL, &call_limit, NULL);
        rc = MsgSendvs_r(d.push, parts, count, NULL, 0);
        TimerTimeout(CLOCK_MONOTONIC, 0, NULL, NULL, NULL);
        if (rc == -16) {
            now = now_ms();
            if (!give_up)
                give_up = now + 250;
            if (give_up <= now) {
                errno = 16;
                return -1;
            }
            usleep(2000);
            continue;
        }
        if (rc < 0) {
            errno = -rc;
            return -1;
        }
        return 0;
    }
}

static int display_frame(const unsigned char *data, unsigned size, unsigned pts)
{
    unsigned step = 0;
    unsigned long long next;

    if (d.have_frame) {
        step = pts - d.last_pts;
        if (step > MAX_PTS_STEP) {
            errno = 22;
            return -1;
        }
    }
    next = d.video_time + step;
    /* The push interface takes a 48-bit microsecond timestamp. */
    if ((next * 1000) >> 48) {
        errno = 22;
        return E_SUBMIT;
    }
    if (send_packet(PUSH_FRAME, data, size, next * 1000) < 0)
        return E_SUBMIT;
    d.video_time = next;
    d.last_pts = pts;
    d.have_frame = 1;
    if (!d.first_frame)
        d.first_frame = now_ms();
    return update_video_window();
}

static int display_close(void)
{
    int failed = 0;

    call_limit = 2000000000ULL;
    if (d.child && d.screen_api[SET_WINDOW_IV])
        failed |= set_int(d.child, PROP_VISIBLE, 0) != 0;
    if (d.push >= 0) {
        TimerTimeout(CLOCK_MONOTONIC, TIMEOUT_SEND_REPLY, NULL, &call_limit, NULL);
        failed |= name_close(d.push) != 0;
        TimerTimeout(CLOCK_MONOTONIC, 0, NULL, NULL, NULL);
    }
    if (d.media)
        failed |= timed(d.media_api[MMR_DESTROY], d.media, 0, 0, 0, 0) != 0;
    if (d.connection)
        timed(d.media_api[MMR_DISCONNECT], d.connection, 0, 0, 0, 0);
    if (d.event)
        failed |= screen(DESTROY_EVENT, d.event, 0, 0, 0) != 0;
    if (d.parent)
        failed |= screen(DESTROY_WINDOW, d.parent, 0, 0, 0) != 0;
    if (d.context)
        failed |= screen(DESTROY_CONTEXT, d.context, 0, 0, 0) != 0;
    TimerTimeout(CLOCK_MONOTONIC, 0, NULL, NULL, NULL);
    if (d.media_library)
        dlclose(d.media_library);
    if (d.stream_library)
        dlclose(d.stream_library);
    if (d.screen_library)
        dlclose(d.screen_library);
    memset(&d, 0, sizeof d);
    d.push = -1;
    call_limit = 1000000000ULL;
    if (failed) {
        say("VIDEO_WINDOW_CLEANUP_FAILED\n");
        cleanup_problem |= 1;
        return -1;
    }
    return cleanup_problem ? -1 : 0;
}

static unsigned media_flags(void)
{
    static const unsigned long long limit = 500000000ULL;
    unsigned flags = 0;
    int visible = 0, saved;

    if (d.media)
        flags |= 1;
    if (d.have_frame)
        flags |= 2;
    if (cleanup_problem)
        flags |= 8;
    if (d.child) {
        flags |= 4;
        if (d.screen_api[GET_WINDOW_IV]) {
            if (!TimerTimeout(CLOCK_MONOTONIC, TIMEOUT_SEND_REPLY, NULL, &limit, NULL) &&
                !d.screen_api[GET_WINDOW_IV](d.child, PROP_VISIBLE, (long)&visible, 0, 0) && visible == 1)
                flags |= 16;
            saved = errno;
            TimerTimeout(CLOCK_MONOTONIC, 0, NULL, NULL, NULL);
            errno = saved;
        }
    }
    return flags;
}

/* ---- H.264 checks: only the stream shape this decoder path is known to take ---- */

struct bits { const unsigned char *at, *end; unsigned byte; int left, zeros, failed; };

static unsigned read_bit(struct bits *b)
{
    unsigned value;

    if (b->left) {
        b->left--;
        return (b->byte >> b->left) & 1;
    }
    if (b->at == b->end) {
        b->failed = 1;
        return 0;
    }
    value = *b->at++;
    b->byte = value;
    if (value == 3 && b->zeros > 1) {
        if (b->at == b->end || *b->at > 3) {
            b->failed = 1;
            return 0;
        }
        value = *b->at++;
        b->zeros = 0;
        b->byte = value;
    }
    b->zeros = value == 0 ? b->zeros + 1 : 0;
    b->left = 7;
    return (value >> 7) & 1;
}

static unsigned read_ue(struct bits *b)
{
    int zeros = 0, i;
    unsigned value = 0;

    for (;;) {
        if (b->failed || read_bit(b))
            break;
        if (++zeros == 25) {
            b->failed = 1;
            return 0;
        }
    }
    for (i = 0; i < zeros; i++)
        value = value << 1 | read_bit(b);
    return (1U << zeros) - 1 + value;
}

static unsigned read_bits(struct bits *b, int count)
{
    unsigned value = 0;

    while (count--)
        value = value << 1 | read_bit(b);
    return value;
}

static int start_code(const unsigned char *data, size_t size)
{
    if (size >= 3 && !data[0] && !data[1]) {
        if (data[2] == 1)
            return 3;
        if (size >= 4 && !data[2] && data[3] == 1)
            return 4;
    }
    return 0;
}

static int check_sps(struct bits *b)
{
    unsigned profile = read_bits(b, 8), constraints = read_bits(b, 8), level = read_bits(b, 8), width, height, crop_x = 0, crop_y = 0, order;

    if (profile != 0x42 || (constraints & 3) || level - 1 > 0x29 || read_ue(b) != 0 || read_ue(b) > 12)
        return -1;
    order = read_ue(b);
    if (order == 0) {
        if (read_ue(b) > 12)
            return -1;
    } else if (order != 2) {
        return -1;
    }
    if (read_ue(b) > 16)
        return -1;
    read_bit(b);
    width = read_ue(b);
    height = read_ue(b);
    if (height > 0x2c || width > 0x77 || !read_bit(b))
        return -1;
    read_bit(b);
    width = (width + 1) << 4;
    height = (height + 1) << 4;
    if (read_bit(b)) {
        unsigned left = read_ue(b), right = read_ue(b), top = read_ue(b), bottom = read_ue(b);
        if (b->failed || left + right >= width >> 1 || top + bottom >= height >> 1)
            return -1;
        crop_x = left + right;
        crop_y = top + bottom;
    }
    if (b->failed || width - crop_x * 2 != FRAME_WIDTH || height - crop_y * 2 != FRAME_HEIGHT)
        return -1;
    return 0;
}

static int check_pps(struct bits *b)
{
    return read_ue(b) != 0 || read_ue(b) != 0 || b->failed ? -1 : 0;
}

static int check_access_unit(const unsigned char *data, size_t size, const struct rect *rect, int first, unsigned flags)
{
    size_t at = 0;
    int have_params = 0, have_slice = 0, has_idr = 0, has_other = 0;

    if (!size)
        return -1;
    while (at < size) {
        size_t body, end;
        int prefix = start_code(data + at, size - at), type, ref;
        unsigned header;
        struct bits b;

        if (!prefix)
            return -1;
        body = at + (size_t)prefix;
        if (body >= size)
            return -1;
        for (end = body; end < size && !start_code(data + end, size - end); end++)
            ;
        header = data[body];
        if ((header & 0x80) || end - body <= 1)
            return -1;
        type = header & 0x1f;
        ref = (header & 0x60) != 0;
        memset(&b, 0, sizeof b);
        b.at = data + body + 1;
        b.end = data + end;
        if (type == 7) {
            if (have_slice || check_sps(&b))
                return -1;
            have_params |= 1;
        } else if (type == 8) {
            if (have_slice || check_pps(&b))
                return -1;
            have_params |= 2;
        } else if ((header & 0x1b) == 1) {
            unsigned mbs, kind;
            mbs = ((unsigned)rect->width + 15) >> 4;
            mbs *= ((unsigned)rect->height + 15) >> 4;
            if (read_ue(&b) >= mbs)
                return -1;
            kind = read_ue(&b);
            if (kind > 9 || ((kind >= 5 ? kind - 5 : kind) & ~2U))
                return -1;
            if (read_ue(&b) != 0 || b.failed)
                return -1;
            if (type == 5) {
                if ((kind >= 5 ? kind - 5 : kind) != 2 || !ref)
                    return -1;
                has_idr = 1;
            } else if (type == 1) {
                has_other = 1;
            }
            have_slice = 1;
        } else if (type > 12 || !((0x1240U >> type) & 1)) {
            return -1;
        }
        at = end;
    }
    if (!have_slice || (has_idr && has_other) || (flags & 1) != (unsigned)has_idr)
        return -1;
    if (have_params)
        return has_idr && have_params == 3 ? 0 : -1;
    return first ? -1 : 0;
}

/* ---- Protocol ---- */

struct packet { unsigned type, length, sequence, pts, flags; };

struct session { int started, running; unsigned sequence, pts; struct rect rect; };
struct client { ms_t hello_time, heartbeat_time; unsigned counter; int said_hello; unsigned version; };

static int parse_header(const unsigned char *raw, struct packet *p, unsigned version)
{
    unsigned wanted;

    if (raw[0] != 'D' || raw[1] != 'C' || raw[2] != 'V' || raw[3] != '1')
        return -1;
    p->type = big32(raw + 4);
    p->length = big32(raw + 8);
    p->sequence = big32(raw + 12);
    p->pts = big32(raw + 16);
    p->flags = big32(raw + 20);
    if (p->type == T_FRAME)
        return p->length - 1 >= MAX_FRAME || p->flags > 1 || (version != 3 && (!p->sequence || p->sequence > MAX_SEQUENCE)) ? -1 : 0;
    if (p->sequence || p->pts || p->flags)
        return -1;
    switch (p->type) {
    case T_START: wanted = 24; break;
    case T_STOP: case T_CLOSE: wanted = 0; break;
    case T_HELLO: case T_BACKGROUND: wanted = 16; break;
    case T_LOG: wanted = LOG_REQUEST; break;
    case T_HEARTBEAT: wanted = 20; break;
    default: return -1;
    }
    return p->length == wanted ? 0 : -1;
}

static int parse_start(const unsigned char *data, struct rect *rect)
{
    static const int PROFILES[3][4] = {{640, 240, 640, 240}, {0, 0, FRAME_WIDTH, FRAME_HEIGHT}, {0, 0, FRAME_WIDTH, FRAME_HEIGHT}};
    unsigned profile = big32(data + 20);
    int i;

    if (profile > 2)
        return -1;
    for (i = 0; i < 4; i++)
        if ((int)big32(data + i * 4) != PROFILES[profile][i])
            return -1;
    if (big32(data + 16) != FPS)
        return -1;
    rect->x = PROFILES[profile][0];
    rect->y = PROFILES[profile][1];
    rect->width = PROFILES[profile][2];
    rect->height = PROFILES[profile][3];
    rect->fps = FPS;
    return 0;
}

static int acknowledge(int fd, const struct packet *p, int result, ms_t deadline)
{
    unsigned char reply[HEADER + ACK];

    if (p->type == T_FRAME && result) {
        display_close();
        deadline = now_ms() + ACK_MS;
    }
    memcpy(reply, "DCV1", 4);
    put32(reply + 4, p->type | 0x80);
    put32(reply + 8, ACK);
    put32(reply + 12, p->sequence);
    put32(reply + 16, p->pts);
    put32(reply + 20, 0);
    put32(reply + 24, (unsigned)(result < 0 ? -result : result));
    if (p->type == T_FRAME && !result)
        submissions++;
    put32(reply + 32, submissions);
    put32(reply + 28, media_flags());
    put32(reply + 36, d.last_pts);
    return transfer(fd, reply, sizeof reply, 1, deadline);
}

static int accept_packet(struct session *s, const struct packet *p, const unsigned char *data)
{
    switch (p->type) {
    case T_START:
        if (s->started || parse_start(data, &s->rect))
            return -1;
        s->started = 1;
        s->running = 0;
        s->sequence = 0;
        s->pts = 0;
        return 0;
    case T_FRAME:
        if (!s->started || p->sequence - s->sequence != 1)
            return E_PARSER;
        if (s->running && p->pts - s->pts > MAX_PTS_STEP)
            return E_PARSER;
        if (check_access_unit(data, p->length, &s->rect, !s->running, p->flags))
            return E_PARSER;
        s->running = 1;
        s->sequence = p->sequence;
        s->pts = p->pts;
        return 0;
    case T_STOP:
        memset(s, 0, sizeof *s);
        return 0;
    default:
        return -1;
    }
}

/* The HMI publishes its background state in a shared page; requests go through a seqlock in it. */
enum { BG_OK, BG_BAD_REQUEST, BG_NO_FILE, BG_NOT_READY, BG_BUSY, BG_WRONG_OWNER, BG_API_ERROR, BG_CLOCK_ERROR, BG_RELEASE_ERROR, BG_TORN };
enum { PAGE_MAGIC = 0x31474244, PAGE_VERSION = 1, PAGE_SIZE_FIELD = 128, RENEW_CAPABILITY = 0x52575642, HOLD_MS = 5000 };

static void bound_next_call(void)
{
    static const unsigned long long limit = 10000000ULL;

    TimerTimeout(CLOCK_MONOTONIC, TIMEOUT_SEND_REPLY, NULL, &limit, NULL);
}

static int background_control(int fd, unsigned char *data)
{
    volatile unsigned *page = NULL;
    struct timespec now;
    unsigned op = big32(data + 4), nonce = big32(data + 8), hold = big32(data + 12), base, reported_op = 0xffffffffU;
    int code = BG_BAD_REQUEST, file = -1, i, tries;

    if (big32(data) != 1)
        goto cleanup;
    reported_op = op;
    if (op > 3)
        goto cleanup;
    if (op == 0 ? nonce != 0 : (int)nonce <= 0)
        goto cleanup;
    if (hold != (op == 1 || op == 3 ? (unsigned)HOLD_MS : op == 0 && hold == 1 ? 1U : 0U))
        goto cleanup;
    if (op == 0 && hold == 1 && cleanup_problem) {
        code = BG_RELEASE_ERROR;
        goto reply;
    }
    bound_next_call();
    file = open(BACKGROUND_PAGE, O_RDWR);
    if (file < 0) {
        code = BG_NO_FILE;
        goto cleanup;
    }
    bound_next_call();
    if (lseek(file, 0, SEEK_END) != 4096) {
        code = BG_API_ERROR;
        goto cleanup;
    }
    bound_next_call();
    {
        void *mapped = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, file, 0);
        if (mapped == (void *)-1 || !mapped) {
            code = BG_API_ERROR;
            goto cleanup;
        }
        page = mapped;
    }
    if (clock_gettime(CLOCK_MONOTONIC, &now)) {
        code = BG_CLOCK_ERROR;
        goto cleanup;
    }
    code = BG_NOT_READY;
    if (page[0] != PAGE_MAGIC || page[1] != PAGE_VERSION || page[2] != PAGE_SIZE_FIELD || (int)page[10] <= 0 || !page[18])
        goto cleanup;
    {
        unsigned seconds = (unsigned)now.tv_sec - page[12];
        if (seconds > 1 || seconds * 1000000000U + (unsigned)now.tv_nsec - page[13] > 500000000U)
            goto cleanup;
    }
    if (op == 0) {
        code = BG_OK;
        goto cleanup;
    }
    base = page[3];
    code = BG_BUSY;
    if ((base & 1) || base != page[8] || base >= 0xfffffffcU)
        goto cleanup;
    if (op == 1) {
        if (page[9] == 1)
            goto cleanup;
    } else {
        if (op == 3) {
            if (page[9] != 1) {
                code = BG_WRONG_OWNER;
                goto cleanup;
            }
            if (page[31] != RENEW_CAPABILITY) {
                code = BG_NOT_READY;
                goto cleanup;
            }
        }
        if (page[20] != nonce) {
            code = BG_WRONG_OWNER;
            goto cleanup;
        }
    }
    page[3] = base + 1;
    __sync_synchronize();
    page[4] = op == 1 ? 1 : op == 3 ? 2 : 0;
    page[5] = page[10];
    page[6] = (unsigned)now.tv_sec;
    page[7] = (unsigned)now.tv_nsec;
    page[19] = page[18];
    page[20] = nonce;
    __sync_synchronize();
    page[3] = base + 2;
    code = BG_OK;
cleanup:
    memset(data + 40, 0, BG_SNAPSHOT);
    if (page) {
        for (tries = 3;; ) {
            unsigned first = page[26];
            if (!(first & 1)) {
                __sync_synchronize();
                for (i = 0; i < BG_SNAPSHOT / 4; i++)
                    put32(data + 40 + i * 4, page[i]);
                __sync_synchronize();
                if (page[26] == first)
                    break;
            }
            if (!--tries) {
                code = BG_TORN;
                break;
            }
        }
        bound_next_call();
        if (munmap((void *)page, 4096)) {
            code = BG_RELEASE_ERROR;
            stopped = 1;
            goto reply;
        }
    }
    if (file >= 0) {
        bound_next_call();
        if (close(file)) {
            code = BG_RELEASE_ERROR;
            stopped = 1;
        }
    }
reply:
    TimerTimeout(CLOCK_MONOTONIC, 0, NULL, NULL, NULL);
    memcpy(data, "DCV1", 4);
    put32(data + 4, 0x8a);
    put32(data + 8, 0x90);
    memset(data + 12, 0, 12);
    put32(data + 24, 1);
    memcpy(data + 28, "BGP1", 4);
    put32(data + 32, reported_op);
    put32(data + 36, (unsigned)code);
    if (transfer(fd, data, BG_REPLY, 1, now_ms() + ACK_MS))
        return -1;
    return code == BG_RELEASE_ERROR ? -1 : 0;
}

/* The end of one of this module's two logs, so they can be collected without a shell on the instrument,
 * or this program's version. The reply is empty for a log that does not exist yet. */
static int send_log(int fd, unsigned char *data)
{
    static const char *const FILES[] = {RUN_DIR "/usb.log", RUN_DIR "/cast.log"};
    unsigned which = big32(data + 4), size = 0;
    int file = big32(data) == 1 && which < 2 ? open(FILES[which], O_RDONLY) : -1;

    if (big32(data) == 1 && which == LOG_VERSION) {
        size = (unsigned)strlen(DASHCAST_VERSION);
        memcpy(data + HEADER, DASHCAST_VERSION, size);
    }
    if (file >= 0) {
        long length = lseek(file, 0, SEEK_END);

        if (length >= 0 && lseek(file, length > LOG_TAIL ? length - LOG_TAIL : 0, SEEK_SET) >= 0) {
            for (;;) {
                ssize_t got = read(file, data + HEADER + size, LOG_TAIL - size);

                if (got < 0 && errno == EINTR)
                    continue;
                if (got <= 0)
                    break;
                size += (unsigned)got;
                if (size == LOG_TAIL)
                    break;
            }
        }
        close(file);
    }
    memcpy(data, "DCV1", 4);
    put32(data + 4, T_LOG | 0x80);
    put32(data + 8, size);
    memset(data + 12, 0, 12);
    return transfer(fd, data, HEADER + size, 1, now_ms() + ACK_MS);
}

/* Returns 0 to keep listening; nonzero stops the service. */
static int connection(int fd, unsigned char *buffer)
{
    struct session s;
    struct client c;
    struct packet p;
    unsigned char header[HEADER], offer[HEADER + OFFER];
    ms_t start = now_ms(), last_activity = 0, now, deadline;
    int rc;

    memset(&s, 0, sizeof s);
    memset(&c, 0, sizeof c);
    /* The client refuses a new session whose count does not start at zero. */
    submissions = 0;
    /* The offer once carried an identity; it is all zero now, and the client checks that it is. */
    memset(offer, 0, sizeof offer);
    memcpy(offer, "DCV1", 4);
    put32(offer + 4, 0x85);
    put32(offer + 8, OFFER);
    if (transfer(fd, offer, sizeof offer, 1, start + IDLE_MS))
        return 0;
    while (!stopped) {
        now = now_ms();
        if (c.version) {
            if (!(now >= c.heartbeat_time && now - c.heartbeat_time <= HEARTBEAT_MS - 1))
                break;
            deadline = c.heartbeat_time + HEARTBEAT_MS;
            if (c.version != 3 && c.hello_time + SESSION_MS < deadline)
                deadline = c.hello_time + SESSION_MS;
        } else {
            deadline = start + HANDSHAKE_MS;
        }
        if (s.started) {
            ms_t idle = c.version == 3 ? c.heartbeat_time + HEARTBEAT_MS
                      : c.version == 2 ? last_activity + SESSION_MS : last_activity + IDLE_MS;
            if (idle < deadline)
                deadline = idle;
        }
        if (transfer(fd, header, HEADER, 0, deadline) || parse_header(header, &p, c.version) ||
            transfer(fd, buffer, p.length, 0, deadline))
            break;
        if (p.type == T_BACKGROUND) {
            if (background_control(fd, buffer))
                break;
            continue;
        }
        if (p.type == T_LOG) {
            if (send_log(fd, buffer))
                break;
            continue;
        }
        if (!c.version) {
            now = now_ms();
            if (p.type != T_HELLO || c.said_hello || big32(buffer) || big32(buffer + 4) || memcmp(buffer + 8, "DCV1", 4) ||
                big32(buffer + 12) - 1 > 2) {
                acknowledge(fd, &p, 2, now + ACK_MS);
                break;
            }
            c.said_hello = 1;
            c.hello_time = now;
            c.heartbeat_time = now;
            c.counter = 0;
            c.version = big32(buffer + 12);
            LOG("DCV1_HELLO version=%u\n", c.version);
            if (acknowledge(fd, &p, 0, now_ms() + ACK_MS))
                break;
            continue;
        }
        now = now_ms();
        if (p.type != T_HEARTBEAT && !(now >= c.heartbeat_time && now - c.heartbeat_time <= HEARTBEAT_MS - 1))
            break;
        if (p.type == T_HEARTBEAT) {
            if (!(now >= c.heartbeat_time && now - c.heartbeat_time <= HEARTBEAT_MS - 1) || big32(buffer) || big32(buffer + 4) || big32(buffer + 8) || big32(buffer + 12) ||
                (c.version != 3 && c.counter == 0xffffffffU) || big32(buffer + 16) != c.counter + 1) {
                acknowledge(fd, &p, 2, now_ms() + ACK_MS);
                break;
            }
            c.counter = big32(buffer + 16);
            c.heartbeat_time = now;
            if (acknowledge(fd, &p, 0, now_ms() + ACK_MS))
                break;
            continue;
        }
        if (p.type == T_CLOSE)
            break;
        rc = accept_packet(&s, &p, buffer);
        if (rc) {
            LOG("DCV1_PACKET_REFUSED type=%u rc=%d\n", p.type, rc);
            acknowledge(fd, &p, rc, now_ms() + ACK_MS);
            return display_close();
        }
        rc = 0;
        if (p.type == T_START) {
            rc = display_open(&s.rect);
        } else if (p.type == T_FRAME) {
            rc = display_frame(buffer, p.length, p.pts);
            if (rc && rc != E_SUBMIT)
                rc = E_WINDOW;
        } else if (p.type == T_STOP) {
            rc = display_close();
        }
        last_activity = now_ms();
        if (acknowledge(fd, &p, rc, now_ms() + ACK_MS) || rc)
            break;
    }
    return display_close();
}

static int parse_number(const char *text, unsigned long limit, unsigned long *value)
{
    char *end;

    *value = strtoul(text, &end, 10);
    return *text && !*end && *value <= limit ? 0 : -1;
}

static int serve(const char *address, const char *port_text, const char *peer_text, int ready_fd)
{
    struct sockaddr_in local, peer, from;
    unsigned char *buffer;
    unsigned long port;
    socklen_t size;
    int server, client, yes = 1, rc;

    if (parse_number(port_text, 0xffff, &port))
        return 2;
    memset(&local, 0, sizeof local);
    memset(&peer, 0, sizeof peer);
    local.sin_family = AF_INET;
    local.sin_port = (unsigned short)(((port & 0xff) << 8) | (port >> 8));
    if (inet_pton(AF_INET, address, &local.sin_addr) != 1 || inet_pton(AF_INET, peer_text, &peer.sin_addr) != 1 ||
        !local.sin_addr.s_addr || !peer.sin_addr.s_addr)
        return 1;
    server = socket(AF_INET, SOCK_STREAM, 0);
    if (server < 0)
        return 1;
    setsockopt(server, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof yes);
    if (fcntl(server, F_SETFL, O_NONBLOCK) == -1 || bind(server, (const struct sockaddr *)&local, sizeof local) || listen(server, 1)) {
        LOG("DCV1_PORT_FAILED errno=%d\n", errno);
        close(server);
        return 1;
    }
    buffer = malloc(MAX_FRAME);
    if (!buffer) {
        close(server);
        return 1;
    }
    LOG("DCV1_PORT_OPEN port=%lu\n", port);
    if (ready_fd >= 0) {
        write(ready_fd, "R", 1);
        close(ready_fd);
    }
    rc = 0;
    while (!stopped) {
        if (wait_fd(server, POLLIN, ~0ULL))
            break;
        size = sizeof from;
        client = accept(server, (struct sockaddr *)&from, &size);
        if (client < 0) {
            if (errno == EINTR || errno == EAGAIN)
                continue;
            break;
        }
        if (from.sin_addr.s_addr != peer.sin_addr.s_addr) {
            close(client);
            continue;
        }
        if (fcntl(client, F_SETFL, O_NONBLOCK) == -1 || setsockopt(client, IPPROTO_TCP, TCP_NODELAY, &yes, sizeof yes)) {
            close(client);
            break;
        }
        rc = connection(client, buffer);
        close(client);
        if (rc)
            break;
    }
    free(buffer);
    close(server);
    return rc != 0;
}

int main(int argc, char **argv)
{
    signal(SIGINT, on_stop);
    signal(SIGTERM, on_stop);
    signal(SIGPIPE, SIG_IGN);
    if (argc >= 6 && !strcmp(argv[1], "--listen") && !strcmp(argv[4], "--client")) {
        unsigned long ready_fd;

        if (argc == 6)
            return serve(argv[2], argv[3], argv[5], -1);
        if (argc == 8 && !strcmp(argv[6], "--ready-fd") && !parse_number(argv[7], 1023, &ready_fd))
            return serve(argv[2], argv[3], argv[5], (int)ready_fd);
    }
    say("usage: dashcast-receiver --listen IPv4 PORT --client IPv4 [--ready-fd N]\n");
    return 2;
}
