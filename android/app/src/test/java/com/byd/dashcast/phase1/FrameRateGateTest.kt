package com.byd.dashcast.phase1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Encoded reference-chain preservation and PTS validation; no display FPS claim. */
class FrameRateGateTest {
    @Test
    fun preservesCodedPicturesWhenSourceTimestampsJitter() {
        val gate = FrameRateGate(30)
        assertTrue(gate.accept(0L))
        assertTrue(gate.accept(10_000L))
        assertTrue(gate.accept(20_000L))
        assertTrue(gate.accept(33_333L))
        assertTrue(gate.accept(40_000L))
        assertTrue(gate.accept(66_666L))
        assertTrue(gate.accept(70_000L))
        assertEquals(7L, gate.inputFrames())
        assertEquals(7L, gate.outputFrames())
        assertEquals(0L, gate.droppedFrames())
        assertTrue(gate.statistics().contains("target_fps=30"))
        assertTrue(gate.statistics().contains("output_interval_us=variable"))
        assertTrue(gate.statistics().contains("retimed=false"))
        assertTrue(gate.statistics().contains("preserve_source_timeline=true"))
    }

    @Test
    fun preservesAcceptedPicturesOnTheRealSourceTimeline() {
        val gate = FrameRateGate(30)
        assertEquals(100_000L, gate.outputPts(100_000L))
        assertEquals(116_000L, gate.outputPts(116_000L))
        assertEquals(150_000L, gate.outputPts(150_000L))
        assertEquals(166_000L, gate.outputPts(166_000L))
        assertEquals(190_000L, gate.outputPts(190_000L))
        assertEquals(5L, gate.outputFrames())
        assertTrue(gate.statistics().contains("output_pts_fps_x100=4444"))
    }

    @Test
    fun slowSourceIsNotFalselyReportedAsThirtyFramesPerSecond() {
        val gate = FrameRateGate(30)
        for (i in 0 until 100) { assertEquals(i * 100_000L, gate.outputPts(i * 100_000L)) }
        assertTrue(gate.statistics().contains("input_pts_fps_x100=1000"))
        assertTrue(gate.statistics().contains("output_pts_fps_x100=1000"))
        assertEquals(100L, gate.outputFrames())
        assertEquals(0L, gate.droppedFrames())
    }

    @Test
    fun realStallRemainsVisibleToExistingDownstreamStalenessProtection() {
        val gate = FrameRateGate(30)
        gate.outputPts(0)
        assertEquals(9_672_573L, gate.outputPts(9_672_573L))
        assertEquals(Long.MAX_VALUE, gate.outputPts(Long.MAX_VALUE))
        assertThrows(IOException::class.java) { gate.outputPts(Long.MAX_VALUE) }
    }

    @Test
    fun rejectsNonMonotonicEncoderPts() {
        val gate = FrameRateGate(30)
        gate.accept(100L)
        assertThrows(IOException::class.java) { gate.accept(100L) }
        assertThrows(IOException::class.java) { gate.accept(-1L) }
    }

    @Test
    fun rejectsInvalidTargetRate() {
        assertThrows(IllegalArgumentException::class.java) { FrameRateGate(0) }
        assertThrows(IllegalArgumentException::class.java) { FrameRateGate(121) }
    }
}
