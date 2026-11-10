package com.byd.dashcast.phase1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import java.util.function.LongSupplier

/** Monotonic transport pacing tests; they do not claim instrument display FPS. */
class FramePacerTest {
    @Test
    fun schedulesWritesAtThirtyFpsWithoutBursting() {
        val clock = AtomicLong()
        val pacer = FramePacer(30, LongSupplier { clock.get() }, FramePacer.Sleeper { nanos -> clock.addAndGet(nanos) })

        pacer.awaitNextSlot()
        clock.addAndGet(1_000_000L)
        pacer.awaitNextSlot()
        clock.addAndGet(1_000_000L)
        pacer.awaitNextSlot()

        assertEquals(3L, pacer.scheduledFrames())
        assertEquals(0L, pacer.lateFrames())
        assertTrue(pacer.statistics().contains("transport_start_fps_x100=3000"))
        assertTrue(pacer.statistics().contains("min_transport_interval_us=33333"))
        assertTrue(pacer.statistics().contains("max_transport_interval_us=33333"))
        assertTrue(pacer.statistics().contains("burst_intervals=0"))
        assertTrue(pacer.statistics().contains("average_transport_interval_us=33333"))
    }

    @Test
    fun reportsSlowAckPathAsLateInsteadOfCatchingUpWithExtraFrames() {
        val clock = AtomicLong()
        val pacer = FramePacer(30, LongSupplier { clock.get() }, FramePacer.Sleeper { nanos -> clock.addAndGet(nanos) })

        pacer.awaitNextSlot()
        clock.addAndGet(40_000_000L)
        pacer.awaitNextSlot()
        val secondSend = clock.get()
        pacer.awaitNextSlot()

        assertEquals(3L, pacer.scheduledFrames())
        assertEquals(1L, pacer.lateFrames())
        assertTrue(pacer.maxLateNanos() >= 6_000_000L)
        assertEquals(33_333_333L, clock.get() - secondSend)
        assertTrue(pacer.statistics().contains("min_transport_interval_us=33333"))
        assertTrue(pacer.statistics().contains("max_transport_interval_us=40000"))
        assertTrue(pacer.statistics().contains("burst_intervals=0"))
        assertTrue(pacer.statistics().contains("late_frames=1"))
    }

    @Test
    fun rejectsMonotonicClockRegressionBetweenWrites() {
        val clock = AtomicLong()
        val pacer = FramePacer(30, LongSupplier { clock.get() }, FramePacer.Sleeper { nanos -> clock.addAndGet(nanos) })

        pacer.awaitNextSlot()
        clock.set(-1L)

        assertThrows(IOException::class.java) { pacer.awaitNextSlot() }
    }

    @Test
    fun rejectsInvalidTargetRate() {
        val clock = AtomicLong()
        assertThrows(IllegalArgumentException::class.java) {
            FramePacer(0, LongSupplier { clock.get() }, FramePacer.Sleeper {})
        }
        assertThrows(IllegalArgumentException::class.java) {
            FramePacer(121, LongSupplier { clock.get() }, FramePacer.Sleeper {})
        }
    }
}
