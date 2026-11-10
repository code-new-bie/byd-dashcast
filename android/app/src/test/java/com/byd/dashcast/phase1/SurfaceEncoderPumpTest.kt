package com.byd.dashcast.phase1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Offline encoder-control tests; not execution of Android EGL or a hardware codec. */
class SurfaceEncoderPumpTest {
    @Test
    fun drainsBeforeEveryInputAndPreservesPacedMonotonicPts() {
        val backend = FakeEncoder()
        assertEquals(30, SurfaceEncoderPump.run(1, 30, backend.cancelled, backend))
        assertEquals(30, backend.outputFrames())
        assertEquals(1, backend.eosSignals)
        assertEquals(1_000_000L, backend.pts[0])
        assertEquals(1_966_666L, backend.pts[29])
        for (index in backend.times.indices) {
            val due = index * 1_000_000_000L / 30
            assertTrue(backend.times[index] >= due)
            assertTrue(backend.times[index] - due < 1_000_000L)
        }
    }

    @Test
    fun cancelledBeforeInputStillDrainsAndSignalsEosOnce() {
        val backend = FakeEncoder()
        backend.cancelled.set(true)
        assertEquals(0, SurfaceEncoderPump.run(1, 30, backend.cancelled, backend))
        assertEquals(1, backend.eosSignals)
        assertEquals(0, backend.pts.size)
    }

    @Test
    fun cancellationStopsInputButCountsAlreadyQueuedOutput() {
        val backend = FakeEncoder()
        backend.cancelAfter = 3
        assertEquals(3, SurfaceEncoderPump.run(1, 30, backend.cancelled, backend))
        assertEquals(3, backend.outputFrames())
        assertEquals(1, backend.eosSignals)
    }

    @Test
    fun missingOutputCannotBecomeSuccessfulEndOfStream() {
        val backend = FakeEncoder()
        backend.dropOutput = true
        val error = assertThrows(IOException::class.java) { SurfaceEncoderPump.run(1, 30, backend.cancelled, backend) }
        assertTrue(error.message!!.contains("Incomplete encoder output"))
        assertEquals(1, backend.eosSignals)
    }

    @Test
    fun eosDrainHasAFiniteDeadline() {
        val backend = FakeEncoder()
        backend.neverEos = true
        val error = assertThrows(IOException::class.java) { SurfaceEncoderPump.run(1, 30, backend.cancelled, backend) }
        assertTrue(error.message!!.contains("EOS timeout"))
        assertTrue(backend.clock < 7_000_000_000L)
        assertEquals(1, backend.eosSignals)
    }

    @Test
    fun prematureOutputEosIsRejectedRatherThanZeroFrameSuccess() {
        val backend = FakeEncoder()
        backend.prematureEos = true
        assertThrows(IOException::class.java) { SurfaceEncoderPump.run(1, 30, backend.cancelled, backend) }
        assertEquals(0, backend.eosSignals)
        assertEquals(0, backend.pts.size)
    }

    @Test
    fun rendererFailurePropagatesWithoutSubmittingAdditionalFrames() {
        val backend = FakeEncoder()
        backend.failRender = true
        assertThrows(IOException::class.java) { SurfaceEncoderPump.run(1, 30, backend.cancelled, backend) }
        assertEquals(0, backend.pts.size)
        assertEquals(0, backend.eosSignals)
    }

    @Test
    fun stalledInputWorkHasAFiniteDeadline() {
        val backend = FakeEncoder()
        backend.workDelay = 12_000_000_000L
        val error = assertThrows(IOException::class.java) { SurfaceEncoderPump.run(1, 30, backend.cancelled, backend) }
        assertTrue(error.message!!.contains("input stalled"))
        assertEquals(1, backend.pts.size)
    }

    @Test
    fun supportsUpperBoundWithoutPtsOverflow() {
        val backend = FakeEncoder()
        assertEquals(3600, SurfaceEncoderPump.run(60, 60, backend.cancelled, backend))
        assertEquals(60_983_333L, backend.pts[3599])
        assertEquals(1, backend.eosSignals)
    }

    @Test
    fun validatesDurationFpsAndRequiredBoundaries() {
        val backend = FakeEncoder()
        for (seconds in intArrayOf(0, 61, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                SurfaceEncoderPump.run(seconds, 30, backend.cancelled, backend)
            }
        }
        for (fps in intArrayOf(0, 61)) {
            assertThrows(IllegalArgumentException::class.java) {
                SurfaceEncoderPump.run(1, fps, backend.cancelled, backend)
            }
        }
        assertThrows(NullPointerException::class.java) { SurfaceEncoderPump.run(1, 30, null, backend) }
        assertThrows(NullPointerException::class.java) { SurfaceEncoderPump.run(1, 30, backend.cancelled, null) }
    }

    private class FakeEncoder : SurfaceEncoderPump.Backend {
        val cancelled = AtomicBoolean()
        val pts = ArrayList<Long>()
        val times = ArrayList<Long>()
        var clock = 0L
        var workDelay = 0L
        var received = 0
        var eosSignals = 0
        var cancelAfter = Int.MAX_VALUE
        var drained = false
        var dropOutput = false
        var neverEos = false
        var prematureEos = false
        var failRender = false

        override fun nanoTime(): Long = clock

        override fun drain(): Boolean {
            drained = true
            received = if (dropOutput) 0 else pts.size
            if (pts.size >= cancelAfter) {
                cancelled.set(true)
            }
            return prematureEos || (eosSignals > 0 && !neverEos)
        }

        override fun outputFrames(): Int = received

        override fun render(frame: Int, ptsMicros: Long) {
            assertTrue("Output must be drained before each surface swap", drained)
            if (failRender) {
                throw IOException("Fixture render failure")
            }
            drained = false
            assertEquals(pts.size, frame)
            times.add(clock)
            pts.add(ptsMicros)
            clock += workDelay
        }

        override fun signalEndOfInput() {
            assertEquals(0, eosSignals++)
        }

        override fun pause(nanos: Long) {
            assertTrue(nanos > 0 && nanos <= 1_000_000L)
            clock += nanos
        }
    }
}
