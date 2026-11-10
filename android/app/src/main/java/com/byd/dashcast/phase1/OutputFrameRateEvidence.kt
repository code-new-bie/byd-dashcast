package com.byd.dashcast.phase1

import java.io.IOException

/**
 * Measures the actual picture access units emitted by the Android encoder.
 *
 * This is deliberately separate from [FrameRateGate]: the gate controls the stream entering DCV1
 * without rewriting its source timeline, while this class answers whether the encoder itself
 * produced a sufficiently long, stable 30 FPS sample. It never duplicates a picture and it never
 * treats a transport ACK as a decoded or displayed frame.
 */
internal class OutputFrameRateEvidence(private val targetFps: Int) {
    private var frames = 0L
    private var firstPts = -1L
    private var lastPts = -1L
    private var firstWallNanos = Long.MIN_VALUE
    private var lastWallNanos = Long.MIN_VALUE
    private var minPtsIntervalUs = -1L
    private var maxPtsIntervalUs = -1L

    init {
        if (targetFps < 1 || targetFps > 120) {
            throw IllegalArgumentException("Target FPS must be 1..120")
        }
    }

    @Throws(IOException::class)
    fun record(ptsMicros: Long, wallNanos: Long) {
        if (ptsMicros < 0L) {
            throw IOException("SHELL_CAPTURE_FRAME_TIMING_INVALID")
        }
        if (lastPts >= 0L && ptsMicros <= lastPts) {
            throw IOException("SHELL_CAPTURE_FRAME_TIMING_PTS_NOT_MONOTONIC")
        }
        if (lastWallNanos != Long.MIN_VALUE && wallNanos <= lastWallNanos) {
            throw IOException("SHELL_CAPTURE_FRAME_TIMING_CLOCK_NOT_MONOTONIC")
        }
        if (frames == 0L) {
            firstPts = ptsMicros
            firstWallNanos = wallNanos
        } else {
            val ptsInterval = ptsMicros - lastPts
            minPtsIntervalUs = if (minPtsIntervalUs < 0L) ptsInterval else minOf(minPtsIntervalUs, ptsInterval)
            maxPtsIntervalUs = if (maxPtsIntervalUs < 0L) ptsInterval else maxOf(maxPtsIntervalUs, ptsInterval)
        }
        frames = ContinuousSessionPolicy.add(frames, 1)
        lastPts = ptsMicros
        lastWallNanos = wallNanos
    }

    fun sampleFrames(): Long = frames

    fun result(): Result {
        val ptsFpsX100 = rateX100(frames, firstPts, lastPts, 1_000_000L)
        val wallFpsX100 = rateX100(frames, firstWallNanos, lastWallNanos, 1_000_000_000L)
        val averageIntervalUs = averageInterval(firstPts, lastPts, frames)
        val enough = frames >= MIN_STABLE_FRAMES
        val rateInRange = ptsFpsX100 >= MIN_FPS_X100 && ptsFpsX100 <= MAX_FPS_X100 &&
            wallFpsX100 >= MIN_FPS_X100 && wallFpsX100 <= MAX_FPS_X100
        val intervalInRange = minPtsIntervalUs >= MIN_PTS_INTERVAL_US && maxPtsIntervalUs <= MAX_PTS_INTERVAL_US
        val status = when {
            !enough -> "INSUFFICIENT_SAMPLE"
            rateInRange && intervalInRange -> "PASS"
            else -> "FAIL"
        }
        return Result(status, targetFps, frames, ptsFpsX100, wallFpsX100,
            minPtsIntervalUs, maxPtsIntervalUs, averageIntervalUs)
    }

    @Throws(IOException::class)
    fun requireStable() {
        val value = result()
        if (!value.stable()) {
            throw IOException("SHELL_CAPTURE_FPS_GATE_" + value.status)
        }
    }

    class Result(
        @JvmField val status: String,
        @JvmField val targetFps: Int,
        @JvmField val frames: Long,
        @JvmField val ptsFpsX100: Long,
        @JvmField val wallFpsX100: Long,
        @JvmField val minPtsIntervalUs: Long,
        @JvmField val maxPtsIntervalUs: Long,
        @JvmField val averagePtsIntervalUs: Long,
    ) {
        fun stable(): Boolean = "PASS" == status

        fun logLine(): String = "SHELL_CAPTURE_FPS_GATE status=" + status +
            " target_fps=" + targetFps +
            " sample_frames=" + frames +
            " min_stable_frames=" + MIN_STABLE_FRAMES +
            " pts_fps_x100=" + ptsFpsX100 +
            " wall_fps_x100=" + wallFpsX100 +
            " pts_min_interval_us=" + minPtsIntervalUs +
            " pts_max_interval_us=" + maxPtsIntervalUs +
            "; encoder_output_only, instrument_display_unverified"
    }

    companion object {
        const val MIN_STABLE_FRAMES = 60
        const val MIN_FPS_X100 = 2_850L
        const val MAX_FPS_X100 = 3_150L
        const val MIN_PTS_INTERVAL_US = 30_000L
        const val MAX_PTS_INTERVAL_US = 36_667L

        private fun rateX100(count: Long, first: Long, last: Long, unitsPerSecond: Long): Long {
            if (count < 2L || first == Long.MIN_VALUE || last <= first) {
                return 0L
            }
            return ContinuousSessionPolicy.rateX100(count, first, last, unitsPerSecond)
        }

        private fun averageInterval(first: Long, last: Long, count: Long): Long {
            if (count < 2L || first < 0L || last <= first) {
                return 0L
            }
            return (last - first) / (count - 1L)
        }
    }
}
