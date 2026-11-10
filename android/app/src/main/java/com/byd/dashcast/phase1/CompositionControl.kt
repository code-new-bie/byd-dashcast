package com.byd.dashcast.phase1

import java.io.IOException

/**
 * A single video stream owns its background lease and restoration.
 * @author Codex
 * @date 2026/10/21
 */
internal interface CompositionControl {
    /** Validate the matching receiver/HMI before media begins. */
    @Throws(IOException::class)
    fun prepare(channel: NoAuthVideoTransport)

    /** Activate only after the first actual video acknowledgement. */
    @Throws(IOException::class)
    fun activate(channel: NoAuthVideoTransport)

    /** Maintain only this healthy stream's lease. */
    @Throws(IOException::class)
    fun tick(channel: NoAuthVideoTransport)

    /** Confirm owned restoration before media/USB teardown. */
    @Throws(IOException::class)
    fun restore(channel: NoAuthVideoTransport)
}
