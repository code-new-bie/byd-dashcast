package com.byd.dashcast.phase1;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/** Deterministic protocol/state tests, not Android/QNX/visual execution. */
public final class InstrumentBackgroundPocTest {
    @Test
    public void requestIsFixedFiveSecondsAndNoVideoStart() throws Exception {
        byte[] packet = InstrumentBackgroundPoc.request(1, 77);
        assertArrayEquals(new int[] {0x44435631, 10, 16, 0, 0, 0, 1, 1, 77, 5000}, words(packet));
        assertArrayEquals(new int[] {0x44435631, 10, 16, 0, 0, 0, 1, 0, 0, 0},
                words(InstrumentBackgroundPoc.request(0, 0)));
    }

    @Test
    public void invalidRequestRangesRejected() {
        for (int[] row : new int[][] {{-1, 0}, {3, 1}, {0, 1}, {1, 0}, {2, -1}}) {
            assertThrows(IOException.class, () -> InstrumentBackgroundPoc.request(row[0], row[1]));
        }
    }

    @Test
    public void independentExpiryObservedWithoutRenewalOrExtraStop() throws Exception {
        Model model = new Model();
        InstrumentBackgroundPoc probe = probe(model);
        probe.run(model);
        assertEquals(1, model.operations.stream().filter(x -> x == 1).count());
        assertFalse(model.operations.contains(2));
        assertEquals(1, model.maxActive);
        assertTrue(model.logs.stream().anyMatch(x -> x.contains("BG_POC_NATIVE_RESTORED")));
        assertThrows(IOException.class, () -> probe.run(model));
    }

    @Test
    public void missingHooksDoesNotReportFeaturePass() {
        Model model = new Model();
        model.draws = false;
        IOException error = assertThrows(IOException.class, () -> probe(model).run(model));
        assertTrue(error.getMessage().contains("DRAW_NOT_OBSERVED"));
        assertEquals(1, model.operations.stream().filter(x -> x == 1).count());
    }

    @Test
    public void preexistingActiveNeverSendsStartOrRestore() {
        Model model = new Model();
        model.control[9] = 1;
        model.deadline = model.now + 5_000_000_000L;
        assertThrows(IOException.class, () -> probe(model).run(model));
        assertEquals(Arrays.asList(0), model.operations);
    }

    @Test
    public void notReadyTimesOutWithoutSendingStart() {
        Model model = new Model();
        model.serverError = 3;
        assertThrows(IOException.class, () -> probe(model).run(model));
        assertFalse(model.operations.contains(1));
        assertFalse(model.operations.contains(2));
        assertTrue(model.operations.size() <= 32);
    }

    @Test
    public void cancellationSendsOnlyOwnedRestoreWithoutReplayingStart() {
        Model model = new Model();
        model.cancelAfterStart = true;
        assertThrows(IOException.class, () -> probe(model).run(model));
        assertTrue(model.cleanup);
        assertEquals(1, model.operations.stream().filter(x -> x == 1).count());
        assertEquals(1, model.operations.stream().filter(x -> x == 2).count());
    }

    @Test
    public void changedHmiEpochAbortsAndAttemptsOwnedRestore() {
        Model model = new Model();
        model.changeEpoch = true;
        IOException error = assertThrows(IOException.class, () -> probe(model).run(model));
        assertTrue(error.getMessage().contains("CHANGED_OR_UNAVAILABLE"));
        assertTrue(model.operations.contains(2));
    }

    @Test
    public void malformedEnvelopeAndSnapshotRejected() throws Exception {
        Model model = new Model();
        model.write(InstrumentBackgroundPoc.request(0, 0));
        byte[] header = model.readExact(24);
        InstrumentBackgroundPoc.validateHeader(header);
        for (int offset : new int[] {0, 7, 11, 15, 19, 23}) {
            byte[] bad = header.clone(); bad[offset] ^= 1;
            assertThrows(IOException.class, () -> InstrumentBackgroundPoc.validateHeader(bad));
        }
        byte[] body = model.readExact(80);
        byte[] tail = model.readExact(64);
        byte[] full = new byte[144];
        System.arraycopy(body, 0, full, 0, 80);
        System.arraycopy(tail, 0, full, 80, 64);
        InstrumentBackgroundPoc.parseReply(full, 0);
        for (int word : new int[] {0, 1, 2, 4, 5, 6}) {
            byte[] bad = full.clone(); bad[word * 4] ^= 1;
            assertThrows(IOException.class, () -> InstrumentBackgroundPoc.parseReply(bad, 0));
        }
        assertThrows(IOException.class, () -> InstrumentBackgroundPoc.parseReply(new byte[145], 0));
    }

