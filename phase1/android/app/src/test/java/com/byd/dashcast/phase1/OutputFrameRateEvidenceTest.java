package com.byd.dashcast.phase1;

import java.io.IOException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Encoder-output timing evidence tests; they do not claim QNX decode or display timing. */
public final class OutputFrameRateEvidenceTest {
    @Test
    public void acceptsAStableThirtyFpsSample() throws Exception {
        OutputFrameRateEvidence evidence = new OutputFrameRateEvidence(30);
        for (int frame = 0; frame < OutputFrameRateEvidence.MIN_STABLE_FRAMES; frame++) {
            evidence.record(frame * 33_333L, frame * 33_333_333L);
        }

        OutputFrameRateEvidence.Result result = evidence.result();
        assertTrue(result.stable());
        assertEquals(OutputFrameRateEvidence.MIN_STABLE_FRAMES, evidence.sampleFrames());
        assertEquals("PASS", result.status);
        assertEquals(3000L, result.ptsFpsX100);
        assertTrue(result.logLine().contains("pts_min_interval_us=33333"));
        assertTrue(result.logLine().contains("pts_max_interval_us=33333"));
        evidence.requireStable();
    }

    @Test
    public void rejectsSlowEncoderAfterAFullSample() throws Exception {
        OutputFrameRateEvidence evidence = new OutputFrameRateEvidence(30);
        for (int frame = 0; frame < OutputFrameRateEvidence.MIN_STABLE_FRAMES; frame++) {
            evidence.record(frame * 40_000L, frame * 40_000_000L);
        }

        OutputFrameRateEvidence.Result result = evidence.result();
        assertEquals("FAIL", result.status);
        assertThrows(IOException.class, evidence::requireStable);
    }

    @Test
    public void doesNotCertifyAShortSample() throws Exception {
        OutputFrameRateEvidence evidence = new OutputFrameRateEvidence(30);
        for (int frame = 0; frame < 30; frame++) {
            evidence.record(frame * 33_333L, frame * 33_333_333L);
        }

        assertEquals("INSUFFICIENT_SAMPLE", evidence.result().status);
        assertThrows(IOException.class, evidence::requireStable);
    }

    @Test
    public void rejectsNonMonotonicTiming() throws Exception {
        OutputFrameRateEvidence evidence = new OutputFrameRateEvidence(30);
        evidence.record(0L, 0L);
        assertThrows(IOException.class, () -> evidence.record(33_333L, 0L));
        OutputFrameRateEvidence pts = new OutputFrameRateEvidence(30);
        pts.record(0L, 0L);
        assertThrows(IOException.class, () -> pts.record(0L, 33_333_333L));
    }

    @Test
    public void acceptsAValidNegativeNanoTimeOrigin() throws Exception {
        OutputFrameRateEvidence evidence = new OutputFrameRateEvidence(30);
        for (int frame = 0; frame < OutputFrameRateEvidence.MIN_STABLE_FRAMES; frame++) {
            evidence.record(frame * 33_333L, -10_000_000_000L + frame * 33_333_333L);
        }
        assertTrue(evidence.result().stable());
    }

    @Test
    public void rejectsInvalidTargetRate() {
        assertThrows(IllegalArgumentException.class, () -> new OutputFrameRateEvidence(0));
        assertThrows(IllegalArgumentException.class, () -> new OutputFrameRateEvidence(121));
    }
}
