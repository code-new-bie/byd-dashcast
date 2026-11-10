/* dashcast-usb: waits for USB role commands and carries them out, one at a time.
 *
 *   --boot                       start-up: put the module in RAM and become the service
 *   --run                        the service
 *   --request SCMD_USBMODE1|2    hand a command to the running service
 *   --show-result ID             show a result notice (run as a child of the service)
 *   --usb-check no-controller|no-devices|controllers-up   one USB query, also run as a child
 *
 * The service also owns the receiver. Rules every change has to keep:
 *   - Only processes this module started are signalled, and only with TERM.
 *   - A step that may still be running ends all further work; the guard stays behind so
 *     nothing can overlap the unknown child until the next reboot.
 *   - "Gone" is never assumed: a failed query is unknown, and a receiver is only known to
 *     have released its window and media context if it exited with status 0.
 */
#include "native_log.h"
#include "notice_glyphs.h"

#ifndef RUN_DIR
#define RUN_DIR "/fs/tmpfs/dashcast"
#endif
#ifndef PPS_PATH
#define PPS_PATH "/pps/spi_service/3507?delta"
#endif
#ifndef WINDOW_CONTROL
#define WINDOW_CONTROL "/pps/winmanager_service/control"
#endif
/* Tests move the system's own tools and device nodes under a sandbox. */
#ifndef PLATFORM
#define PLATFORM ""
#endif
#ifndef SCREEN_NODE
#define SCREEN_NODE "/dev/screen"
#endif
#ifndef RENDERER_NODE
#define RENDERER_NODE "/pps/services/multimedia/renderer/control"
#endif
#ifndef CAST_START_POLLS
#define CAST_START_POLLS 80
#endif
/* Releasing the media context stops hardware playback and can take seconds, so this budget is
 * long and is never cut short by force. */
#ifndef CAST_STOP_POLLS
#define CAST_STOP_POLLS 300
#endif
#ifndef MEDIA_POLLS
#define MEDIA_POLLS 80
#endif
/* At boot the USB stack may still be coming up, so a failed first look is repeated before giving up. */
#ifndef QUERY_ATTEMPTS
#define QUERY_ATTEMPTS 20
#endif
#ifndef INSTALL_DIR
#define INSTALL_DIR "/base/app/dashcast"
#endif
#ifndef RAM_ROOT
#define RAM_ROOT "/fs/tmpfs"
#endif
#ifndef BACKGROUND_PAGE
#define BACKGROUND_PAGE "/dev/shmem/dashcast-bg1"
#endif

#define LOCK_DIR RUN_DIR "/lock"
#define COMMANDS RUN_DIR "/commands"
#define USB_NODE "/dev/io-usb/io-usb"

enum { CMD_STATUS, CMD_HOST, CMD_NCM };
enum { TIMEOUT_SEND_REPLY = 0x50 };
enum { ROLE_LIMIT_S = 45, MAX_EXEC_S = 45, STEP_LIMIT_S = 4, UNCONFIRMED_RECOVERY = 3, TIMED_OUT = 124 };
enum { BUFFER = 4096, MAX_VALUES = 512, FRAME_MARK = 0x1c, FRAME_TEXT = 0x20, MARK_VALUE = 0xa1 };

static volatile int stopping;
static int stop_fd = -1;

static void pause_for(long seconds, long ns)
{
    struct timespec delay = {seconds, ns};
    nanosleep(&delay, NULL);
}

static int parse_command(const char *text)
{
    if (strcmp(text, "SCMD_USBMODE1") == 0)
        return CMD_HOST;
    if (strcmp(text, "SCMD_USBMODE2") == 0)
        return CMD_NCM;
    return 0;
}

/* How a child is set up before it runs: where its output goes and whether it uses the USB network stack. */
struct launch { const char *output; int private_network; };

#define NET RUN_DIR "/net"

static void prepare_child(const struct launch *how)
{
    int fd;

    if (how->output) {
        fd = open(how->output, O_WRONLY | O_CREAT | O_TRUNC, 0600);
        if (fd < 0 || dup2(fd, 1) < 0)
            _exit(127);
        close(fd);
    }
    if (how->private_network && setenv("SOCK", NET, 1))
        _exit(127);
}

/* Returns the child's exit code, 1 if it was killed by a signal or could not be waited for,
 * and 124 if it outlived the limit and could not be confirmed gone. */
static int execute(char *const argv[], const struct launch *how, unsigned seconds)
{
    struct timespec now, deadline;
    int status, i;
    pid_t pid, done;

    pid = fork();
    clock_gettime(CLOCK_MONOTONIC, &deadline);
    deadline.tv_sec += (long)seconds;
    if (pid < 0)
        return 1;
    if (pid == 0) {
        signal(SIGTERM, SIG_DFL);
        signal(SIGINT, SIG_DFL);
        if (how)
            prepare_child(how);
        execv(argv[0], argv);
        _exit(127);
    }
    for (;;) {
        done = waitpid(pid, &status, WNOHANG);
        if (done == pid)
            break;
        if (done < 0 && errno != EINTR)
            return 1;
        clock_gettime(CLOCK_MONOTONIC, &now);
        if (now.tv_sec > deadline.tv_sec || (now.tv_sec == deadline.tv_sec && now.tv_nsec >= deadline.tv_nsec)) {
            kill(pid, SIGTERM);
            pause_for(0, 100000000);
            do
                done = waitpid(pid, &status, WNOHANG);
            while (done < 0 && errno == EINTR);
            if (done == 0) {
                /* Never block here: a child stuck in a driver must not stall the service. */
                kill(pid, SIGKILL);
                for (i = 0; i < 10 && waitpid(pid, &status, WNOHANG) == 0; i++)
                    pause_for(0, 20000000);
            }
            say("ROLE_STEP_TIMED_OUT\n");
            return TIMED_OUT;
        }
        pause_for(0, 20000000);
    }
    return WIFEXITED(status) ? WEXITSTATUS(status) : 1;
}

/* ---- PPS frame: "@3507\ndata:json:{"len":N,"data":[...]}" with a UTF-16LE command name ---- */

struct cursor { const char *at, *end; };

static void skip_spaces(struct cursor *c)
{
    while (c->at < c->end && (*c->at == ' ' || *c->at == '\r' || *c->at == '\n' || *c->at == '\t'))
        c->at++;
}

static int take(struct cursor *c, char wanted)
{
    if (c->at == c->end || *c->at != wanted)
        return 0;
    c->at++;
    return 1;
}

static int is_digit(const struct cursor *c, const char *at)
{
    return at < c->end && *at >= '0' && *at <= '9';
}

static int take_number(struct cursor *c, unsigned limit, unsigned *value)
{
    unsigned n = 0;

    skip_spaces(c);
    if (!is_digit(c, c->at))
        return 0;
    if (*c->at == '0' && is_digit(c, c->at + 1))
        return 0;
    while (is_digit(c, c->at)) {
        n = n * 10 + (unsigned)(*c->at++ - '0');
        if (n > limit)
            return 0;
    }
    *value = n;
    return 1;
}

static int take_values(struct cursor *c, unsigned char *values, unsigned *count)
{
    unsigned value;

    *count = 0;
    skip_spaces(c);
    if (!take(c, '['))
        return 0;
    skip_spaces(c);
    if (take(c, ']'))
        return 1;
    for (;;) {
        if (*count == MAX_VALUES || !take_number(c, 255, &value))
            return 0;
        values[(*count)++] = (unsigned char)value;
        skip_spaces(c);
        if (take(c, ']'))
            return 1;
        if (!take(c, ','))
            return 0;
    }
}

static int header_is_frame(const char *line, size_t length)
{
    return (length == 5 && memcmp(line, "@3507", 5) == 0) || (length == 8 && memcmp(line, "[n]@3507", 8) == 0) ||
           (length == 6 && memcmp(line, "+@3507", 6) == 0);
}

