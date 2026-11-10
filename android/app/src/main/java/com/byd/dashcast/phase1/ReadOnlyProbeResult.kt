package com.byd.dashcast.phase1

import java.io.IOException

/**
 * A read-only command returning EOF or no matching lines is not evidence of successful execution.
 *
 * @author Codex
 * @date 2026/10/10
 */
internal object ReadOnlyProbeResult {
    @JvmStatic
    @Throws(IOException::class)
    fun requireOutput(text: String?, truncated: Boolean): String {
        if (truncated) {
            throw IOException("Read-only probe output truncated; result UNKNOWN")
        }
        val output = AdbCommandOutput.requireSuccess(text)
        val trimmed = output.trim()
        if (trimmed.isEmpty()) {
            throw IOException("Read-only probe returned no evidence; result UNKNOWN")
        }
        if (trimmed.startsWith("Can't find service:") || trimmed.startsWith("Cannot find service:")) {
            throw IOException("Requested dumpsys service unavailable; result UNKNOWN")
        }
        return output
    }
}
