package com.byd.dashcast.phase1;

/** Explicit, finite bounds for the first continuous small-window milestone.
 * @author Codex
 * @date 2026/10/13
 */
final class ProjectionLimits {
    static final int MAX_SECONDS = 1800;
    static final int MAX_FRAMES = 65000;
    static final long MAX_BYTES = 512L * 1024 * 1024;
    static final long STOP_TIMEOUT_NANOS = 10_000_000_000L;

    private ProjectionLimits() {
    }

    static void requireSeconds(int seconds) {
        if (seconds < 1 || seconds > MAX_SECONDS) {
            throw new IllegalArgumentException("Continuous capture requires 1..1800 seconds");
        }
    }
}