/* Anything malformed is simply not a command. */
int parse_frame(const char *data, size_t size)
{
    unsigned char values[MAX_VALUES];
    char name[MAX_VALUES];
    struct cursor c;
    const char *newline;
    unsigned count = 0, declared = 0, seen = 0, name_bytes, i;
    size_t line;

    if (size == 0 || size > BUFFER || memchr(data, 0, size))
        return 0;
    newline = memchr(data, '\n', size);
    if (!newline || newline == data)
        return 0;
    line = (size_t)(newline - data);
    if (data[line - 1] == '\r')
        line--;
    if (!header_is_frame(data, line))
        return 0;
    c.at = newline + 1;
    c.end = data + size;
    skip_spaces(&c);
    if ((size_t)(c.end - c.at) < 10 || memcmp(c.at, "data:json:", 10))
        return 0;
    c.at += 10;
    skip_spaces(&c);
    if (!take(&c, '{'))
        return 0;
    do {
        skip_spaces(&c);
        if (!take(&c, '"'))
            return 0;
        if (c.end - c.at >= 4 && !memcmp(c.at, "len\"", 4)) {
            if (seen & 1)
                return 0;
            c.at += 4;
            skip_spaces(&c);
            if (!take(&c, ':') || !take_number(&c, MAX_VALUES, &declared))
                return 0;
            seen |= 1;
        } else if (c.end - c.at >= 5 && !memcmp(c.at, "data\"", 5)) {
            if (seen & 2)
                return 0;
            c.at += 5;
            skip_spaces(&c);
            if (!take(&c, ':') || !take_values(&c, values, &count))
                return 0;
            seen |= 2;
        } else {
            return 0;
        }
        skip_spaces(&c);
    } while (take(&c, ','));
    if (!take(&c, '}'))
        return 0;
    skip_spaces(&c);
    if (seen != 3 || c.at != c.end || declared != count || count < 34 || count > 62)
        return 0;
    if (values[FRAME_MARK] != MARK_VALUE || values[1] != count - FRAME_TEXT || (values[1] & 1))
        return 0;
    name_bytes = values[1];
    for (i = 0; i < name_bytes / 2; i++) {
        if (values[FRAME_TEXT + 2 * i] == 0 || values[FRAME_TEXT + 2 * i + 1] != 0)
            return 0;
        name[i] = (char)values[FRAME_TEXT + 2 * i];
    }
    name[i] = 0;
    return parse_command(name);
}

/* ---- USB query through the system's own libusbdi ---- */

struct usbd_connect_parm { const char *path; unsigned version; unsigned reserved[10]; };
struct usbd_hcd_info { unsigned reserved; char controller[32]; };

typedef int (*usbd_connect_fn)(struct usbd_connect_parm *, void **);
typedef int (*usbd_hcd_info_fn)(void *, unsigned, struct usbd_hcd_info *);
typedef int (*usbd_topology_fn)(void *, unsigned char, unsigned char *);
typedef int (*usbd_disconnect_fn)(void *);

enum { USBD_VERSION = 0x01010110, MAX_BUSES = 16, ENODEV_ = 19, TOPOLOGY_BYTES = 512, TOPOLOGY_SLOTS = 64 };
enum { XHCI_BUSES = 2 };

static int probe(const char *question)
{
    struct usbd_connect_parm parm;
    struct usbd_hcd_info info;
    unsigned char topology[TOPOLOGY_BYTES];
    void *library, *connection = NULL;
    usbd_connect_fn connect_fn;
    usbd_hcd_info_fn info_fn;
    usbd_topology_fn topology_fn;
    usbd_disconnect_fn disconnect_fn;
    int want_empty = strcmp(question, "no-devices") == 0;
    int failed = 0, rc;
    unsigned bus, slot, controllers = 0, devices = 0;

    library = dlopen("libusbdi.so.2", RTLD_NOW | RTLD_LOCAL);
    if (!library)
        return 1;
    connect_fn = (usbd_connect_fn)dlsym(library, "usbd_connect");
    info_fn = (usbd_hcd_info_fn)dlsym(library, "usbd_hcd_ext_info");
    topology_fn = (usbd_topology_fn)dlsym(library, "usbd_topology_ext");
    disconnect_fn = (usbd_disconnect_fn)dlsym(library, "usbd_disconnect");
    memset(&parm, 0, sizeof parm);
    parm.path = USB_NODE;
    parm.version = USBD_VERSION;
    if (!connect_fn || !info_fn || !topology_fn || !disconnect_fn || connect_fn(&parm, &connection)) {
        dlclose(library);
        return 1;
    }
    for (bus = 0; bus < MAX_BUSES && !failed; bus++) {
        memset(&info, 0, sizeof info);
        rc = info_fn(connection, bus, &info);
        if (rc == ENODEV_ || rc == ENOENT)
            continue;
        if (rc || strncmp(info.controller, "XHCI", 4)) {
            failed = 1;
            break;
        }
        controllers++;
        if (want_empty) {
            memset(topology, 0xff, sizeof topology);
            if (topology_fn(connection, (unsigned char)bus, topology)) {
                failed = 1;
                break;
            }
            for (slot = 0; slot < TOPOLOGY_SLOTS; slot++)
                if (topology[slot * 8] != 0xff)
                    devices++;
        }
    }
    if (disconnect_fn(connection))
        failed = 1;
    dlclose(library);
    if (strcmp(question, "no-controller") == 0)
        return failed || controllers != 0;
    if (want_empty ? devices != 0 : strcmp(question, "controllers-up") != 0)
        return 1;
    return failed || controllers != XHCI_BUSES;
}

/* ---- Result notice: a 640x96 window for about 1.2 s, shown from a short-lived child ---- */

enum { NOTICE_X = 640, NOTICE_Y = 312, NOTICE_W = 640, NOTICE_H = 96, DISPLAY_W = 1920, DISPLAY_H = 720, MAX_DISPLAYS = 8 };
enum { TEXT_ROW = 28, GLYPH_W = 32, GLYPH_H = 40, GLYPH_ROW_BYTES = 4, FULL_LINE_GLYPHS = 20 };
enum { NOTICE_HOST_OK = 1, NOTICE_NCM_OK = 3, NOTICE_FAILED = 4, NOTICE_LIMIT_S = 3, NOTICE_ALARM_S = 5 };
enum { CONTEXT_MANAGER = 1, FORMAT_RGBA8888 = 8, USAGE_NATIVE = 4 };
enum { PROP_BUFFER_SIZE = 0x05, PROP_DISPLAY = 0x0b, PROP_FORMAT = 0x0e, PROP_ID = 0x14, PROP_POINTER = 0x22, PROP_POSITION = 0x23,
       PROP_RENDER_BUFFERS = 0x25, PROP_SIZE = 0x28, PROP_STRIDE = 0x2c, PROP_USAGE = 0x30, PROP_VISIBLE = 0x33, PROP_ZORDER = 0x36,
       PROP_DISPLAY_COUNT = 0x3b, PROP_DISPLAYS = 0x3c };
enum screen_call { CREATE_CONTEXT, DESTROY_CONTEXT, CREATE_WINDOW, DESTROY_WINDOW, CREATE_GROUP, CREATE_BUFFERS, SET_WINDOW_IV,
                   SET_WINDOW_PV, SET_WINDOW_CV, GET_WINDOW_IV, GET_WINDOW_PV, GET_CONTEXT_IV, GET_CONTEXT_PV, GET_DISPLAY_IV,
                   GET_BUFFER_PV, GET_BUFFER_IV, POST_WINDOW, FLUSH, SCREEN_CALLS };

static const char *const SCREEN_NAMES[SCREEN_CALLS] = {
    "screen_create_context", "screen_destroy_context", "screen_create_window", "screen_destroy_window",
    "screen_create_window_group", "screen_create_window_buffers", "screen_set_window_property_iv",
    "screen_set_window_property_pv", "screen_set_window_property_cv", "screen_get_window_property_iv",
    "screen_get_window_property_pv", "screen_get_context_property_iv", "screen_get_context_property_pv",
    "screen_get_display_property_iv", "screen_get_buffer_property_pv", "screen_get_buffer_property_iv",
    "screen_post_window", "screen_flush_context"};

