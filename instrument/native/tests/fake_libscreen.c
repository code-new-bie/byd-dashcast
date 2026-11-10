/* TEST FIXTURE ONLY: stands in for libscreen.so.1 and appends each call to FAKE_SCREEN_LOG. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static unsigned char pixels[2560 * 96];
static int position[2], extent[2];

static void note(const char *text, int value)
{
    FILE *log = fopen(getenv("FAKE_SCREEN_LOG"), "a");
    fprintf(log, "%s %d\n", text, value);
    fclose(log);
}

static int fails(const char *name)
{
    const char *which = getenv("FAKE_SCREEN_FAIL");
    return which && strcmp(which, name) == 0;
}

int screen_create_context(void **context, int mode) { note("create_context", mode); *context = (void *)1; return 0; }
int screen_destroy_context(void *c) { (void)c; note("destroy_context", 0); return 0; }
int screen_create_window(void **window, void *c) { (void)c; if (fails("window")) return -1; note("create_window", 0); *window = (void *)2; return 0; }
int screen_destroy_window(void *w) { (void)w; note("destroy_window", 0); return 0; }
int screen_create_window_group(void *w, const char *name) { (void)w; (void)name; return 0; }
int screen_create_window_buffers(void *w, int n) { (void)w; (void)n; return 0; }
int screen_set_window_property_pv(void *w, int p, void **v) { (void)w; (void)p; (void)v; return 0; }
int screen_set_window_property_cv(void *w, int p, int n, const char *v) { (void)w; (void)p; (void)n; (void)v; return 0; }
int screen_get_window_property_pv(void *w, int p, void **v) { (void)w; (void)p; v[0] = (void *)3; return 0; }
int screen_get_context_property_iv(void *c, int p, int *v) { (void)c; (void)p; *v = 1; return 0; }
int screen_get_context_property_pv(void *c, int p, void **v) { (void)c; (void)p; v[0] = (void *)4; return 0; }
int screen_get_buffer_property_pv(void *b, int p, void **v) { (void)b; (void)p; *v = pixels; return 0; }
int screen_get_buffer_property_iv(void *b, int p, int *v) { (void)b; (void)p; *v = 2560; return 0; }
int screen_flush_context(void *c, int f) { (void)c; (void)f; return 0; }

int screen_get_display_property_iv(void *d, int p, int *v)
{
    (void)d; (void)p;
    v[0] = 1920; v[1] = 720;
    return 0;
}

int screen_set_window_property_iv(void *w, int p, const int *v)
{
    (void)w;
    if (p == 0x23) memcpy(position, v, sizeof position);
    if (p == 0x28) memcpy(extent, v, sizeof extent);
    return 0;
}

int screen_get_window_property_iv(void *w, int p, int *v)
{
    (void)w;
    memcpy(v, p == 0x23 ? position : extent, 2 * sizeof(int));
    if (p == 0x23 && fails("moved")) v[0] = 0;
    return 0;
}

int screen_post_window(void *w, void *b, int n, const int *d, int f)
{
    int count = 0, i;

    (void)w; (void)b; (void)n; (void)d; (void)f;
    for (i = 0; i < 640 * 96; i++)
        if (pixels[i * 4] == 0xf9 && pixels[i * 4 + 1] == 0xf5 && pixels[i * 4 + 2] == 0xf1)
            count++;
    note("post_text_pixels", count);
    /* RGBA8888 is stored blue first; report the corner as 0xRRGGBB. */
    note("post_background", pixels[0] | pixels[1] << 8 | pixels[2] << 16);
    return 0;
}
