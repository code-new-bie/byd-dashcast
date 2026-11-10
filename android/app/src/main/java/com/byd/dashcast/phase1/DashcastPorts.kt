package com.byd.dashcast.phase1

/** Every TCP port the app uses, in one place. */
object DashcastPorts {
    /**
     * DCV1 on the instrument, 192.168.77.1: video, control, logs and version on one connection.
     * Must equal CAST_PORT in instrument/native/dashcast_usb.c (tools/tests/test_native_usb.py checks it).
     * 0x4443, "DC"; below both the QNX and the Android ephemeral ranges, and not used by the instrument's system.
     */
    const val PROJECTION = 17475

    /** The head unit's own adbd on 127.0.0.1. The head unit sets it; this app only connects to it. */
    const val LOCAL_ADB = 5555

    /** Source ports of the app's own TCP stack on the USB link: the IANA dynamic range 49152-65535. */
    const val SOURCE_FIRST = 49152
    const val SOURCE_COUNT = 16384
}