typedef int (*screen_fn)(long, long, long, long, long);

static void *screen_library;
static screen_fn screen_api[SCREEN_CALLS];
static long notice_context, notice_window;
static char notice_name[64];

/* The display service can stall, so every call to it has a one-second limit. */
static int screen(enum screen_call call, long a, long b, long c, long d)
{
    static const unsigned long long limit = 1000000000ULL;
    int rc, saved;

    TimerTimeout(CLOCK_MONOTONIC, TIMEOUT_SEND_REPLY, NULL, &limit, NULL);
    rc = screen_api[call](a, b, c, d, 0);
    saved = errno;
    TimerTimeout(CLOCK_MONOTONIC, 0, NULL, NULL, NULL);
    errno = saved;
    return rc;
}

static int window_ints(int property, const int *values)
{
    return screen(SET_WINDOW_IV, notice_window, property, (long)values, 0);
}

static int set_window_int(int property, int value)
{
    return window_ints(property, &value);
}

static const unsigned char *notice_text(int id)
{
    return id == NOTICE_HOST_OK ? NOTICE_HOST_TEXT : id == NOTICE_NCM_OK ? NOTICE_NCM_TEXT : id == NOTICE_FAILED ? NOTICE_FAILED_TEXT : NULL;
}

/* One colour per result (0xRRGGBB), so the mode can be told at a glance. */
static unsigned notice_background(int id)
{
    return id == NOTICE_HOST_OK ? 0x1f5fa8 : id == NOTICE_NCM_OK ? 0x0b7a64 : 0xb3262e;
}

/* Screen's RGBA8888 pixel is a little-endian 0xAARRGGBB word, so blue is the first byte in memory. */
static void put_pixel(unsigned char *pixel, unsigned rgb)
{
    pixel[0] = (unsigned char)rgb;
    pixel[1] = (unsigned char)(rgb >> 8);
    pixel[2] = (unsigned char)(rgb >> 16);
    pixel[3] = 0xff;
}

static void draw_notice(unsigned char *pixels, int stride, const unsigned char *text, unsigned background)
{
    int x, y, glyph, length = (int)strlen((const char *)text);
    int left = (FULL_LINE_GLYPHS - length) * (GLYPH_W / 2);

    for (y = 0; y < NOTICE_H; y++) {
        unsigned char *row = pixels + y * stride;
        for (x = 0; x < NOTICE_W; x++)
            put_pixel(row + x * 4, background);
    }
    for (glyph = 0; glyph < length; glyph++) {
        const unsigned char *bits = NOTICE_GLYPHS[text[glyph] - 1];
        for (y = 0; y < GLYPH_H; y++) {
            unsigned char *row = pixels + (TEXT_ROW + y) * stride + (left + glyph * GLYPH_W) * 4;
            for (x = 0; x < GLYPH_W; x++) {
                if (bits[y * GLYPH_ROW_BYTES + (x >> 3)] & (0x80 >> (x & 7)))
                    put_pixel(row + x * 4, 0xf1f5f9);
            }
        }
    }
}

static int load_screen(void)
{
    int i;

    screen_library = dlopen("libscreen.so.1", RTLD_NOW | RTLD_LOCAL);
    if (!screen_library)
        return -1;
    for (i = 0; i < SCREEN_CALLS; i++) {
        screen_api[i] = (screen_fn)dlsym(screen_library, SCREEN_NAMES[i]);
        if (!screen_api[i])
            return -1;
    }
    return 0;
}

/* Exactly one 1920x720 display is the instrument; anything else is not a screen to draw on. */
static int notice_open(void)
{
    long displays[MAX_DISPLAYS], chosen = 0;
    int count = 0, i, size[2];
    int position[2] = {NOTICE_X, NOTICE_Y}, extent[2] = {NOTICE_W, NOTICE_H};

    snprintf(notice_name, sizeof notice_name, "dashcast_%ld", (long)getpid());
    if (load_screen() || screen(CREATE_CONTEXT, (long)&notice_context, CONTEXT_MANAGER, 0, 0) ||
        screen(CREATE_WINDOW, (long)&notice_window, notice_context, 0, 0) ||
        screen(GET_CONTEXT_IV, notice_context, PROP_DISPLAY_COUNT, (long)&count, 0) || count < 1 || count > MAX_DISPLAYS ||
        screen(GET_CONTEXT_PV, notice_context, PROP_DISPLAYS, (long)displays, 0))
        return -1;
    for (i = 0; i < count; i++) {
        if (screen(GET_DISPLAY_IV, displays[i], PROP_SIZE, (long)size, 0))
            return -1;
        if (size[0] == DISPLAY_W && size[1] == DISPLAY_H) {
            if (chosen)
                return -1;
            chosen = displays[i];
        }
    }
    if (!chosen || screen(SET_WINDOW_PV, notice_window, PROP_DISPLAY, (long)&chosen, 0) ||
        screen(SET_WINDOW_CV, notice_window, PROP_ID, (long)(strlen(notice_name) + 1), (long)notice_name) ||
        screen(CREATE_GROUP, notice_window, (long)notice_name, 0, 0) ||
        window_ints(PROP_POSITION, position) || window_ints(PROP_SIZE, extent) || set_window_int(PROP_ZORDER, 1))
        return -1;
    return 0;
}

static int notice_paint(const unsigned char *text, unsigned background)
{
    int extent[2] = {NOTICE_W, NOTICE_H}, stride = 0;
    long buffers[3] = {0, 0, 0};
    unsigned char *pixels = NULL;

    if (set_window_int(PROP_VISIBLE, 0) || set_window_int(PROP_FORMAT, FORMAT_RGBA8888) ||
        set_window_int(PROP_USAGE, USAGE_NATIVE) || window_ints(PROP_BUFFER_SIZE, extent) ||
        screen(CREATE_BUFFERS, notice_window, 1, 0, 0) ||
        screen(GET_WINDOW_PV, notice_window, PROP_RENDER_BUFFERS, (long)buffers, 0) ||
        screen(GET_BUFFER_PV, buffers[0], PROP_POINTER, (long)&pixels, 0) ||
        screen(GET_BUFFER_IV, buffers[0], PROP_STRIDE, (long)&stride, 0) || !pixels || NOTICE_W * 4 > stride)
        return -1;
    draw_notice(pixels, stride, text, background);
    if (screen(POST_WINDOW, notice_window, buffers[0], 0, 0) || set_window_int(PROP_VISIBLE, 1) ||
        screen(FLUSH, notice_context, 0, 0, 0))
        return -1;
    return 0;
}

/* The window manager may move a new window, so the place is set and read back each time. */
static int notice_place(void)
{
    int position[2] = {NOTICE_X, NOTICE_Y}, extent[2] = {NOTICE_W, NOTICE_H}, got_position[2], got_extent[2];

    if (window_ints(PROP_SIZE, extent) || window_ints(PROP_POSITION, position) || screen(FLUSH, notice_context, 0, 0, 0) ||
        screen(GET_WINDOW_IV, notice_window, PROP_POSITION, (long)got_position, 0) ||
        screen(GET_WINDOW_IV, notice_window, PROP_SIZE, (long)got_extent, 0))
        return -1;
    return got_position[0] == NOTICE_X && got_position[1] == NOTICE_Y && got_extent[0] == NOTICE_W && got_extent[1] == NOTICE_H ? 0 : -1;
}

static int notice_raise(void)
{
    static const unsigned long long limit = 1000000000ULL;
    char line[80];
    int length = snprintf(line, sizeof line, "%s::12\n", notice_name), fd;
    ssize_t written;

    TimerTimeout(CLOCK_MONOTONIC, TIMEOUT_SEND_REPLY, NULL, &limit, NULL);
    fd = open(WINDOW_CONTROL, O_WRONLY | O_NONBLOCK);
    TimerTimeout(CLOCK_MONOTONIC, 0, NULL, NULL, NULL);
    if (fd < 0)
        return -1;
    TimerTimeout(CLOCK_MONOTONIC, TIMEOUT_SEND_REPLY, NULL, &limit, NULL);
    written = write(fd, line, (size_t)length);
    TimerTimeout(CLOCK_MONOTONIC, 0, NULL, NULL, NULL);
    close(fd);
    return written == length ? notice_place() : -1;
}

