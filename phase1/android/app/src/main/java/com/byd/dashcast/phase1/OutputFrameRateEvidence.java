package com.byd.dashcast.phase1;

import java.io.IOException;

/**
 * Measures the actual picture access units emitted by the Android encoder.
 *
 * <p>This is deliberately separate from {@link FrameRateGate}: the gate controls the stream
 * entering DCV1, while this class answers whether the encoder itself produced a sufficiently
 * long, stable 30 FPS sample. It never duplicates a picture and it never treats a transport ACK
 * as a decoded or displayed frame.</p>
 */
final class OutputFrameRateEvidence {
    static final int MIN_STABLE_FRAMES = 60;
    static final long MIN_FPS_X100 = 2_850L;
    static final long MAX_FPS_X100 = 3_150L;
    static final long MIN_PTS_INTERVAL_US = 30_000L;
    static final long MAX_PTS_INTERVAL_US = 36_667L;

    private final int targetFps;
    private long frames;
    private long firstPts = -1L;
    private long lastPts = -1L;
    private long firstWallNanos = Long.MIN_VALUE;
    private long lastWallNanos = Long.MIN_VALUE;
    private long minPtsIntervalUs = -1L;
    private long maxPtsIntervalUs = -1L;

    OutputFrameRateEvidence(int targetFps) {
        if (targetFps < 1 || targetFps > 120) {
            throw new IllegalArgumentException("Target FPS must be 1..120");
        }
        this.targetFps = targetFps;
    }

    void record(long ptsMicros, long wallNanos) throws IOException {
        if (ptsMicros < 0L) {
            throw new IOException("SHELL_CAPTURE_FRAME_TIMING_INVALID");
        }
        if (lastPts >= 0L && ptsMicros <= lastPts) {
            throw new IOException("SHELL_CAPTURE_FRAME_TIMING_PTS_NOT_MONOTONIC");
        }
        if (lastWallNanos != Long.MIN_VALUE && wallNanos <= lastWallNanos) {
            throw new IOException("SHELL_CAPTURE_FRAME_TIMING_CLOCK_NOT_MONOTONIC");
        }
        if (frames == 0L) {
            firstPts = ptsMicros;
            firstWallNanos = wallNanos;
        } else {
            long ptsInterval = ptsMicros - lastPts;
            minPtsIntervalUs = minPtsIntervalUs < 0L
                    ? ptsInterval : Math.min(minPtsIntervalUs, ptsInterval);
            maxPtsIntervalUs = maxPtsIntervalUs < 0L
                    ? ptsInterval : Math.max(maxPtsIntervalUs, ptsInterval);
        }
        frames++;
        lastPts = ptsMicros;
        lastWallNanos = wallNanos;
    }

    long sampleFrames() {
        return frames;
    }

    Result result() {
        long ptsFpsX100 = rateX100(frames, firstPts, lastPts, 1_000_000L);
        long wallFpsX100 = rateX100(frames, firstWallNanos, lastWallNanos, 1_000_000_000L);
        long averageIntervalUs = averageInterval(firstPts, lastPts, frames);
        boolean enough = frames >= MIN_STABLE_FRAMES;
        boolean rateInRange = ptsFpsX100 >= MIN_FPS_X100 && ptsFpsX100 <= MAX_FPS_X100
                && wallFpsX100 >= MIN_FPS_X100 && wallFpsX100 <= MAX_FPS_X100;
        boolean intervalInRange = minPtsIntervalUs >= MIN_PTS_INTERVAL_US
                && maxPtsIntervalUs <= MAX_PTS_INTERVAL_US;
        String status = !enough ? "INSUFFICIENT_SAMPLE"
                : rateInRange && intervalInRange ? "PASS" : "FAIL";
        return new Result(status, targetFps, frames, ptsFpsX100, wallFpsX100,
                minPtsIntervalUs, maxPtsIntervalUs, averageIntervalUs);
    }

    void requireStable() throws IOException {
        Result value = result();
        if (!value.stable()) {
            throw new IOException("SHELL_CAPTURE_FPS_GATE_" + value.status);
        }
    }

    static final class Result {
        final String status;
        final int targetFps;
        final long frames;
        final long ptsFpsX100;
        final long wallFpsX100;
        final long minPtsIntervalUs;
        final long maxPtsIntervalUs;
        final long averagePtsIntervalUs;

        Result(String status, int targetFps, long frames, long ptsFpsX100, long wallFpsX100,
                long minPtsIntervalUs, long maxPtsIntervalUs, long averagePtsIntervalUs) {
            this.status = status;
            this.targetFps = targetFps;
            this.frames = frames;
            this.ptsFpsX100 = ptsFpsX100;
            this.wallFpsX100 = wallFpsX100;
            this.minPtsIntervalUs = minPtsIntervalUs;
            this.maxPtsIntervalUs = maxPtsIntervalUs;
            this.averagePtsIntervalUs = averagePtsIntervalUs;
        }

        boolean stable() {
            return "PASS".equals(status);
        }

        String logLine() {
            return "SHELL_CAPTURE_FPS_GATE status=" + status
                    + " target_fps=" + targetFps
                    + " sample_frames=" + frames
                    + " min_stable_frames=" + MIN_STABLE_FRAMES
                    + " pts_fps_x100=" + ptsFpsX100
                    + " wall_fps_x100=" + wallFpsX100
                    + " pts_min_interval_us=" + minPtsIntervalUs
                    + " pts_max_interval_us=" + maxPtsIntervalUs
                    + "; encoder_output_only, instrument_display_unverified";
        }
    }

    private static long rateX100(long count, long first, long last, long unitsPerSecond) {
        if (count < 2L || first == Long.MIN_VALUE || last <= first) {
            return 0L;
        }
        return (count - 1L) * unitsPerSecond * 100L / (last - first);
    }

    private static long averageInterval(long first, long last, long count) {
        if (count < 2L || first < 0L || last <= first) {
            return 0L;
        }
        return (last - first) / (count - 1L);
    }
}
