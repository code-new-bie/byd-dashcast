package com.byd.dashcast.phase1;

import java.io.IOException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Encoded reference-chain preservation and PTS validation; no display FPS claim. */
public final class FrameRateGateTest {
    @Test
    public void preservesCodedPicturesWhenSourceTimestampsJitter() throws Exception {
        FrameRateGate gate = new FrameRateGate(30);
        assertTrue(gate.accept(0L));
        assertTrue(gate.accept(10_000L));
        assertTrue(gate.accept(20_000L));
        assertTrue(gate.accept(33_333L));
        assertTrue(gate.accept(40_000L));
        assertTrue(gate.accept(66_666L));
        assertTrue(gate.accept(70_000L));
        assertEquals(7L, gate.inputFrames());
        assertEquals(7L, gate.outputFrames());
        assertEquals(0L, gate.droppedFrames());
        assertTrue(gate.statistics().contains("target_fps=30"));
        assertTrue(gate.statistics().contains("output_pts_fps_x100=3000"));
        assertTrue(gate.statistics().contains("output_interval_us=33333"));
        assertTrue(gate.statistics().contains("retimed=true"));
    }

    @Test
    public void retimesAcceptedPicturesToFixedTargetCadence() throws Exception {
        FrameRateGate gate = new FrameRateGate(30);
        assertEquals(100_000L, gate.outputPts(100_000L));
        assertEquals(133_333L, gate.outputPts(116_000L));
        assertEquals(166_666L, gate.outputPts(150_000L));
        assertEquals(199_999L, gate.outputPts(166_000L));
        assertEquals(233_332L, gate.outputPts(190_000L));
        assertEquals(5L, gate.outputFrames());
        assertTrue(gate.statistics().contains("output_pts_fps_x100=3000"));
    }

    @Test
    public void rejectsNonMonotonicEncoderPts() throws Exception {
        FrameRateGate gate = new FrameRateGate(30);
        gate.accept(100L);
        assertThrows(IOException.class, () -> gate.accept(100L));
        assertThrows(IOException.class, () -> gate.accept(-1L));
    }

    @Test
    public void rejectsInvalidTargetRate() {
        assertThrows(IllegalArgumentException.class, () -> new FrameRateGate(0));
        assertThrows(IllegalArgumentException.class, () -> new FrameRateGate(121));
    }
}