static int notice_close(void)
{
    int failed = 0;

    if (notice_window && screen(DESTROY_WINDOW, notice_window, 0, 0, 0))
        failed = 1;
    if (notice_context && screen(DESTROY_CONTEXT, notice_context, 0, 0, 0))
        failed = 1;
    TimerTimeout(CLOCK_MONOTONIC, 0, NULL, NULL, NULL);
    if (screen_library)
        dlclose(screen_library);
    screen_library = NULL;
    notice_window = notice_context = 0;
    return failed;
}

static void on_signal(int number);

static int show_notice(int id)
{
    const unsigned char *text = notice_text(id);
    struct timespec now, until;
    int failed;

    if (!text)
        return 2;
    signal(SIGTERM, on_signal);
    signal(SIGINT, on_signal);
    alarm(NOTICE_ALARM_S);
    failed = notice_open() || notice_paint(text, notice_background(id)) || notice_place();
    if (failed) {
        notice_close();
    } else {
        clock_gettime(CLOCK_MONOTONIC, &until);
        until.tv_sec += 1;
        until.tv_nsec += 200000000;
        if (until.tv_nsec >= 1000000000) {
            until.tv_sec += 1;
            until.tv_nsec -= 1000000000;
        }
        while (!failed && !stopping) {
            clock_gettime(CLOCK_MONOTONIC, &now);
            if (now.tv_sec > until.tv_sec || (now.tv_sec == until.tv_sec && now.tv_nsec >= until.tv_nsec))
                break;
            failed = notice_raise() != 0;
            pause_for(0, 100000000);
        }
    }
    failed |= notice_close();
    alarm(0);
    LOG("NOTICE_DONE failed=%d\n", failed);
    return failed != 0;
}

/* ---- USB role ---- */

#define INSTRUMENT_IP "192.168.77.1"
#define PEER_IP "192.168.77.2"
/* Must equal DashcastPorts.PROJECTION in the app; tools/tests/test_native_usb.py checks it. */
#define CAST_PORT "17475"
#define XHCI_PORTS "ioport=0x48890000,irq=108,ioport=0x488d0000,irq=110"
#define HOST_NODE PLATFORM "/dev/io-usb/devu-omap5-xhci.so"
#define DCD RUN_DIR "/dcd/io-usb"
#define NET_SOCKET NET "/dev/socket"
#define LINK_MARK RUN_DIR "/ncm-connected"
#define ROLE_GUARD RUN_DIR "/role-operation"
#define RECEIVER RUN_DIR "/dashcast-receiver"
#define HOST_ARGS "-d omap5-xhci " XHCI_PORTS
#define DCD_ARGS "-n " DCD " -d usbncm-omap543x-dwcotg3 ioport=0x488d0000,irq=110,linkdown"
#define NET_ARGS "-i7 -d usbdnet protocol=ncm,path=" DCD ",mac=020000000701,usbdnet_mac=020000000702,wait=2 -ptcpip prefix=" NET
#define IFCONFIG PLATFORM "/sbin/ifconfig"
#define ULINK PLATFORM "/sbin/ulink_ctrl"

enum { NODE_POLLS = 60, STACK_STOP_POLLS = 60, DESTROY_ATTEMPTS = 20, DISCONNECT_SETTLE_TICKS = 6, TEXT_LIMIT = 128 * 1024 };

static int role_timed_out, role_watch_stop;
static ms_t role_deadline;
static char self_path[PATH_MAX];

static int fail(const char *token)
{
    say(token);
    return 1;
}

static int step_out_of_time(void)
{
    if (role_timed_out)
        return 1;
    if ((role_watch_stop && stopping) || now_ms() >= role_deadline) {
        role_timed_out = 1;
        say("USB_BACKEND_COMMAND_TIMEOUT\n");
        return 1;
    }
    return 0;
}

static void log_step(const char *name, int rc, ms_t began)
{
    const char *base = strrchr(name, '/');

    LOG("STEP %s rc=%d ms=%lu\n", base ? base + 1 : name, rc, (unsigned long)(now_ms() - began));
}

/* One step of a role change: at most STEP_LIMIT_S, and a timeout stops everything after it. */
static int run(char *const argv[], const struct launch *how)
{
    ms_t began = now_ms();
    int rc;

    if (step_out_of_time())
        return TIMED_OUT;
#ifdef COMMAND_WRAPPER
    {
        char *routed[20] = {COMMAND_WRAPPER, "--bounded", "4"};
        int i;

        for (i = 0; argv[i]; i++)
            routed[i + 3] = argv[i];
        routed[i + 3] = NULL;
        rc = execute(routed, how, STEP_LIMIT_S);
    }
#else
    rc = execute(argv, how, STEP_LIMIT_S);
#endif
    log_step(argv[0], rc, began);
    if (rc == TIMED_OUT) {
        role_timed_out = 1;
        say("USB_BACKEND_COMMAND_TIMEOUT\n");
    }
    return rc;
}

/* The four things a shell could not do. Tests route them through the fixture that simulates the system. */
#ifdef COMMAND_WRAPPER
static void tick(void)
{
    char *argv[] = {COMMAND_WRAPPER, "--tick", NULL};

    execute(argv, NULL, STEP_LIMIT_S);
}

static int usb_check(const char *question)
{
    char *argv[] = {COMMAND_WRAPPER, "--usb-check", (char *)question, NULL};

    return run(argv, NULL);
}

static int remove_role_guard(void)
{
    char *argv[] = {COMMAND_WRAPPER, "--rmdir", ROLE_GUARD, NULL};

    return run(argv, NULL);
}

static int stop_process(int pid)
{
    char number[16];
    char *argv[] = {"/bin/kill", "-TERM", number, NULL};

    snprintf(number, sizeof number, "%d", pid);
    return run(argv, NULL);
}

static int make_stack_directories(void)
{
    char *argv[] = {"/bin/mkdir", "-p", RUN_DIR "/dcd", NET, NULL};

    return run(argv, NULL);
}
#else
static void tick(void)
{
    pause_for(0, 50000000);
}

/* In its own process: a USB driver that stops answering must not stall the service. */
static int usb_check(const char *question)
{
    char *argv[] = {self_path, "--usb-check", (char *)question, NULL};

    return run(argv, NULL);
}

static int remove_role_guard(void)
{
    return step_out_of_time() ? TIMED_OUT : rmdir(ROLE_GUARD) != 0;
}

static int stop_process(int pid)
{
    return step_out_of_time() ? TIMED_OUT : kill(pid, SIGTERM) != 0;
}

static int make_stack_directories(void)
{
    if (mkdir(RUN_DIR "/dcd", 0700) && errno != EEXIST)
        return 1;
    return mkdir(NET, 0700) && errno != EEXIST;
}
#endif

static int exists(const char *path)
{
    return access(path, F_OK) == 0;
}

static int is_link(const char *path)
{
    char target[2];

    return readlink(path, target, sizeof target) >= 0;
}

/* The whole file with a terminator, or NULL; a file too long to hold is treated as unreadable. */
static char *load_text(const char *path)
{
    char *text = malloc(TEXT_LIMIT + 1);
    size_t used = 0;
    ssize_t got;
    int fd;

    if (!text)
        return NULL;
    fd = open(path, O_RDONLY);
    if (fd < 0) {
        free(text);
        return NULL;
    }
    while (used <= TEXT_LIMIT && (got = read(fd, text + used, TEXT_LIMIT + 1 - used)) != 0) {
        if (got < 0) {
            if (errno == EINTR)
                continue;
            used = TEXT_LIMIT + 1;
            break;
        }
        used += (size_t)got;
    }
    close(fd);
    if (used > TEXT_LIMIT) {
        free(text);
        return NULL;
    }
    text[used] = 0;
    return text;
}

