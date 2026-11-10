package com.byd.dashcast.phase1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Encoder-output timing evidence tests; they do not claim QNX decode or display timing. */
class OutputFrameRateEvidenceTest {
    @Test
    fun acceptsAStableThirtyFpsSample() {
        val evidence = OutputFrameRateEvidence(30)
        for (frame in 0 until OutputFrameRateEvidence.MIN_STABLE_FRAMES) {
            evidence.record(frame * 33_333L, frame * 33_333_333L)
        }

        val result = evidence.result()
        assertTrue(result.stable())
        assertEquals(OutputFrameRateEvidence.MIN_STABLE_FRAMES.toLong(), evidence.sampleFrames())
        assertEquals("PASS", result.status)
        assertEquals(3000L, result.ptsFpsX100)
        assertTrue(result.logLine().contains("pts_min_interval_us=33333"))
        assertTrue(result.logLine().contains("pts_max_interval_us=33333"))
        evidence.requireStable()
    }

    @Test
    fun rejectsSlowEncoderAfterAFullSample() {
        val evidence = OutputFrameRateEvidence(30)
        for (frame in 0 until OutputFrameRateEvidence.MIN_STABLE_FRAMES) {
            evidence.record(frame * 40_000L, frame * 40_000_000L)
        }

        val result = evidence.result()
        assertEquals("FAIL", result.status)
        assertThrows(IOException::class.java) { evidence.requireStable() }
    }

    @Test
    fun doesNotCertifyAShortSample() {
        val evidence = OutputFrameRateEvidence(30)
        for (frame in 0 until 30) {
            evidence.record(frame * 33_333L, frame * 33_333_333L)
        }

        assertEquals("INSUFFICIENT_SAMPLE", evidence.result().status)
        assertThrows(IOException::class.java) { evidence.requireStable() }
    }

    @Test
    fun rejectsNonMonotonicTiming() {
        val evidence = OutputFrameRateEvidence(30)
        evidence.record(0L, 0L)
        assertThrows(IOException::class.java) { evidence.record(33_333L, 0L) }
        val pts = OutputFrameRateEvidence(30)
        pts.record(0L, 0L)
        assertThrows(IOException::class.java) { pts.record(0L, 33_333_333L) }
    }

    @Test
    fun acceptsAValidNegativeNanoTimeOrigin() {
        val evidence = OutputFrameRateEvidence(30)
        for (frame in 0 until OutputFrameRateEvidence.MIN_STABLE_FRAMES) {
            evidence.record(frame * 33_333L, -10_000_000_000L + frame * 33_333_333L)
        }
        assertTrue(evidence.result().stable())
    }

    @Test
    fun rejectsInvalidTargetRate() {
        assertThrows(IllegalArgumentException::class.java) { OutputFrameRateEvidence(0) }
        assertThrows(IllegalArgumentException::class.java) { OutputFrameRateEvidence(121) }
    }
}
