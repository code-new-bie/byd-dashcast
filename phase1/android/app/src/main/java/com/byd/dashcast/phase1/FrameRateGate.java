package com.byd.dashcast.phase1;

import java.io.IOException;

/**
 * Selects at most one encoded access unit per target frame interval using encoder PTS.
 *
 * <p>The source encoder may produce more than the requested rate on the Android head unit.
 * Dropping before DCV1 keeps the instrument stream bounded without queueing stale pictures.
 * Accepted pictures are re-timestamped to a fixed target cadence before they enter DCV1. This
 * removes small source-clock jitter without inventing pictures when the encoder is slow. The
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
    private long nextOutputPts = -1L;
    private long inputFrames;
    private long outputFrames;
    private long droppedFrames;

    FrameRateGate(int targetFps) {
        if (targetFps < 1 || targetFps > 120) {
            throw new IllegalArgumentException("Target FPS must be 1..120");
        }
        this.targetFps = targetFps;
        intervalMicros = MICROS_PER_SECOND / targetFps;
    }

    /**
     * Returns the fixed output PTS for an accepted picture, or {@code -1} when the source picture
     * is intentionally dropped. The input PTS remains the selection clock; the returned PTS is
     * the transport clock.
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
        if (nextOutputPts >= 0L && ptsMicros < nextOutputPts) {
            droppedFrames++;
            return -1L;
        }
        if (firstOutputPts < 0L) {
            firstOutputPts = ptsMicros;
            lastOutputPts = ptsMicros;
        } else {
            lastOutputPts = advanceOutputPts(lastOutputPts);
        }
        outputFrames++;
        nextOutputPts = advanceDeadline(ptsMicros);
        return lastOutputPts;
    }

    /** Compatibility helper for tests and callers that only need the selection decision. */
    boolean accept(long ptsMicros) throws IOException {
        return outputPts(ptsMicros) >= 0L;
    }

    private long advanceDeadline(long ptsMicros) throws IOException {
        long deadline = ptsMicros + intervalMicros;
        if (deadline <= ptsMicros) {
            throw new IOException("FRAME_RATE_GATE_PTS_OVERFLOW");
        }
        return deadline;
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
        return droppedFrames;
    }

    String statistics() {
        return "FRAME_RATE_GATE target_fps=" + targetFps
                + " input_frames=" + inputFrames
                + " output_frames=" + outputFrames
                + " dropped_frames=" + droppedFrames
                + " input_pts_fps_x100=" + rateX100(inputFrames, firstInputPts, lastInputPts)
                + " output_pts_fps_x100=" + rateX100(outputFrames, firstOutputPts, lastOutputPts)
                + " output_interval_us=" + intervalMicros
                + " retimed=true; PTS cadence only, instrument decoded/display FPS not measured";
    }

    private static long rateX100(long frames, long firstPts, long lastPts) {
        if (frames < 2L || firstPts < 0L || lastPts <= firstPts) {
            return 0L;
        }
        return (frames - 1L) * MICROS_PER_SECOND * RATE_SCALE / (lastPts - firstPts);
    }
}
