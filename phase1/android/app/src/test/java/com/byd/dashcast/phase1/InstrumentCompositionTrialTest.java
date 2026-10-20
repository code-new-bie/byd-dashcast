package com.byd.dashcast.phase1;

import org.junit.Test;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Deterministic same-stream control tests; not QNX physical composition evidence. */
public final class InstrumentCompositionTrialTest {
    @Test
    public void firstPictureStartsOneLeaseAndExpiryStopsWithoutRenewal() throws Exception {
        Model model = new Model();
        InstrumentCompositionTrial trial = model.trial();
        trial.prepare(model);
        assertEquals(List.of(0), model.operations);
        trial.activate(model);
        trial.tick(model);
        model.now += 5_000_000_000L;
        model.state = 2;
        trial.tick(model);
        trial.restore(model);
        assertTrue(model.stopped);
        assertEquals(1, model.operations.stream().filter(i -> i == 1).count());
        assertFalse(model.operations.contains(2));
        assertThrows(IOException.class, () -> trial.activate(model));
    }

    @Test
    public void earlyStopRestoresOnlyOwnedNonce() throws Exception {
        Model model = new Model();
        InstrumentCompositionTrial trial = model.trial();
        trial.prepare(model);
        trial.activate(model);
        trial.restore(model);
        trial.restore(model);
        assertEquals(List.of(0, 1, 2, 0), model.operations);
        assertFalse(model.stopped);
    }

    @Test
    public void preexistingLeaseOrPendingRequestNeverSendsStart() throws Exception {
        for (boolean pending : new boolean[] {false, true}) {
            Model model = new Model();
            model.state = pending ? 0 : 1;
            model.pending = pending;
            assertThrows(IOException.class, () -> model.trial().prepare(model));
            assertEquals(List.of(0), model.operations);
        }
    }

    @Test
    public void hmiReplacementOrMissingDrawingNeverReportsSuccess() throws Exception {
        for (boolean replacement : new boolean[] {false, true}) {
            Model model = new Model();
            InstrumentCompositionTrial trial = model.trial();
            trial.prepare(model);
            trial.activate(model);
            trial.tick(model);
            model.now += 5_000_000_000L;
            model.state = 2;
            if (replacement) { model.pid++; } else { model.skips = 0; }
            assertThrows(IOException.class, () -> trial.tick(model));
            assertFalse(model.stopped);
        }
    }

    @Test
    public void sevenSecondBoundAndFailedRestoreAreNotSuccess() throws Exception {
        Model model = new Model();
        InstrumentCompositionTrial trial = model.trial();
        trial.prepare(model);
        trial.activate(model);
        model.now += 7_000_000_001L;
        assertThrows(IOException.class, () -> trial.tick(model));
        model.error = 3;
        assertThrows(IOException.class, () -> trial.restore(model));
        assertFalse(model.stopped);
    }

    @Test
    public void busySnapshotDoesNotCauseStartReplay() throws Exception {
        Model model = new Model();
        InstrumentCompositionTrial trial = model.trial();
        trial.prepare(model);
        model.error = 9;
        trial.activate(model);
        trial.tick(model);
        model.error = 0;
        model.now += 100_000_000L;
        trial.tick(model);
        model.now += 5_000_000_000L;
        model.state = 2;
        trial.tick(model);
        assertTrue(model.stopped);
        assertEquals(1, model.operations.stream().filter(i -> i == 1).count());
    }

    private static final class Model implements NoAuthVideoTransport {
        final List<Integer> operations = new ArrayList<>();
        long now = 100_000_000_000L;
        int state;
        int nonce;
        int pid = 123;
        int skips = 2;
        int error;
        boolean pending;
        boolean stopped;
        byte[] response;
        int offset;

        InstrumentCompositionTrial trial() {
            return new InstrumentCompositionTrial(s -> {}, () -> now, 77, () -> stopped = true);
        }
        @Override public void write(byte[] bytes) {
            ByteBuffer request = ByteBuffer.wrap(bytes);
            assertEquals(10, request.getInt(4));
            int op = request.getInt(28);
            operations.add(op);
            if (op == 1) {
                nonce = request.getInt(32);
                assertEquals(77, nonce);
                assertEquals(5000, request.getInt(36));
                state = 1;
            } else if (op == 2) {
                assertEquals(nonce, request.getInt(32));
                state = 2;
            }
            int[] control = new int[32];
            control[0] = 0x31474244; control[1] = 1; control[2] = 128;
            control[3] = 2; control[8] = pending ? 0 : 2; control[9] = state;
            control[10] = pid; control[12] = (int)(now / 1_000_000_000L);
            control[14] = control[12] + 5; control[18] = 1; control[21] = nonce;
            control[16] = nonce == 0 ? 0 : skips; control[17] = control[16];
            ByteBuffer output = ByteBuffer.allocate(168);
            output.putInt(0x44435631).putInt(0x8a).putInt(144).putInt(0).putInt(0).putInt(0);
            output.putInt(1).putInt(0x42475031).putInt(op).putInt(error);
            for (int value : control) { output.putInt(value); }
            response = output.array(); offset = 0;
        }
        @Override public byte[] readExact(int size) {
            byte[] part = java.util.Arrays.copyOfRange(response, offset, offset + size);
            offset += size;
            return part;
        }
        @Override public void beginCleanup() {}
        @Override public void close() {}
    }
}
