/* TEST FIXTURE ONLY: stands in for libscreen, libmmrndclient and libstrm, built once under all three names.
 *
 * Every call is appended to FAKE_LOG. The other variables steer it:
 *   FAKE_FAIL          comma-separated names of the calls that fail
 *   FAKE_DISPLAYS      comma-separated display widths (default "1280,1920")
 *   FAKE_NO_WINDOW     the decoder never creates its window
 *   FAKE_WINDOW_ID     the id the decoder's window reports instead of the expected one
 *   FAKE_OTHER_EVENTS  how many unrelated events come before the window
 *   FAKE_MOVED         window position reads back wrong
 */
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

enum { PARENT = 2, CHILD = 7, BUFFER = 3, EVENT = 5 };
enum { PROP_BUFFER_SIZE = 0x05, PROP_ID = 0x14, PROP_POSITION = 0x23, PROP_SIZE = 0x28, PROP_EVENT_TYPE = 0x2f, PROP_VISIBLE = 0x33 };

static int position[2][2], extent[2][2], visible[2], buffer_size[2], events;
static char parent_name[64];
static unsigned char *pixels;

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

static int fails(const char *name)
{
    const char *list = getenv("FAKE_FAIL"), *at;
    size_t size = strlen(name);

    for (at = list; at && (at = strstr(at, name)); at += size)
        if ((at == list || at[-1] == ',') && (at[size] == ',' || !at[size]))
            return 1;
    return 0;
}

#define CALL(name) do { note(name); if (fails(name)) return -1; } while (0)
#define CALL_POINTER(name) do { note(name); if (fails(name)) return NULL; } while (0)

static int slot(void *window)
{
    return (long)window == CHILD;
}

static const char *window_name(void *window)
{
    return slot(window) ? "child" : "parent";
}

/* ---- libscreen ---- */

int screen_create_context(void **context, int mode)
{
    (void)mode;
    CALL("screen_create_context");
    *context = (void *)1;
    return 0;
}

int screen_destroy_context(void *context)
{
    (void)context;
    CALL("screen_destroy_context");
    return 0;
}

int screen_create_window(void **window, void *context)
{
    (void)context;
    CALL("screen_create_window");
    *window = (void *)PARENT;
    return 0;
}

int screen_destroy_window(void *window)
{
    (void)window;
    free(pixels);
    pixels = NULL;
    CALL("screen_destroy_window");
    return 0;
}

int screen_create_window_group(void *window, const char *name)
{
    (void)window;
    note("screen_create_window_group %s", name);
    return fails("screen_create_window_group") ? -1 : 0;
}

int screen_create_window_buffers(void *window, int count)
{
    (void)window;
    (void)count;
    CALL("screen_create_window_buffers");
    return 0;
}

int screen_set_window_property_iv(void *window, int property, const int *value)
{
    note("set %s 0x%x %d", window_name(window), property, value[0]);
    if (fails("screen_set_window_property_iv"))
        return -1;
    if (property == PROP_POSITION)
        memcpy(position[slot(window)], value, sizeof position[0]);
    if (property == PROP_SIZE)
        memcpy(extent[slot(window)], value, sizeof extent[0]);
    if (property == PROP_VISIBLE)
        visible[slot(window)] = value[0];
    if (property == PROP_BUFFER_SIZE)
        memcpy(buffer_size, value, sizeof buffer_size);
    return 0;
}

int screen_set_window_property_pv(void *window, int property, void **value)
{
    (void)window;
    note("set_display %ld (property 0x%x)", (long)value[0], property);
    return 0;
}

int screen_set_window_property_cv(void *window, int property, int size, const char *value)
{
    (void)window;
    (void)property;
    (void)size;
    snprintf(parent_name, sizeof parent_name, "%s", value);
    return 0;
}

int screen_get_window_property_cv(void *window, int property, int size, char *value)
{
    const char *forced = getenv("FAKE_WINDOW_ID");

    (void)window;
    (void)property;
    if (fails("screen_get_window_property_cv"))
        return -1;
    if (forced)
        snprintf(value, (size_t)size, "%s", forced);
    else
        snprintf(value, (size_t)size, "%s_picture", parent_name);
    return 0;
}

int screen_get_window_property_iv(void *window, int property, int *value)
{
    if (property == PROP_VISIBLE) {
        value[0] = visible[slot(window)];
        return 0;
    }
    memcpy(value, property == PROP_POSITION ? position[slot(window)] : extent[slot(window)], 2 * sizeof(int));
    if (property == PROP_POSITION && getenv("FAKE_MOVED"))
        value[0] += 5;
    return 0;
}

int screen_get_window_property_pv(void *window, int property, void **value)
{
    (void)window;
    (void)property;
    value[0] = (void *)BUFFER;
    return 0;
}

int screen_get_context_property_iv(void *context, int property, int *value)
{
    const char *list = getenv("FAKE_DISPLAYS");

    (void)context;
    (void)property;
    *value = 1;
    for (list = list ? list : "1280,1920"; *list; list++)
        *value += *list == ',';
    return 0;
}