static int is_blank(char c)
{
    return c == ' ' || c == '\t';
}

/* The next complete line with its leading blanks removed. A last line with no newline is not seen. */
static char *next_line(char **cursor)
{
    char *line = *cursor, *end = strchr(line, '\n');

    if (!end)
        return NULL;
    *end = 0;
    *cursor = end + 1;
    while (is_blank(*line))
        line++;
    return line;
}

static char *next_word(char **cursor)
{
    char *word = *cursor;

    while (is_blank(*word))
        word++;
    *cursor = word;
    while (**cursor && !is_blank(**cursor))
        (*cursor)++;
    if (**cursor)
        *(*cursor)++ = 0;
    return word;
}

/* What remains of a line, without blanks at either end. */
static char *rest_of_line(char *line)
{
    size_t size;

    while (is_blank(*line))
        line++;
    size = strlen(line);
    while (size && is_blank(line[size - 1]))
        line[--size] = 0;
    return line;
}

static int all_digits(const char *text)
{
    if (!*text)
        return 0;
    for (; *text; text++)
        if (*text < '0' || *text > '9')
            return 0;
    return 1;
}

static int has_word(const char *text, const char *word)
{
    size_t size = strlen(word);
    const char *at;

    for (at = text; (at = strstr(at, word)); at += size)
        if ((at == text || is_blank(at[-1])) && (!at[size] || is_blank(at[size])))
            return 1;
    return 0;
}

struct stacks { int host, dcd, net; };

/* Anything that looks like another owner of the controller fails the survey instead of being treated as absent. */
static int usb_survey(struct stacks *found)
{
    char *argv[] = {"/bin/pidin", "ar", NULL};
    struct launch how = {RUN_DIR "/processes", 0};
    char *text, *cursor, *line;
    int foreign = 0;

    memset(found, 0, sizeof *found);
    if (run(argv, &how))
        return 1;
    text = load_text(RUN_DIR "/processes");
    if (!text)
        return 1;
    cursor = text;
    while ((line = next_line(&cursor))) {
        char *number = next_word(&line), *program = next_word(&line), *arguments = rest_of_line(line);
        const char *name = strrchr(program, '/');
        int pid = (int)strtoul(number, NULL, 10);

        if (!all_digits(number))
            continue;
        name = name ? name + 1 : program;
        if (!strcmp(name, "io-usb")) {
            if (strcmp(arguments, HOST_ARGS) || found->host)
                foreign = 1;
            else
                found->host = pid;
        } else if (!strcmp(name, "io-usb-dcd")) {
            if (strcmp(arguments, DCD_ARGS) || found->dcd)
                foreign = 1;
            else
                found->dcd = pid;
        } else if (!strcmp(name, "io-pkt-v6-hc")) {
            if (!strcmp(arguments, NET_ARGS)) {
                if (found->net)
                    foreign = 1;
                else
                    found->net = pid;
            } else if (has_word(arguments, "-i7") || strstr(arguments, DCD) || strstr(arguments, NET)) {
                foreign = 1;
            }
        } else if (!strcmp(name, "io-usb-otg") || !strcmp(name, "usb-service")) {
            foreign = 1;
        }
    }
    free(text);
    return foreign || !found->host ? fail("USB_FOREIGN_STACK\n") : 0;
}

static int wait_for_node(const char *path)
{
    int polls;

    for (polls = 0; polls < NODE_POLLS; polls++) {
        if (exists(path))
            return 0;
        tick();
    }
    return 1;
}

static int stop_stack(int network)
{
    struct stacks now;
    int pid, still, polls;

    if (usb_survey(&now))
        return 1;
    pid = network ? now.net : now.dcd;
    if (!pid)
        return 0;
    if (stop_process(pid))
        return 1;
    for (polls = 0; polls < STACK_STOP_POLLS; polls++) {
        if (usb_survey(&now))
            return 1;
        still = network ? now.net : now.dcd;
        if (!still)
            return 0;
        if (still != pid)
            return 1;
        tick();
    }
    return fail("USB_STACK_STOP_UNCONFIRMED\n");
}

static const struct launch ON_USB_NETWORK = {NULL, 1};

/* 0 present, 1 absent, 2 unknown. */
static int interface_state(void)
{
    char *argv[] = {IFCONFIG, "-a", NULL};
    struct launch how = {RUN_DIR "/interfaces", 1};
    char *text, *cursor, *line;
    int state = 1;

    if (run(argv, &how))
        return 2;
    text = load_text(RUN_DIR "/interfaces");
    if (!text)
        return 2;
    cursor = text;
    while ((line = next_line(&cursor)))
        if (!strncmp(line, "ncm0:", 5))
            state = 0;
    free(text);
    return state;
}

/* The network driver refuses destruction while its USB disconnect is still pending, so the
 * logical disconnect is given time to drain with the cable attached. */
static int remove_interface(void)
{
    char *down[] = {IFCONFIG, "ncm0", "down", NULL}, *destroy[] = {IFCONFIG, "ncm0", "destroy", NULL};
    int attempts, destroy_rc, state = interface_state();

    if (state == 1) {
        say("NCM_INTERFACE_ALREADY_REMOVED\n");
        return 0;
    }
    if (state != 0)
        return fail("NCM_INTERFACE_UNKNOWN\n");
    if (run(down, &ON_USB_NETWORK))
        return 1;
    for (attempts = 0; attempts < DESTROY_ATTEMPTS; attempts++) {
        destroy_rc = run(destroy, &ON_USB_NETWORK);
        state = interface_state();
        if (state == 1) {
            say("NCM_INTERFACE_REMOVED\n");
            return 0;
        }
        if (state != 0)
            return fail("NCM_INTERFACE_UNKNOWN\n");
        if (destroy_rc == 0)
            return fail("NCM_INTERFACE_STILL_PRESENT\n");
        tick();
        tick();
    }
    return fail("NCM_DESTROY_UNCONFIRMED\n");
}

/* ---- Receiver ---- */

enum cast_state { CAST_NONE, CAST_RUNNING, CAST_CLEAN, CAST_UNCLEAN };

static struct { enum cast_state state; int pid, ready_fd, ready; } cast = {CAST_NONE, 0, -1, 0};

static void cast_close_ready(void)
{
    if (cast.ready_fd >= 0)
        close(cast.ready_fd);
    cast.ready_fd = -1;
}

/* Exit status 0 is the receiver's own report that its window and media context are released. */
static void cast_reap(void)
{
    int status;
    pid_t done;

    if (cast.state != CAST_RUNNING)
        return;
    do
        done = waitpid(cast.pid, &status, WNOHANG);
    while (done < 0 && errno == EINTR);
    if (done != cast.pid)
        return;
    cast.state = WIFEXITED(status) && WEXITSTATUS(status) == 0 ? CAST_CLEAN : CAST_UNCLEAN;
    LOG("DCV1_NATIVE_EXIT status=%d\n", status);
    cast_close_ready();
}

static int cast_is_listening(void)
{
    char byte;

    if (!cast.ready && cast.ready_fd >= 0 && read(cast.ready_fd, &byte, 1) == 1) {
        cast.ready = 1;
        cast_close_ready();
    }
    return cast.ready;
}

static int set_cloexec(int fd);

