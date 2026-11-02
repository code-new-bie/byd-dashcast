package com.byd.dashcast.phase1;

/** Finite engineering profiles; normal product capture uses ContinuousSessionPolicy, not these totals.
 * @author Codex
 * @date 2026/10/13
 */
final class ProjectionLimits {
    static final long TARGET_FRAME_INTERVAL_US = 1_000_000L / NoAuthVideoProtocol.FPS;
    static final int MAX_SECONDS = 7200;
    static final int MAX_FRAMES = 240000;
    static final int MAX_CAPTURE_FRAMES = 500000;
    static final long MAX_CAPTURE_BYTES = 8L * 1024 * 1024 * 1024;
    static final long STOP_TIMEOUT_NANOS = 10_000_000_000L;

    private ProjectionLimits() {
    }

    static void requireSeconds(int seconds) {
        if (seconds < 1 || seconds > MAX_SECONDS) {
            throw new IllegalArgumentException("Continuous capture requires 1..7200 seconds");
        }
    }
}
