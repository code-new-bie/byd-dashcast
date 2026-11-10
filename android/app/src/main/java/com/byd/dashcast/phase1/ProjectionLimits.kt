package com.byd.dashcast.phase1

/**
 * Finite engineering profiles; normal product capture uses ContinuousSessionPolicy, not these totals.
 * @author Codex
 * @date 2026/10/13
 */
internal object ProjectionLimits {
    const val TARGET_FRAME_INTERVAL_US = 1_000_000L / NoAuthVideoProtocol.FPS
    const val MAX_SECONDS = 7200
    const val MAX_FRAMES = 240000
    const val MAX_CAPTURE_FRAMES = 500000
    const val MAX_CAPTURE_BYTES = 8L * 1024 * 1024 * 1024
    const val STOP_TIMEOUT_NANOS = 10_000_000_000L

    @JvmStatic
    fun requireSeconds(seconds: Int) {
        if (seconds < 1 || seconds > MAX_SECONDS) {
            throw IllegalArgumentException("Continuous capture requires 1..7200 seconds")
        }
    }
}