int screen_get_context_property_pv(void *context, int property, long *value)
{
    int count = 0, i;

    screen_get_context_property_iv(context, property, &count);
    for (i = 0; i < count; i++)
        value[i] = i + 1; /* handles are never null */
    return 0;
}

int screen_get_display_property_iv(void *display, int property, int *value)
{
    const char *list = getenv("FAKE_DISPLAYS");
    long index;

    (void)property;
    list = list ? list : "1280,1920";
    for (index = (long)display - 1; index; index--)
        list = strchr(list, ',') + 1;
    value[0] = atoi(list);
    value[1] = value[0] == 1920 ? 720 : 480;
    return 0;
}

int screen_get_buffer_property_pv(void *buffer, int property, void **value)
{
    (void)buffer;
    (void)property;
    pixels = malloc((size_t)buffer_size[0] * 4 * (size_t)buffer_size[1]);
    memset(pixels, 0xaa, (size_t)buffer_size[0] * 4 * (size_t)buffer_size[1]);
    *value = pixels;
    return 0;
}

int screen_get_buffer_property_iv(void *buffer, int property, int *value)
{
    (void)buffer;
    (void)property;
    *value = buffer_size[0] * 4;
    return 0;
}

int screen_post_window(void *window, void *buffer, int count, const int *dirty, int flags)
{
    size_t size = (size_t)buffer_size[0] * 4 * (size_t)buffer_size[1], i, marked = 0;

    (void)window;
    (void)buffer;
    (void)count;
    (void)dirty;
    (void)flags;
    for (i = 0; i < size; i++)
        marked += pixels[i] != 0;
    note("screen_post_window uncleared=%zu of=%zu", marked, size);
    return fails("screen_post_window") ? -1 : 0;
}

int screen_flush_context(void *context, int flags)
{
    (void)context;
    (void)flags;
    return fails("screen_flush_context") ? -1 : 0;
}

int screen_create_event(void **event)
{
    CALL("screen_create_event");
    *event = (void *)EVENT;
    return 0;
}

int screen_destroy_event(void *event)
{
    (void)event;
    CALL("screen_destroy_event");
    return 0;
}

int screen_get_event(void *context, void *event, long timeout)
{
    (void)context;
    (void)event;
    (void)timeout;
    events++;
    return fails("screen_get_event") ? -1 : 0;
}

int screen_get_event_property_iv(void *event, int property, int *value)
{
    const char *text = getenv("FAKE_OTHER_EVENTS");
    int others = text ? atoi(text) : 0;

    (void)event;
    (void)property;
    if (events <= others)
        *value = 7;
    else
        *value = events == others + 1 && !getenv("FAKE_NO_WINDOW") ? 1 : 0;
    return 0;
}

int screen_get_event_property_pv(void *event, int property, void **value)
{
    (void)event;
    (void)property;
    *value = (void *)CHILD;
    return 0;
}

/* ---- libmmrndclient ---- */

void *mmr_connect(const char *name)
{
    (void)name;
    CALL_POINTER("mmr_connect");
    return (void *)0x10;
}

int mmr_disconnect(void *connection)
{
    (void)connection;
    CALL("mmr_disconnect");
    return 0;
}

void *mmr_context_create(void *connection, const char *name, int flags, int mode)
{
    (void)connection;
    (void)flags;
    note("mmr_context_create %s mode=0%o", name, mode);
    return fails("mmr_context_create") ? NULL : (void *)0x20;
}

int mmr_context_destroy(void *media)
{
    (void)media;
    CALL("mmr_context_destroy");
    return 0;
}

int mmr_context_parameters(void *media, void *dict)
{
    (void)media;
    (void)dict;
    CALL("mmr_context_parameters");
    return 0;
}

int mmr_input_parameters(void *media, void *dict)
{
    (void)media;
    (void)dict;
    CALL("mmr_input_parameters");
    return 0;
}

int mmr_output_parameters(void *media, int index, void *dict)
{
    (void)media;
    (void)index;
    (void)dict;
    CALL("mmr_output_parameters");
    return 0;
}

int mmr_output_attach(void *media, const char *url, const char *type)
{
    (void)media;
    note("mmr_output_attach %s %s", url, type);
    return fails("mmr_output_attach") ? -1 : 0;
}

int mmr_input_attach(void *media, const char *url, const char *type)
{
    (void)media;
    note("mmr_input_attach %s %s", url, type);
    return fails("mmr_input_attach") ? -1 : 0;
}

int mmr_play(void *media)
{
    (void)media;
    CALL("mmr_play");
    return 0;
}

/* ---- libstrm ---- */

void *strm_dict_new(void)
{
    CALL_POINTER("strm_dict_new");
    return (void *)0x30;
}

void *strm_dict_set(void *dict, const char *key, const char *value)
{
    note("dict %s=%s", key, value);
    return fails("strm_dict_set") ? NULL : dict;
}

int strm_dict_destroy(void *dict)
{
    (void)dict;
    note("strm_dict_destroy");
    return 0;
}