static int cast_launch(void)
{
    char *argv[] = {RECEIVER, "--listen", INSTRUMENT_IP, CAST_PORT, "--client", PEER_IP, "--ready-fd", "3", NULL};
    int ready[2], fd;
    pid_t pid;

    if (pipe(ready))
        return 1;
    if (set_cloexec(ready[0]) == -1 || fcntl(ready[0], F_SETFL, O_NONBLOCK) == -1 || (pid = fork()) < 0) {
        close(ready[0]);
        close(ready[1]);
        return 1;
    }
    if (pid == 0) {
        signal(SIGHUP, SIG_IGN);
        signal(SIGTERM, SIG_DFL);
        signal(SIGINT, SIG_DFL);
        if (ready[1] != 3) {
            if (dup2(ready[1], 3) < 0)
                _exit(127);
            close(ready[1]);
        }
        fd = open("/dev/null", O_RDONLY);
        if (fd < 0 || dup2(fd, 0) < 0)
            _exit(127);
        close(fd);
        fd = open(RUN_DIR "/cast.log", O_WRONLY | O_CREAT | O_TRUNC, 0600);
        if (fd < 0 || dup2(fd, 1) < 0 || dup2(fd, 2) < 0)
            _exit(127);
        close(fd);
        if (setenv("SOCK", NET, 1))
            _exit(127);
        execv(argv[0], argv);
        _exit(127);
    }
    close(ready[1]);
    cast.pid = pid;
    cast.ready_fd = ready[0];
    cast.ready = 0;
    cast.state = CAST_RUNNING;
    return 0;
}

static int cast_start(void)
{
    int polls;

    cast_reap();
    if (!exists(NET_SOCKET))
        return 1;
    if (cast.state == CAST_RUNNING) {
        if (!cast_is_listening())
            return 1;
        say("DCV1_LISTENING_NOT_RENDERED\n");
        return 0;
    }
    if (cast.state == CAST_UNCLEAN)
        return fail("DCV1_PRIOR_CLEANUP_UNCONFIRMED\n");
    /* A missing display or media service is a video failure, never a USB role failure. */
    for (polls = 0; !(exists(SCREEN_NODE) && exists(RENDERER_NODE)); polls++) {
        if (polls == MEDIA_POLLS)
            return fail("DCV1_MEDIA_NOT_READY\n");
        pause_for(0, 50000000);
    }
    if (cast_launch())
        return 1;
    for (polls = 0; polls < CAST_START_POLLS; polls++) {
        cast_reap();
        if (cast.state != CAST_RUNNING)
            break;
        if (cast_is_listening()) {
            say("DCV1_LISTENING_NOT_RENDERED\n");
            return 0;
        }
        pause_for(0, 50000000);
    }
    return fail("DCV1_START_UNCONFIRMED\n");
}

static int cast_stop(void)
{
    int polls;

    cast_reap();
    if (cast.state == CAST_NONE)
        return 0;
    if (cast.state == CAST_UNCLEAN)
        return fail("DCV1_CLEANUP_UNCONFIRMED\n");
    if (cast.state == CAST_RUNNING) {
        kill(cast.pid, SIGTERM);
        for (polls = 0; cast.state == CAST_RUNNING && polls < CAST_STOP_POLLS; polls++) {
            pause_for(0, 50000000);
            cast_reap();
        }
    }
    if (cast.state != CAST_CLEAN)
        return fail("DCV1_STOP_UNCONFIRMED\n");
    say("DCV1_CLEANUP_REPORTED\n");
    return 0;
}

static int video(int start)
{
    if (step_out_of_time())
        return 1;
#ifdef VIDEO_FIXTURE
    char *argv[] = {RUN_DIR "/fake-cast", start ? "start" : "stop", NULL};

    return access(RECEIVER, X_OK) ? 0 : execute(argv, NULL, MAX_EXEC_S);
#else
    if (access(RECEIVER, X_OK))
        return 0;
    return start ? cast_start() : cast_stop();
#endif
}

/* ---- Role changes ---- */

static int to_host(void)
{
    char *link_down[] = {ULINK, "-s", DCD, "-w", "2", "-l", "0", NULL};
    char *mount[] = {"/bin/mount", "-Tio-usb", "-o", XHCI_PORTS, PLATFORM "/lib/dll/devu-omap5-xhci.so", PLATFORM "/dev/io-usb/io-usb", NULL};
    struct stacks at_start, now;
    int settle;

    if (usb_survey(&at_start))
        return 1;
    if (video(0))
        return fail("HOST_VIDEO_STOP_UNCONFIRMED\n");
    if (at_start.dcd) {
        if (is_link(LINK_MARK) || (unlink(LINK_MARK) && errno != ENOENT))
            return 1;
        if (run(link_down, NULL))
            return fail("HOST_LINK_DOWN_FAILED\n");
        /* Disconnect notifications from the class driver arrive asynchronously. */
        for (settle = 0; settle < DISCONNECT_SETTLE_TICKS; settle++)
            tick();
    }
    if (at_start.net && remove_interface())
        return fail("HOST_NET_DETACH_FAILED\n");
    if (stop_stack(1) || stop_stack(0))
        return fail("HOST_PRIVATE_STOP_FAILED\n");
    if (exists(DCD) || exists(NET_SOCKET))
        return fail("HOST_PRIVATE_NODES_REMAIN\n");
    if (!exists(HOST_NODE)) {
        if (usb_check("no-controller") || run(mount, NULL) || wait_for_node(HOST_NODE))
            return 1;
    }
    if (usb_check("controllers-up") || usb_survey(&now) || now.dcd || now.net)
        return 1;
    say("USB_HOST_READY\n");
    return 0;
}

static int network_ready(void)
{
    char *argv[] = {IFCONFIG, "ncm0", NULL};
    struct launch how = {RUN_DIR "/interface", 1};
    char *text, *cursor, *line;
    int has_address = 0, is_up = 0;

    if (run(argv, &how))
        return 0;
    text = load_text(RUN_DIR "/interface");
    if (!text)
        return 0;
    cursor = text;
    while ((line = next_line(&cursor))) {
        const char *address = strstr(line, "inet " INSTRUMENT_IP " ");

        if (address && strstr(address, "netmask 0xfffffffc"))
            has_address = 1;
        if (!strncmp(line, "ncm0:", 5)) {
            const char *open_flags = strchr(line + 5, '<');

            if ((open_flags && strstr(open_flags, "UP,")) || strstr(line + 5, "<UP>"))
                is_up = 1;
        }
    }
    free(text);
    return has_address && is_up;
}

/* True only after our own successful link-up; says nothing about the peer or video. */
static int link_marked(void)
{
    char *text;
    int marked;

    if (is_link(LINK_MARK))
        return 0;
    text = load_text(LINK_MARK);
    if (!text)
        return 0;
    marked = !strcmp(text, "NCM_SOFT_CONNECTED\n");
    free(text);
    return marked;
}

