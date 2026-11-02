package com.byd.dashcast.phase1;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Offline encoder-control tests; not execution of Android EGL or a hardware codec. */
public final class SurfaceEncoderPumpTest {
    @Test
    public void drainsBeforeEveryInputAndPreservesPacedMonotonicPts() throws Exception {
        FakeEncoder backend = new FakeEncoder();
        assertEquals(30, SurfaceEncoderPump.run(1, 30, backend.cancelled, backend));
        assertEquals(30, backend.outputFrames());
        assertEquals(1, backend.eosSignals);
        assertEquals(Long.valueOf(1_000_000L), backend.pts.get(0));
        assertEquals(Long.valueOf(1_966_666L), backend.pts.get(29));
        for (int index = 0; index < backend.times.size(); index++) {
            long due = index * 1_000_000_000L / 30;
            assertTrue(backend.times.get(index) >= due);
            assertTrue(backend.times.get(index) - due < 1_000_000L);
        }
    }

    @Test
    public void cancelledBeforeInputStillDrainsAndSignalsEosOnce() throws Exception {
        FakeEncoder backend = new FakeEncoder();
        backend.cancelled.set(true);
        assertEquals(0, SurfaceEncoderPump.run(1, 30, backend.cancelled, backend));
        assertEquals(1, backend.eosSignals);
        assertEquals(0, backend.pts.size());
    }

    @Test
    public void cancellationStopsInputButCountsAlreadyQueuedOutput() throws Exception {
        FakeEncoder backend = new FakeEncoder();
        backend.cancelAfter = 3;
        assertEquals(3, SurfaceEncoderPump.run(1, 30, backend.cancelled, backend));
        assertEquals(3, backend.outputFrames());
        assertEquals(1, backend.eosSignals);
    }

    @Test
    public void missingOutputCannotBecomeSuccessfulEndOfStream() {
        FakeEncoder backend = new FakeEncoder();
        backend.dropOutput = true;
        IOException error = assertThrows(IOException.class,
                () -> SurfaceEncoderPump.run(1, 30, backend.cancelled, backend));
        assertTrue(error.getMessage().contains("Incomplete encoder output"));
        assertEquals(1, backend.eosSignals);
    }

    @Test
    public void eosDrainHasAFiniteDeadline() {
        FakeEncoder backend = new FakeEncoder();
        backend.neverEos = true;
        IOException error = assertThrows(IOException.class,
                () -> SurfaceEncoderPump.run(1, 30, backend.cancelled, backend));
        assertTrue(error.getMessage().contains("EOS timeout"));
        assertTrue(backend.clock < 7_000_000_000L);
        assertEquals(1, backend.eosSignals);
    }

    @Test
    public void prematureOutputEosIsRejectedRatherThanZeroFrameSuccess() {
        FakeEncoder backend = new FakeEncoder();
        backend.prematureEos = true;
        assertThrows(IOException.class, () -> SurfaceEncoderPump.run(1, 30, backend.cancelled, backend));
        assertEquals(0, backend.eosSignals);
        assertEquals(0, backend.pts.size());
    }

    @Test
    public void rendererFailurePropagatesWithoutSubmittingAdditionalFrames() {
        FakeEncoder backend = new FakeEncoder();
        backend.failRender = true;
        assertThrows(IOException.class, () -> SurfaceEncoderPump.run(1, 30, backend.cancelled, backend));
        assertEquals(0, backend.pts.size());
        assertEquals(0, backend.eosSignals);
    }

    @Test
    public void stalledInputWorkHasAFiniteDeadline() {
        FakeEncoder backend = new FakeEncoder();
        backend.workDelay = 12_000_000_000L;
        IOException error = assertThrows(IOException.class,
                () -> SurfaceEncoderPump.run(1, 30, backend.cancelled, backend));
        assertTrue(error.getMessage().contains("input stalled"));
        assertEquals(1, backend.pts.size());
    }

    @Test
    public void supportsUpperBoundWithoutPtsOverflow() throws Exception {
        FakeEncoder backend = new FakeEncoder();
        assertEquals(3600, SurfaceEncoderPump.run(60, 60, backend.cancelled, backend));
        assertEquals(Long.valueOf(60_983_333L), backend.pts.get(3599));
        assertEquals(1, backend.eosSignals);
    }

    @Test
    public void validatesDurationFpsAndRequiredBoundaries() {
        FakeEncoder backend = new FakeEncoder();
        for (int seconds : new int[] {0, 61, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> SurfaceEncoderPump.run(seconds, 30, backend.cancelled, backend));
        }
        for (int fps : new int[] {0, 61}) {
            assertThrows(IllegalArgumentException.class,
                    () -> SurfaceEncoderPump.run(1, fps, backend.cancelled, backend));
        }
        assertThrows(NullPointerException.class, () -> SurfaceEncoderPump.run(1, 30, null, backend));
        assertThrows(NullPointerException.class, () -> SurfaceEncoderPump.run(1, 30, backend.cancelled, null));
    }

    private static final class FakeEncoder implements SurfaceEncoderPump.Backend {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final List<Long> pts = new ArrayList<>();
        private final List<Long> times = new ArrayList<>();
        private long clock;
        private long workDelay;
        private int received;
        private int eosSignals;
        private int cancelAfter = Integer.MAX_VALUE;
        private boolean drained;
        private boolean dropOutput;
        private boolean neverEos;
        private boolean prematureEos;
        private boolean failRender;

        @Override
        public long nanoTime() {
            return clock;
        }

        @Override
        public boolean drain() {
            drained = true;
            received = dropOutput ? 0 : pts.size();
            if (pts.size() >= cancelAfter) {
                cancelled.set(true);
            }
            return prematureEos || eosSignals > 0 && !neverEos;
        }

        @Override
        public int outputFrames() {
            return received;
        }

        @Override
        public void render(int frame, long ptsMicros) throws IOException {
            assertTrue("Output must be drained before each surface swap", drained);
            if (failRender) {
                throw new IOException("Fixture render failure");
            }
            drained = false;
            assertEquals(pts.size(), frame);
            times.add(clock);
            pts.add(ptsMicros);
            clock += workDelay;
        }

        @Override
        public void signalEndOfInput() {
            assertEquals(0, eosSignals++);
        }

        @Override
        public void pause(long nanos) {
            assertTrue(nanos > 0 && nanos <= 1_000_000L);
            clock += nanos;
        }
    }
}
