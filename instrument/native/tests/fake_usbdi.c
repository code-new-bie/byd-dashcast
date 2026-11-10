/* TEST FIXTURE ONLY: stands in for libusbdi.so.2, driven by environment variables.
 * FAKE_BUSES is a comma list such as "XHCI:0,XHCI:2" (controller:attached devices). */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int flag(const char *name)
{
    const char *value = getenv(name);
    return value ? atoi(value) : 0;
}

/* Copies the bus entry into controller and returns its device count, or -1 past the end. */
static int bus_entry(unsigned bus, char controller[32])
{
    const char *at = getenv("FAKE_BUSES");
    unsigned i;
    int devices = 0;

    if (!at || !*at)
        return -1;
    for (i = 0; i < bus; i++) {
        at = strchr(at, ',');
        if (!at)
            return -1;
        at++;
    }
    if (sscanf(at, "%31[^:]:%d", controller, &devices) != 2)
        return -1;
    return devices;
}

int usbd_connect(void *parm, void **connection)
{
    (void)parm;
    *connection = (void *)1;
    return flag("FAKE_CONNECT");
}

int usbd_hcd_ext_info(void *connection, unsigned bus, char *info)
{
    (void)connection;
    if (flag("FAKE_INFO"))
        return flag("FAKE_INFO");
    return bus_entry(bus, info + 4) < 0 ? 19 : 0;
}

int usbd_topology_ext(void *connection, unsigned char bus, unsigned char *entries)
{
    char controller[32];
    int devices = bus_entry(bus, controller), i;

    (void)connection;
    if (flag("FAKE_TOPOLOGY"))
        return flag("FAKE_TOPOLOGY");
    for (i = 0; i < devices; i++)
        entries[i * 8] = 1;
    return 0;
}

int usbd_disconnect(void *connection)
{
    (void)connection;
    return flag("FAKE_DISCONNECT");
}
