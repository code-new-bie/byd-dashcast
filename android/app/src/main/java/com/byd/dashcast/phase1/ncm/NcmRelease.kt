package com.byd.dashcast.phase1.ncm

/**
 * Ordered best-effort USB cleanup; one failed step never skips releasing the remaining resources.
 * @author Codex
 * @date 2026/10/11
 */
object NcmRelease {
    /** Owned USB resource operations, injected independently of Android for tests. */
    interface Steps {
        /** Selects data alternate 0 if the data interface was claimed. */
        fun deactivateData(): Boolean

        /** Releases the owned data interface, or succeeds if it was never claimed. */
        fun releaseData(): Boolean

        /** Releases the owned control interface, or succeeds if it was never claimed. */
        fun releaseControl(): Boolean

        /** Closes the owned USB device connection. */
        fun closeConnection()
    }

    /** Calls all four steps in order, reporting failure without inventing a Host-role acknowledgement. */
    @JvmStatic
    fun finish(steps: Steps?): Boolean {
        if (steps == null) {
            throw IllegalArgumentException("Missing NCM release adapter")
        }
        // `and` evaluates both sides, so a failed step never skips the next one.
        var confirmed = attempt(steps::deactivateData, "USB_DATA_ALT0_CLEANUP")
        confirmed = confirmed and attempt(steps::releaseData, "USB_RELEASE_DATA")
        confirmed = confirmed and attempt(steps::releaseControl, "USB_RELEASE_CONTROL")
        try {
            steps.closeConnection()
        } catch (error: RuntimeException) {
            confirmed = false
            LogHub.throwable("usb", "USB_CLOSE_CONNECTION rejected", error)
        }
        return confirmed
    }

    private fun attempt(action: () -> Boolean, name: String): Boolean = try {
        val result = action()
        LogHub.i("usb", "$name=$result")
        result
    } catch (error: RuntimeException) {
        LogHub.throwable("usb", "$name rejected", error)
        false
    }
}
