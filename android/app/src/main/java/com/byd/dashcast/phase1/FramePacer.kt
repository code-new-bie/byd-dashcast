package com.byd.dashcast.phase1

import java.io.IOException
import java.util.Objects
import java.util.concurrent.locks.LockSupport
import java.util.function.LongSupplier

/**
 * Paces accepted access units on the monotonic clock before they enter DCV1.
 *
 * PTS retiming alone does not constrain when a synchronous sender writes a frame. If the
 * encoder or pipe releases several access units together, their transport writes can otherwise
 * burst even though their PTS values are 33,333 microseconds apart. This class schedules the
 * start of each write at a fixed 30 FPS cadence. It never duplicates a picture and never catches
 * up by sending extra pictures; a slow ACK path is reported as late and the next slot is
 * re-anchored from the current time.
 */
internal class FramePacer(targetFps: Int, nanoTime: LongSupplier?, sleeper: Sleeper?) {
    fun interface Sleeper {
        fun parkNanos(nanos: Long)
    }

    private val targetFps: Int
    private val intervalNanos: Long
    private val nanoTime: LongSupplier
    private val sleeper: Sleeper
    private var initialized = false
    private var nextSlotNanos = 0L
    private var scheduledFrames = 0L
    private var lateFrames = 0L
    private var totalWaitNanos = 0L
    private var maxLateNanos = 0L
    private var minTransportIntervalNanos = -1L
    private var maxTransportIntervalNanos = -1L
    private var burstIntervals = 0L
    private var firstSendNanos = -1L
    private var lastSendNanos = -1L

    constructor(targetFps: Int) : this(targetFps, LongSupplier { System.nanoTime() }, Sleeper { LockSupport.parkNanos(it) })

    init {
        if (targetFps < 1 || targetFps > 120) {
            throw IllegalArgumentException("Target FPS must be 1..120")
        }
        this.targetFps = targetFps
        intervalNanos = NANOS_PER_SECOND / targetFps
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime")!!
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper")!!
    }

    /**
     * Waits for the next scheduled send slot and records the actual write start timestamp.
     * The caller must invoke this immediately before the synchronous transport write.
     */
    @Throws(IOException::class)
    fun awaitNextSlot() {
        val beforeWait = nanoTime.asLong
        if (lastSendNanos >= 0L && beforeWait < lastSendNanos) {
            throw IOException("FRAME_PACER_CLOCK_MOVED_BACKWARD")
        }
        val slot = if (initialized) nextSlotNanos else beforeWait
        var now = beforeWait
        while (now < slot) {
            sleeper.parkNanos(slot - now)
            if (Thread.currentThread().isInterrupted) {
                throw IOException("FRAME_PACER_INTERRUPTED")
            }
            val after = nanoTime.asLong
            if (after < now) {
                throw IOException("FRAME_PACER_CLOCK_MOVED_BACKWARD")
            }
            now = after
        }
        val late = maxOf(0L, now - slot)
        if (late > 0L) {
            lateFrames++
            maxLateNanos = maxOf(maxLateNanos, late)
        }
        totalWaitNanos += maxOf(0L, now - beforeWait)
        scheduledFrames++
        if (firstSendNanos < 0L) {
            firstSendNanos = now
        }
        if (lastSendNanos >= 0L) {
            val interval = now - lastSendNanos
            minTransportIntervalNanos = if (minTransportIntervalNanos < 0L) interval else minOf(minTransportIntervalNanos, interval)
            maxTransportIntervalNanos = if (maxTransportIntervalNanos < 0L) interval else maxOf(maxTransportIntervalNanos, interval)
            if (interval < intervalNanos) {
                burstIntervals++
            }
        }
        lastSendNanos = now
        initialized = true
        // A slow synchronous ACK/encode path must not make the next call immediately catch up
        // against an old deadline. Re-anchor after lateness so two writes are never emitted as a
        // burst merely because the previous write consumed more than one frame interval.
        val nextBase = if (late > 0L) now else slot
        if (nextBase > Long.MAX_VALUE - intervalNanos) {
            throw IOException("FRAME_PACER_CLOCK_OVERFLOW")
        }
        nextSlotNanos = nextBase + intervalNanos
    }

    fun scheduledFrames(): Long = scheduledFrames

    fun lateFrames(): Long = lateFrames

    fun maxLateNanos(): Long = maxLateNanos

    fun statistics(): String = "FRAME_PACER target_fps=" + targetFps +
        " interval_ns=" + intervalNanos +
        " scheduled_frames=" + scheduledFrames +
        " late_frames=" + lateFrames +
        " max_late_us=" + maxLateNanos / MICROS_PER_NANO +
        " total_wait_ms=" + totalWaitNanos / 1_000_000L +
        " transport_start_fps_x100=" + rateX100(scheduledFrames, firstSendNanos, lastSendNanos) +
        " min_transport_interval_us=" + intervalMicros(minTransportIntervalNanos) +
        " max_transport_interval_us=" + intervalMicros(maxTransportIntervalNanos) +
        " burst_intervals=" + burstIntervals +
        " average_transport_interval_us=" + averageIntervalMicros() +
        "; monotonic_transport_schedule, no_frame_duplication, instrument_display_unverified"

    private fun averageIntervalMicros(): Long {
        if (scheduledFrames < 2L || firstSendNanos < 0L || lastSendNanos <= firstSendNanos) {
            return 0L
        }
        return (lastSendNanos - firstSendNanos) / (scheduledFrames - 1L) / MICROS_PER_NANO
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val MICROS_PER_NANO = 1_000L

        fun intervalMicros(nanos: Long): Long = if (nanos < 0L) 0L else nanos / MICROS_PER_NANO

        fun rateX100(frames: Long, first: Long, last: Long): Long {
            if (frames < 2L || first < 0L || last <= first) {
                return 0L
            }
            return ContinuousSessionPolicy.rateX100(frames, first, last, NANOS_PER_SECOND)
        }
    }
}
