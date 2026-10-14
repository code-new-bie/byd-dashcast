package com.byd.dashcast.phase1;

import java.io.IOException;
import java.util.function.LongSupplier;
import java.util.concurrent.locks.LockSupport;

/**
 * Paces accepted access units on the monotonic clock before they enter DCV1.
 *
 * <p>PTS retiming alone does not constrain when a synchronous sender writes a frame. If the
 * encoder or pipe releases several access units together, their transport writes can otherwise
 * burst even though their PTS values are 33,333 microseconds apart. This class schedules the
 * start of each write at a fixed 30 FPS cadence. It never duplicates a picture and never catches
 * up by sending extra pictures; a slow ACK path is reported as late and the next slot is
 * re-anchored from the current time.</p>
 */
final class FramePacer {
    interface Sleeper {
        void parkNanos(long nanos);
    }

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long MICROS_PER_NANO = 1_000L;
    private final int targetFps;
    private final long intervalNanos;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;
    private boolean initialized;
    private long nextSlotNanos;
    private long scheduledFrames;
    private long lateFrames;
    private long totalWaitNanos;
    private long maxLateNanos;
    private long minTransportIntervalNanos = -1L;
    private long maxTransportIntervalNanos = -1L;
    private long burstIntervals;
    private long firstSendNanos = -1L;
    private long lastSendNanos = -1L;

    FramePacer(int targetFps) {
        this(targetFps, System::nanoTime, LockSupport::parkNanos);
    }

    FramePacer(int targetFps, LongSupplier nanoTime, Sleeper sleeper) {
        if (targetFps < 1 || targetFps > 120) {
            throw new IllegalArgumentException("Target FPS must be 1..120");
        }
        this.targetFps = targetFps;
        intervalNanos = NANOS_PER_SECOND / targetFps;
        this.nanoTime = java.util.Objects.requireNonNull(nanoTime, "nanoTime");
        this.sleeper = java.util.Objects.requireNonNull(sleeper, "sleeper");
    }

    /**
     * Waits for the next scheduled send slot and records the actual write start timestamp.
     * The caller must invoke this immediately before the synchronous transport write.
     */
    void awaitNextSlot() throws IOException {
        long beforeWait = nanoTime.getAsLong();
        if (lastSendNanos >= 0L && beforeWait < lastSendNanos) {
            throw new IOException("FRAME_PACER_CLOCK_MOVED_BACKWARD");
        }
        long slot = initialized ? nextSlotNanos : beforeWait;
        long now = beforeWait;
        while (now < slot) {
            long remaining = slot - now;
            sleeper.parkNanos(remaining);
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("FRAME_PACER_INTERRUPTED");
            }
            long after = nanoTime.getAsLong();
            if (after < now) {
                throw new IOException("FRAME_PACER_CLOCK_MOVED_BACKWARD");
            }
            now = after;
        }
        long late = Math.max(0L, now - slot);
        if (late > 0L) {
            lateFrames++;
            maxLateNanos = Math.max(maxLateNanos, late);
        }
        totalWaitNanos += Math.max(0L, now - beforeWait);
        scheduledFrames++;
        if (firstSendNanos < 0L) {
            firstSendNanos = now;
        }
        if (lastSendNanos >= 0L) {
            long interval = now - lastSendNanos;
            minTransportIntervalNanos = minTransportIntervalNanos < 0L
                    ? interval : Math.min(minTransportIntervalNanos, interval);
            maxTransportIntervalNanos = maxTransportIntervalNanos < 0L
                    ? interval : Math.max(maxTransportIntervalNanos, interval);
            if (interval < intervalNanos) {
                burstIntervals++;
            }
        }
        lastSendNanos = now;
        initialized = true;
        // A slow synchronous ACK/encode path must not make the next call immediately catch up
        // against an old deadline. Re-anchor after lateness so two writes are never emitted as a
        // burst merely because the previous write consumed more than one frame interval.
        long nextBase = late > 0L ? now : slot;
        if (nextBase > Long.MAX_VALUE - intervalNanos) {
            throw new IOException("FRAME_PACER_CLOCK_OVERFLOW");
        }
        nextSlotNanos = nextBase + intervalNanos;
    }

    long scheduledFrames() {
        return scheduledFrames;
    }

    long lateFrames() {
        return lateFrames;
    }

    long maxLateNanos() {
        return maxLateNanos;
    }

    String statistics() {
        return "FRAME_PACER target_fps=" + targetFps
                + " interval_ns=" + intervalNanos
                + " scheduled_frames=" + scheduledFrames
                + " late_frames=" + lateFrames
                + " max_late_us=" + maxLateNanos / MICROS_PER_NANO
                + " total_wait_ms=" + totalWaitNanos / 1_000_000L
                + " transport_start_fps_x100=" + rateX100(scheduledFrames, firstSendNanos, lastSendNanos)
                + " min_transport_interval_us=" + intervalMicros(minTransportIntervalNanos)
                + " max_transport_interval_us=" + intervalMicros(maxTransportIntervalNanos)
                + " burst_intervals=" + burstIntervals
                + " average_transport_interval_us=" + averageIntervalMicros()
                + "; monotonic_transport_schedule, no_frame_duplication, instrument_display_unverified";
    }

    private static long intervalMicros(long nanos) {
        return nanos < 0L ? 0L : nanos / MICROS_PER_NANO;
    }

    private long averageIntervalMicros() {
        if (scheduledFrames < 2L || firstSendNanos < 0L || lastSendNanos <= firstSendNanos) {
            return 0L;
        }
        return (lastSendNanos - firstSendNanos) / (scheduledFrames - 1L) / MICROS_PER_NANO;
    }

    private static long rateX100(long frames, long first, long last) {
        if (frames < 2L || first < 0L || last <= first) {
            return 0L;
        }
        return (frames - 1L) * NANOS_PER_SECOND * 100L / (last - first);
    }
}
