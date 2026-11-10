package com.byd.dashcast.phase1

import java.io.IOException

/** A source change resumes only at a fresh timestamped IDR; old P pictures are not spliced.
 * Frame timestamps must use the SurfaceFlinger/encoder monotonic clock domain.
 * @author Codex
 * @date 2026/10/22
 */
internal class CaptureSourceFence {
    private var lowerPts = Long.MAX_VALUE
    private var started = 0L
    private var nextSync = 0L
    private var waiting = true

    fun changed(nowNanos: Long) {
        require(nowNanos >= 0)
        lowerPts = nowNanos / 1000
        started = nowNanos
        nextSync = nowNanos
        waiting = true
    }

    fun needsSync(nowNanos: Long): Boolean {
        checkDeadline(nowNanos)
        if (!waiting || nowNanos < nextSync) { return false }
        nextSync = nowNanos + 250_000_000L
        return true
    }

    fun accept(ptsMicros: Long, key: Boolean, nowNanos: Long): Boolean {
        checkDeadline(nowNanos)
        if (ptsMicros < lowerPts) { return false }
        if (ptsMicros > nowNanos / 1000 + 1_000_000L) {
            throw IOException("APP_SOURCE_UNSUPPORTED_PTS_CLOCK")
        }
        if (waiting && !key) { return false }
        waiting = false
        return true
    }

    private fun checkDeadline(nowNanos: Long) {
        if (waiting && nowNanos - started >= 4_000_000_000L) {
            throw IOException("APP_SOURCE_FRESH_IDR_TIMEOUT; old/unrelated frames not forwarded")
        }
    }
}
