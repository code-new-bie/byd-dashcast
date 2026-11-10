package com.byd.dashcast.phase1

import java.io.IOException

/**
 * Exit/resource proof is mandatory even on cancellation or capture failure.
 * @author Codex
 * @date 2026/10/20
 */
internal object RemoteProbeCompletion {
    @JvmStatic
    @Throws(IOException::class)
    fun require(exitConfirmed: Boolean, stopped: Boolean, pictures: Int, failure: IOException?) {
        if (!exitConfirmed) {
            throw ProjectionSessionController.CleanupUnconfirmedException(
                "REMOTE_PROBE_EXIT_OR_RESOURCES_UNCONFIRMED", failure)
        }
        if (failure != null) {
            throw failure
        }
        if (!stopped && pictures < 2) {
            throw IOException("REMOTE_PROBE_FEWER_THAN_TWO_PICTURES")
        }
    }
}