static int write_link_mark(void)
{
    static const char text[] = "NCM_SOFT_CONNECTED\n";
    int fd = open(LINK_MARK, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    ssize_t written;

    if (fd < 0)
        return 1;
    written = write(fd, text, sizeof text - 1);
    close(fd);
    return written != (ssize_t)(sizeof text - 1);
}

static int connect_peer(void)
{
    char *address[] = {IFCONFIG, "ncm0", INSTRUMENT_IP, "netmask", "255.255.255.252", "up", NULL};
    char *link_up[] = {ULINK, "-s", DCD, "-w", "2", "-l", "1", NULL};
    struct stacks now;

    if (run(address, &ON_USB_NETWORK) || !network_ready() || run(link_up, NULL))
        return 1;
    if (usb_check("no-controller") || usb_survey(&now) || !now.dcd || !now.net)
        return 1;
    if (is_link(LINK_MARK) || write_link_mark())
        return 1;
    say("USB_NCM_READY\n");
    return 0;
}

static int to_ncm(void)
{
    static const char *const REQUIRED[] = {PLATFORM "/sbin/io-usb-dcd", PLATFORM "/sbin/io-pkt-v6-hc", ULINK, IFCONFIG,
                                           PLATFORM "/lib/dll/devu-usbncm-omap543x-dwcotg3.so", PLATFORM "/lib/dll/devnp-usbdnet.so"};
    char *unmount[] = {"/bin/umount", HOST_NODE, NULL};
    char *device_stack[] = {PLATFORM "/sbin/io-usb-dcd", "-n", DCD, "-d", "usbncm-omap543x-dwcotg3", "ioport=0x488d0000,irq=110,linkdown", NULL};
    char *network_stack[] = {PLATFORM "/sbin/io-pkt-v6-hc", "-i7", "-d", "usbdnet",
                             "protocol=ncm,path=" DCD ",mac=020000000701,usbdnet_mac=020000000702,wait=2", "-ptcpip", "prefix=" NET, NULL};
    struct stacks at_start, now;
    unsigned i;

    if (usb_survey(&at_start))
        return 1;
    if (at_start.dcd && at_start.net && !exists(HOST_NODE) && exists(DCD)) {
        /* A repeated request must not reset a gadget that is already serving the peer. */
        if (link_marked() && network_ready()) {
            if (usb_check("no-controller"))
                return 1;
            say("NCM_ALREADY_CONFIGURED_NO_RECONNECT\n");
            return 0;
        }
        /* A failed Host request can leave the stack up but the link down. */
        return usb_check("no-controller") || connect_peer();
    }
    if (at_start.dcd || at_start.net || !exists(HOST_NODE) || exists(DCD) || exists(NET_SOCKET))
        return 1;
    for (i = 0; i < sizeof REQUIRED / sizeof REQUIRED[0]; i++)
        if (!exists(REQUIRED[i]))
            return 1;
    if (usb_check("no-devices"))
        return fail("USB_DEVICES_ATTACHED\n");
    if (make_stack_directories() || run(unmount, NULL) || exists(HOST_NODE) || usb_check("no-controller"))
        return 1;
    if (run(device_stack, NULL) || wait_for_node(DCD) || run(network_stack, NULL))
        return 1;
    /* The survey here covers both stacks, so the device stack needs no survey of its own. */
    if (wait_for_node(NET_SOCKET) || usb_survey(&now) || !now.dcd || !now.net)
        return 1;
    return connect_peer();
}

static void role_begin(int watch_stop)
{
    role_timed_out = 0;
    role_watch_stop = watch_stop;
    role_deadline = now_ms() + ROLE_LIMIT_S * 1000;
}

static int role_query(void)
{
    struct stacks now;

    role_begin(0);
    if (usb_survey(&now))
        return 1;
    if (exists(HOST_NODE) && !now.dcd && !now.net) {
        if (usb_check("controllers-up"))
            return 1;
        say("USB_HOST_READY\n");
        return 0;
    }
    if (!exists(HOST_NODE) && now.dcd && now.net && exists(DCD)) {
        if (usb_check("no-controller") || !network_ready())
            return 1;
        say("NCM_NETWORK_UP_PEER_UNKNOWN\n");
        return 0;
    }
    return fail("USB_STATE_UNKNOWN\n");
}

static int role_change(int command)
{
    int rc = 0;

    role_begin(1);
    if (is_link(ROLE_GUARD) || mkdir(ROLE_GUARD, 0700))
        return fail("USB_OPERATION_BUSY_OR_UNCONFIRMED\n");
    if (command == CMD_HOST) {
        if (to_host())
            rc = fail("USB_HOST_FAILED\n");
    } else if (to_ncm()) {
        say("USB_NCM_FAILED\n");
        rc = 1;
        if (!role_timed_out && to_host()) {
            say("USB_HOST_RECOVERY_UNCONFIRMED\n");
            rc = UNCONFIRMED_RECOVERY;
        }
    } else if (video(1)) {
        say("DCV1_UNAVAILABLE\n");
    }
    /* The guard stays after a timeout: an unknown child may still be running. */
    if (role_timed_out) {
        say("USB_OPERATION_QUARANTINED_RESTART_REQUIRED\n");
        return TIMED_OUT;
    }
    if (remove_role_guard())
        return fail("USB_OPERATION_GUARD_RELEASE_FAILED\n");
    return rc;
}

/* ---- Service ---- */

static void on_signal(int number)
{
    int saved = errno;
    char byte = 1;

    (void)number;
    stopping = 1;
    if (stop_fd >= 0)
        write(stop_fd, &byte, 1);
    errno = saved;
}

/* Commands that arrived while a switch ran are dropped, not queued. */
static int drain(int fd)
{
    char data[BUFFER];
    int rounds;
    ssize_t got;

    for (rounds = 0; rounds < 32; rounds++) {
        got = read(fd, data, sizeof data);
        if (got == 0)
            return 0;
        if (got < 0 && errno != EINTR)
            return errno != EAGAIN;
    }
    return 1;
}

/* The notice runs beside the service, so a request made while it is on screen is not lost. */
static pid_t notice_child;
static ms_t notice_began;

static void notice_collect(void)
{
    int status;

    if (notice_child && waitpid(notice_child, &status, WNOHANG) == notice_child)
        notice_child = 0;
}

static void notice_end(void)
{
    int polls, status;

    notice_collect();
    if (!notice_child)
        return;
    kill(notice_child, SIGTERM);
    for (polls = 0; polls < 25 && notice_child; polls++) {
        pause_for(0, 20000000);
        notice_collect();
    }
    if (notice_child) {
        kill(notice_child, SIGKILL);
        for (polls = 0; polls < 10 && waitpid(notice_child, &status, WNOHANG) == 0; polls++)
            pause_for(0, 20000000);
        notice_child = 0;
    }
}

static void notice_overdue(void)
{
    if (notice_child && now_ms() - notice_began > NOTICE_LIMIT_S * 1000)
        notice_end();
}

static void notice_start(char *self, char digit)
{
    char text[2] = {digit, 0};
    char *argv[] = {self, "--show-result", text, NULL};
    pid_t pid;

#ifdef BLOCKING_NOTICE
    execute(argv, NULL, NOTICE_LIMIT_S);
    (void)pid;
    (void)notice_began;
#else
    pid = fork();
    if (pid == 0) {
        signal(SIGTERM, SIG_DFL);
        signal(SIGINT, SIG_DFL);
        execv(argv[0], argv);
        _exit(127);
    }
    if (pid > 0) {
        notice_child = pid;
        notice_began = now_ms();
    }
#endif
}

static int apply(char *self, int command)
{
    static char *const WORDS[] = {"query", "host", "ncm"};
    int rc;

    if (command != CMD_STATUS)
        notice_end();
#ifdef ROLE_FIXTURE
    {
        char *argv[] = {"/bin/sh", ROLE_FIXTURE, WORDS[command], self, NULL};

        rc = execute(argv, NULL, ROLE_LIMIT_S);
    }
#else
    rc = command == CMD_STATUS ? role_query() : role_change(command);
#endif
    LOG("ROLE_REQUEST role=%s rc=%d\n", WORDS[command], rc);
    if (command != CMD_STATUS)
        notice_start(self, (char)('0' + (rc ? NOTICE_FAILED : command == CMD_HOST ? NOTICE_HOST_OK : NOTICE_NCM_OK)));
    return rc;
}

/* The first valid line of one read from the FIFO; an over-long or NUL-carrying line is ignored. */
static int parse_lines(const char *data, ssize_t size)
{
    char line[256];
    ssize_t i;
    int used = 0, bad = 0, command;

    for (i = 0; i < size; i++) {
        char c = data[i];

        if (c == '\n') {
            line[used] = 0;
            command = bad ? 0 : parse_command(line);
            used = 0;
            bad = 0;
            if (command)
                return command;
        } else if (c == 0 || used == 255) {
            bad = 1;
        } else if (!bad) {
            line[used++] = c;
        }
    }
    return 0;
}

static int set_cloexec(int fd)
{
    return fcntl(fd, F_SETFD, FD_CLOEXEC);
}

static int serve(char *self)
{
    struct pollfd fds[3];
    char data[BUFFER];
    int stop_pipe[2], pps = -1, fifo = -1, failed = 1, unconfirmed = 0, waited = 0, attempt, i, command;

    stop_pipe[0] = -1;
    if (mkdir(RUN_DIR, 0700) && errno != EEXIST)
        return 1;
    if (mkdir(LOCK_DIR, 0700)) {
        say("ALREADY_RUNNING\n");
        return 1;
    }
    if (pipe(stop_pipe) || set_cloexec(stop_pipe[0]) == -1 || set_cloexec(stop_pipe[1]) == -1 ||
        fcntl(stop_pipe[1], F_SETFL, O_NONBLOCK) == -1)
        goto out;
    stop_fd = stop_pipe[1];
    signal(SIGTERM, on_signal);
    signal(SIGINT, on_signal);
    say("WATCH_BEGIN\n");
    while (!stopping && (pps = open(PPS_PATH, O_RDONLY | O_NONBLOCK)) < 0) {
        if (errno != EINTR) {
            if (errno != ENOENT)
                goto out;
            if (!waited++)
                say("WAIT_FOR_CHANNEL\n");
            pause_for(1, 0);
        }
    }
    if (stopping) {
        failed = 0;
        goto out;
    }
    if (drain(pps))
        goto out;
    set_cloexec(pps);
    for (attempt = 1; apply(self, CMD_STATUS); attempt++) {
        if (stopping) {
            failed = 0;
            goto out;
        }
        if (attempt >= QUERY_ATTEMPTS)
            goto out;
        pause_for(0, 500000000);
    }
    if (drain(pps))
        goto out;
    if (mkfifo(COMMANDS, 0600) || (fifo = open(COMMANDS, O_RDWR | O_NONBLOCK)) < 0)
        goto out;
    set_cloexec(fifo);
    fds[0].fd = pps;
    fds[1].fd = fifo;
    fds[2].fd = stop_pipe[0];
    for (i = 0; i < 3; i++)
        fds[i].events = POLLIN;
    say("WATCHING\n");
    while (!stopping) {
        int ready = poll(fds, 3, notice_child ? 100 : -1);

        notice_collect();
        notice_overdue();
        if (ready < 0) {
            if (errno != EINTR)
                goto out;
            continue;
        }
        for (i = 0; i < 2 && !stopping; i++) {
            ssize_t got;

            if (fds[i].revents & POLL_FAILED)
                goto out;
            if (!(fds[i].revents & POLLIN))
                continue;
            got = read(fds[i].fd, data, sizeof data);
            if (got == 0 || (got < 0 && errno != EINTR && errno != EAGAIN))
                goto out;
            if (got < 0)
                continue;
            command = i == 0 ? parse_frame(data, (size_t)got) : parse_lines(data, got);
            if (command && apply(self, command) == UNCONFIRMED_RECOVERY) {
                unconfirmed = 1;
                stopping = 1;
            }
            if (drain(pps) || drain(fifo))
                goto out;
            if (command && !stopping)
                say("REQUEST_DONE\n");
        }
    }
    failed = unconfirmed;
out:
    /* The receiver must not outlive the service that is the only thing able to stop it. */
    if (cast.state == CAST_RUNNING && cast_stop())
        failed = 1;
    if (stop_fd >= 0) {
        int fd = stop_fd;
        stop_fd = -1;
        close(fd);
    }
    if (stop_pipe[0] >= 0)
        close(stop_pipe[0]);
    if (pps >= 0)
        close(pps);
    if (fifo >= 0)
        close(fifo);
    unlink(COMMANDS);
    if (!failed)
        rmdir(LOCK_DIR);
    LOG("WATCH_END failed=%d\n", failed);
    return failed;
}

static int send_command(const char *name)
{
    char line[32];
    int length, fd = open(COMMANDS, O_WRONLY | O_NONBLOCK), written;

    if (fd < 0) {
        say("START_FAILED\n");
        return 1;
    }
    signal(SIGPIPE, SIG_IGN);
    length = snprintf(line, sizeof line, "%s\n", name);
    written = (int)write(fd, line, (size_t)length);
    close(fd);
    return written != length;
}

/* ---- Start-up: the system runs this once, from the installed copy ---- */

static int copy_file(const char *from, const char *to)
{
    char data[BUFFER];
    int in = open(from, O_RDONLY), out, failed = 1;
    ssize_t got;

    if (in < 0)
        return 1;
    out = open(to, O_WRONLY | O_CREAT | O_TRUNC, 0700);
    if (out >= 0) {
        for (;;) {
            got = read(in, data, sizeof data);
            if (got < 0 && errno == EINTR)
                continue;
            if (got <= 0) {
                failed = got != 0;
                break;
            }
            if (write(out, data, (size_t)got) != got)
                break;
        }
        if (close(out))
            failed = 1;
    }
    close(in);
    return failed;
}

/* Shared with the HMI. It may already be mapped, so an existing one is never touched; projection works without it. */
static void prepare_background_page(void)
{
    char zeros[4096];
    int fd;

    if (exists(BACKGROUND_PAGE) || is_link(BACKGROUND_PAGE))
        return;
    memset(zeros, 0, sizeof zeros);
    fd = open(BACKGROUND_PAGE, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd < 0 || write(fd, zeros, sizeof zeros) != (ssize_t)sizeof zeros)
        say("BACKGROUND_PAGE_UNAVAILABLE\n");
    if (fd >= 0)
        close(fd);
}

/* Video is optional: without a complete receiver in RAM, the USB control still runs. */
static void copy_receiver(void)
{
    if (!exists(INSTALL_DIR "/dashcast-receiver"))
        return;
    if (!copy_file(INSTALL_DIR "/dashcast-receiver", RECEIVER ".tmp") && !rename(RECEIVER ".tmp", RECEIVER)) {
        say("DCV1_FILES_READY\n");
        return;
    }
    unlink(RECEIVER ".tmp");
    say("DCV1_COPY_FAILED\n");
}

/* The USB role is left exactly as the boot chain set it. */
static int boot(void)
{
    char *argv[] = {RUN_DIR "/dashcast-usb", "--run", NULL};
    int fd;

    umask(077);
    if (!exists(RAM_ROOT)) {
        say("RAM_DIR_UNAVAILABLE\n");
        return 1;
    }
    if (is_link(RUN_DIR) || exists(LOCK_DIR) || (mkdir(RUN_DIR, 0700) && errno != EEXIST))
        return 1;
    fd = open(RUN_DIR "/usb.log", O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd < 0 || dup2(fd, 1) < 0 || dup2(fd, 2) < 0)
        return 1;
    if (fd > 2)
        close(fd);
    say("DASHCAST_STARTUP\n");
    prepare_background_page();
    if (is_link(INSTALL_DIR "/dashcast-usb") || copy_file(INSTALL_DIR "/dashcast-usb", RUN_DIR "/dashcast-usb")) {
        say("BOOT_COPY_FAILED\n");
        return 1;
    }
    copy_receiver();
    unsetenv("SOCK");
    execv(argv[0], argv);
    LOG("BOOT_EXEC_FAILED errno=%d\n", errno);
    return 1;
}

int main(int argc, char **argv)
{
    if (argc == 3 && !strcmp(argv[1], "--usb-check"))
        return probe(argv[2]);
#ifdef TEST_CLI
    if (argc > 3 && !strcmp(argv[1], "--bounded") && argv[3][0] == '/') {
        char *end;
        unsigned long seconds = strtoul(argv[2], &end, 10);
        if (*end == 0 && seconds >= 1 && seconds <= MAX_EXEC_S)
            return execute(argv + 3, NULL, (unsigned)seconds);
    }
#endif
    if (argc == 3 && !strcmp(argv[1], "--show-result") && strlen(argv[2]) == 1 && argv[2][0] >= '0' && argv[2][0] <= '9')
        return show_notice(argv[2][0] - '0');
    if (argc == 3 && !strcmp(argv[1], "--request") && parse_command(argv[2]))
        return send_command(argv[2]);
    if (argc == 2 && !strcmp(argv[1], "--boot"))
        return boot();
    if (argc == 2 && !strcmp(argv[1], "--run") && realpath(argv[0], self_path))
        return serve(self_path);
    say("usage: dashcast-usb --boot | --run | --request SCMD_USBMODE1|2\n");
    return 2;
}
