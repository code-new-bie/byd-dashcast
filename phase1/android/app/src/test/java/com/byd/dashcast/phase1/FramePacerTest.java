package com.byd.dashcast.phase1;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Monotonic transport pacing tests; they do not claim instrument display FPS. */
public final class FramePacerTest {
    @Test
    public void schedulesWritesAtThirtyFpsWithoutBursting() throws Exception {
        AtomicLong clock = new AtomicLong();
        FramePacer pacer = new FramePacer(30, clock::get,
                nanos -> clock.addAndGet(nanos));

        pacer.awaitNextSlot();
        clock.addAndGet(1_000_000L);
        pacer.awaitNextSlot();
        clock.addAndGet(1_000_000L);
        pacer.awaitNextSlot();

        assertEquals(3L, pacer.scheduledFrames());
        assertEquals(0L, pacer.lateFrames());
        assertTrue(pacer.statistics().contains("transport_start_fps_x100=3000"));
        assertTrue(pacer.statistics().contains("average_transport_interval_us=33333"));
    }

    @Test
    public void reportsSlowAckPathAsLateInsteadOfCatchingUpWithExtraFrames() throws Exception {
        AtomicLong clock = new AtomicLong();
        FramePacer pacer = new FramePacer(30, clock::get,
                nanos -> clock.addAndGet(nanos));

        pacer.awaitNextSlot();
        clock.addAndGet(40_000_000L);
        pacer.awaitNextSlot();
        pacer.awaitNextSlot();

        assertEquals(3L, pacer.scheduledFrames());
        assertEquals(1L, pacer.lateFrames());
        assertTrue(pacer.maxLateNanos() >= 6_000_000L);
        assertTrue(pacer.statistics().contains("late_frames=1"));
    }

    @Test
    public void rejectsInvalidTargetRate() {
        AtomicLong clock = new AtomicLong();
        assertThrows(IllegalArgumentException.class, () -> new FramePacer(0, clock::get,
                nanos -> {}));
        assertThrows(IllegalArgumentException.class, () -> new FramePacer(121, clock::get,
                nanos -> {}));
    }
}
