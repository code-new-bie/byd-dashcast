package com.byd.dashcast.phase1;

import java.io.IOException;

/**
 * Preserves every encoded access unit and assigns a bounded transport PTS cadence.
 *
 * <p>The source encoder may produce more than the requested rate on the Android head unit.
 * Rate limiting belongs at the encoder's Surface input and in the transport pacer. Dropping a
 * coded P picture can invalidate the following reference-dependent pictures. All valid pictures
 * are re-timestamped to a fixed target cadence before they enter DCV1. The
 * class measures the resulting PTS cadence; it does not claim decoded-display timing.</p>
 *
 * @author Codex
 * @date 2026/10/14
 */
final class FrameRateGate {
    private static final long MICROS_PER_SECOND = 1_000_000L;
    private static final long RATE_SCALE = 100L;
    private final int targetFps;
    private final long intervalMicros;
    private long firstInputPts = -1L;
    private long lastInputPts = -1L;
    private long firstOutputPts = -1L;
    private long lastOutputPts = -1L;
    private long inputFrames;
    private long outputFrames;

    FrameRateGate(int targetFps) {
        if (targetFps < 1 || targetFps > 120) {
            throw new IllegalArgumentException("Target FPS must be 1..120");
        }
        this.targetFps = targetFps;
        intervalMicros = MICROS_PER_SECOND / targetFps;
    }

    /**
     * Returns the fixed transport PTS for every valid picture without discarding coded references.
     */
    long outputPts(long ptsMicros) throws IOException {
        if (ptsMicros < 0L || lastInputPts >= 0L && ptsMicros <= lastInputPts) {
            throw new IOException("FRAME_RATE_GATE_NON_MONOTONIC_PTS");
        }
        if (firstInputPts < 0L) {
            firstInputPts = ptsMicros;
        }
        lastInputPts = ptsMicros;
        inputFrames++;
        if (firstOutputPts < 0L) {
            firstOutputPts = ptsMicros;
            lastOutputPts = ptsMicros;
        } else {
            lastOutputPts = advanceOutputPts(lastOutputPts);
        }
        outputFrames++;
        return lastOutputPts;
    }

    /** Compatibility helper; valid encoded pictures are always preserved. */
    boolean accept(long ptsMicros) throws IOException {
        return outputPts(ptsMicros) >= 0L;
    }

    private long advanceOutputPts(long ptsMicros) throws IOException {
        long next = ptsMicros + intervalMicros;
        if (next <= ptsMicros) {
            throw new IOException("FRAME_RATE_GATE_OUTPUT_PTS_OVERFLOW");
        }
        return next;
    }

    long inputFrames() {
        return inputFrames;
    }

    long outputFrames() {
        return outputFrames;
    }

    long droppedFrames() {
        return 0L;
    }

    String statistics() {
        return "FRAME_RATE_GATE target_fps=" + targetFps
                + " input_frames=" + inputFrames
                + " output_frames=" + outputFrames
                + " dropped_frames=0"
                + " input_pts_fps_x100=" + rateX100(inputFrames, firstInputPts, lastInputPts)
                + " output_pts_fps_x100=" + rateX100(outputFrames, firstOutputPts, lastOutputPts)
                + " output_interval_us=" + intervalMicros
                + " retimed=true preserve_encoded_references=true; PTS cadence only, instrument decoded/display FPS not measured";
    }

    private static long rateX100(long frames, long firstPts, long lastPts) {
        if (frames < 2L || firstPts < 0L || lastPts <= firstPts) {
            return 0L;
        }
        return (frames - 1L) * MICROS_PER_SECOND * RATE_SCALE / (lastPts - firstPts);
    }
}
