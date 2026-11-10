package com.byd.dashcast.phase1

import java.io.IOException

/**
 * Preserves every encoded access unit and its original monotonic presentation timestamp.
 *
 * The source encoder may produce more than the requested rate on the Android head unit.
 * Rate limiting belongs at the encoder's Surface input and in the transport pacer. Dropping a
 * coded P picture can invalidate the following reference-dependent pictures. Rewriting a slow
 * source to 33,333-us increments also compresses its timeline without improving real FPS.
 * The input limiter and transport pacer enforce rate ceilings; this class validates and measures
 * the real timeline. The existing DCV1 gap/staleness protections remain authoritative.
 *
 * @author Codex
 * @date 2026/10/14
 */
internal class FrameRateGate(private val targetFps: Int) {
    private var firstInputPts = -1L
    private var lastInputPts = -1L
    private var firstOutputPts = -1L
    private var lastOutputPts = -1L
    private var inputFrames = 0L
    private var outputFrames = 0L

    init {
        if (targetFps < 1 || targetFps > 120) {
            throw IllegalArgumentException("Target FPS must be 1..120")
        }
    }

    /** Returns the source PTS unchanged, without dropping references or hiding source gaps. */
    @Throws(IOException::class)
    fun outputPts(ptsMicros: Long): Long {
        if (ptsMicros < 0L || (lastInputPts >= 0L && ptsMicros <= lastInputPts)) {
            throw IOException("FRAME_RATE_GATE_NON_MONOTONIC_PTS")
        }
        if (firstInputPts < 0L) {
            firstInputPts = ptsMicros
        }
        lastInputPts = ptsMicros
        inputFrames = ContinuousSessionPolicy.add(inputFrames, 1)
        if (firstOutputPts < 0L) {
            firstOutputPts = ptsMicros
        }
        lastOutputPts = ptsMicros
        outputFrames = ContinuousSessionPolicy.add(outputFrames, 1)
        return lastOutputPts
    }

    /** Compatibility helper; valid encoded pictures are always preserved. */
    @Throws(IOException::class)
    fun accept(ptsMicros: Long): Boolean = outputPts(ptsMicros) >= 0L

    fun inputFrames(): Long = inputFrames

    fun outputFrames(): Long = outputFrames

    fun droppedFrames(): Long = 0L

    fun statistics(): String = "FRAME_RATE_GATE target_fps=" + targetFps +
        " input_frames=" + inputFrames +
        " output_frames=" + outputFrames +
        " dropped_frames=0" +
        " input_pts_fps_x100=" + rateX100(inputFrames, firstInputPts, lastInputPts) +
        " output_pts_fps_x100=" + rateX100(outputFrames, firstOutputPts, lastOutputPts) +
        " output_interval_us=variable" +
        " retimed=false preserve_source_timeline=true preserve_encoded_references=true;" +
        " source PTS cadence only, instrument decoded/display FPS not measured"

    private companion object {
        const val MICROS_PER_SECOND = 1_000_000L

        fun rateX100(frames: Long, firstPts: Long, lastPts: Long): Long {
            if (frames < 2L || firstPts < 0L || lastPts <= firstPts) {
                return 0L
            }
            return ContinuousSessionPolicy.rateX100(frames, firstPts, lastPts, MICROS_PER_SECOND)
        }
    }
}