    @Test
    public void uncertainStartSnapshotQueriesInsteadOfRetryingStart() throws Exception {
        Model model = new Model();
        model.startSnapshotBusy = true;
        probe(model).run(model);
        assertEquals(1, model.operations.stream().filter(x -> x == 1).count());
    }

    @Test
    public void frozenHmiNeverBecomesSuccessAndRequestCountIsBounded() {
        Model model = new Model();
        model.freeze = true;
        assertThrows(IOException.class, () -> probe(model).run(model));
        assertTrue(model.operations.size() <= 101);
        assertTrue(model.operations.contains(2));
    }

    private static InstrumentBackgroundPoc probe(Model model) {
        return new InstrumentBackgroundPoc(model.cancelled, model.logs::add, 77, () -> model.now, delta -> {
            model.now += delta;
            if (model.cancelAfterStart && model.operations.contains(1)) {
                model.cancelled.set(true);
            }
        });
    }

    private static int[] words(byte[] bytes) {
        int[] values = new int[bytes.length / 4];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < values.length; i++) {
            values[i] = buffer.getInt();
        }
        return values;
    }

    private static final class Model implements NoAuthVideoTransport {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final List<Integer> operations = new ArrayList<>();
        final List<String> logs = new ArrayList<>();
        final int[] control = new int[32];
        byte[] reply;
        int offset;
        int serverError;
        int maxActive;
        long now = 100_000_000_000L;
        long deadline;
        boolean draws = true;
        boolean cleanup;
        boolean cancelAfterStart;
        boolean changeEpoch;
        boolean freeze;
        boolean startSnapshotBusy;

        Model() {
            control[0] = 0x31474244; control[1] = 1; control[2] = 128;
            control[10] = 123; control[18] = 1;
        }

        @Override
        public void write(byte[] data) {
            int[] request = words(data);
            assertEquals(10, request[1]);
            int operation = request[7];
            operations.add(operation);
            if (operation == 1) {
                control[3] += 2; control[8] = control[3]; control[9] = 1; control[21] = request[8];
                deadline = now + 5_000_000_000L;
            } else if (operation == 2) {
                control[9] = 2;
            }
            if (operation == 0 && control[9] == 1 && !freeze) {
                if (now >= deadline) {
                    control[9] = 2; control[25] = control[24];
                } else if (draws) {
                    control[16]++; control[17]++;
                }
                if (changeEpoch) { control[18]++; }
            }
            if (control[9] == 1) {
                maxActive = 1;
                control[14] = (int)(deadline / 1_000_000_000L);
                control[15] = (int)(deadline % 1_000_000_000L);
            }
            control[12] = (int)(now / 1_000_000_000L);
            control[13] = (int)(now % 1_000_000_000L);
            control[24]++;
            int error = operation == 1 && startSnapshotBusy ? 9 : serverError;
            ByteBuffer buffer = ByteBuffer.allocate(168).order(ByteOrder.BIG_ENDIAN);
            buffer.putInt(0x44435631).putInt(0x8a).putInt(144).putInt(0).putInt(0).putInt(0);
            buffer.putInt(1).putInt(0x42475031).putInt(operation).putInt(error);
            for (int value : control) { buffer.putInt(value); }
            reply = buffer.array(); offset = 0;
        }

        @Override
        public byte[] readExact(int count) {
            assertTrue(count <= 80);
            byte[] piece = Arrays.copyOfRange(reply, offset, offset + count);
            offset += count;
            return piece;
        }

        @Override
        public void beginCleanup() { cleanup = true; }

        @Override
        public void close() { }
    }
}
