package com.byd.dashcast.phase1;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/** @author Codex @date 2026/10/19 */
public final class InstrumentDisplayProbeTest {
    @Test
    public void requestRejectsEveryPathEscapeAndInvalidRange() throws Exception {
        assertEquals(44, InstrumentDisplayProbe.request(0, 0, 0, 0).length);
        assertEquals(44, InstrumentDisplayProbe.request(2, 0, 65535, 3).length);
        for (int[] row : new int[][] {{0, 1, 0, 0}, {0, 0, 0, 1}, {1, 1, 2, 0},
                {1, 0, 0, 0}, {1, 0, 9, 0}, {2, -1, 1, 0}, {2, 1, -1, 0},
                {3, 1, 65536, 0}, {4, 0, 0, 0}, {2, 1, 1, 4}}) {
            assertThrows(IOException.class, () -> InstrumentDisplayProbe.request(row[0], row[1], row[2], row[3]));
        }
    }

    @Test
    public void collectsDisplayWindowAndStreamWithoutStartOrStop() throws Exception {
        FakeTransport fake = new FakeTransport();
        fake.display = "dpy-2\n win-3 ctx-0 ssn-0 pid=100(rc_hmi.exe)\n"
                + " win-4 ctx-0 ssn-0 pid=200(instrument-receiver)\n";
        List<String> logs = new ArrayList<>();
        InstrumentDisplayProbe probe = new InstrumentDisplayProbe(new AtomicBoolean(), logs::add, () -> 0);
        probe.negotiate(fake);
        probe.run(fake);
        assertTrue(logs.stream().anyMatch(s -> s.contains("owner_pid=100 object=3 pages=1")));
        assertTrue(logs.stream().anyMatch(s -> s.contains("kind=3 owner_pid=100 object=5 pages=1")));
        assertTrue(logs.stream().anyMatch(s -> s.contains("COLLECTION_ENDED")));
        assertTrue(logs.stream().noneMatch(s -> s.contains("PASS")));
        assertTrue(fake.operations.stream().allMatch(value -> value == 9));
        assertFalse(fake.closed);
    }

    @Test
    public void splitUtf8AcrossPagesIsDecodedOnlyAfterCollection() throws Exception {
        FakeTransport fake = new FakeTransport();
        fake.display = "x".repeat(4095) + "仪表";
        List<String> logs = new ArrayList<>();
        InstrumentDisplayProbe probe = new InstrumentDisplayProbe(new AtomicBoolean(), logs::add, () -> 0);
        probe.negotiate(fake);
        probe.run(fake);
        assertTrue(logs.stream().anyMatch(s -> s.contains("仪表")));
    }

    @Test
    public void truncationNeverClaimsCompleteEvidence() throws Exception {
        FakeTransport fake = new FakeTransport();
        fake.display = "x".repeat(17000);
        List<String> logs = new ArrayList<>();
        InstrumentDisplayProbe probe = new InstrumentDisplayProbe(new AtomicBoolean(), logs::add, () -> 0);
        probe.negotiate(fake);
        probe.run(fake);
        assertTrue(logs.stream().anyMatch(s -> s.contains("truncated=true")));
        assertTrue(logs.stream().anyMatch(s -> s.contains("partial=1")));
    }

    @Test
    public void rejectsMalformedHeaderSchemaEchoAndFlags() throws Exception {
        for (int mode = 1; mode <= 7; mode++) {
            FakeTransport fake = new FakeTransport();
            fake.corruption = mode;
            InstrumentDisplayProbe probe = new InstrumentDisplayProbe(new AtomicBoolean(), s -> { }, () -> 0);
            assertThrows(IOException.class, () -> probe.negotiate(fake));
        }
    }

    @Test
    public void missingOrDeniedFilesAreNotReportedAsPass() throws Exception {
        FakeTransport fake = new FakeTransport();
        fake.allMissing = true;
        List<String> logs = new ArrayList<>();
        InstrumentDisplayProbe probe = new InstrumentDisplayProbe(new AtomicBoolean(), logs::add, () -> 0);
        probe.negotiate(fake);
        probe.run(fake);
        assertTrue(logs.stream().anyMatch(s -> s.contains("files=0 unavailable=8")));
        assertTrue(logs.stream().noneMatch(s -> s.contains("PASS")));
    }

    @Test
    public void readFailureAndUnknownCloseAreTerminal() throws Exception {
        for (int code : new int[] {3, 4, 5, 6}) {
            FakeTransport fake = new FakeTransport();
            InstrumentDisplayProbe probe = new InstrumentDisplayProbe(new AtomicBoolean(), s -> { }, () -> 0);
            probe.negotiate(fake);
            fake.failure = code;
            assertThrows(IOException.class, () -> probe.step(fake));
        }
    }

    @Test
    public void cancellationAndClockBudgetDoNotIssueFurtherRequests() throws Exception {
        FakeTransport fake = new FakeTransport();
        AtomicBoolean cancel = new AtomicBoolean();
        long[] clock = {0};
        List<String> logs = new ArrayList<>();
        InstrumentDisplayProbe probe = new InstrumentDisplayProbe(cancel, logs::add, () -> clock[0]);
        probe.negotiate(fake);
        cancel.set(true);
        assertThrows(IOException.class, () -> probe.step(fake));
        cancel.set(false);
        clock[0] = 10_000_000_000L;
        probe.step(fake);
        assertEquals(1, fake.operations.size());
        assertTrue(logs.stream().anyMatch(s -> s.contains("BUDGET_LIMIT")));
        assertThrows(IOException.class, () -> probe.negotiate(fake));
    }

    @Test
    public void duplicateWindowsAreReadOnceAndIdOverflowFails() throws Exception {
        FakeTransport fake = new FakeTransport();
        fake.display = " win-3 ctx-0 pid=100(hmi)\n win-3 ctx-0 pid=100(hmi)\n";
        InstrumentDisplayProbe probe = new InstrumentDisplayProbe(new AtomicBoolean(), s -> { }, () -> 0);
        probe.negotiate(fake);
        probe.run(fake);
        assertEquals(1L, fake.resources.stream().filter(s -> s.equals("2/100/3/0")).count());
        FakeTransport overflow = new FakeTransport();
        overflow.display = " win-3 ctx-0 pid=999999999999999(hmi)\n";
        InstrumentDisplayProbe bad = new InstrumentDisplayProbe(new AtomicBoolean(), s -> { }, () -> 0);
        bad.negotiate(overflow);
        bad.step(overflow);
        assertThrows(IOException.class, () -> bad.step(overflow));
    }

    @Test
    public void activeFrameSessionCanEndWithPartialReadback() throws Exception {
        List<String> logs = new ArrayList<>();
        InstrumentDisplayProbe probe = new InstrumentDisplayProbe(new AtomicBoolean(), logs::add, () -> 0);
        probe.negotiate(new FakeTransport());
        probe.end();
        assertTrue(logs.stream().anyMatch(s -> s.contains("VIDEO_ENDED_BEFORE_COLLECTION")));
    }

    static final class FakeTransport implements NoAuthVideoTransport {
        private byte[] reply = new byte[0];
        private int offset;
        private String display = "dpy-2\n";
        private int corruption;
        private int failure;
        private boolean allMissing;
        private boolean closed;
        private final List<Integer> operations = new ArrayList<>();
        private final List<String> resources = new ArrayList<>();

        @Override
        public void write(byte[] request) throws IOException {
            ByteBuffer wire = ByteBuffer.wrap(request).order(ByteOrder.BIG_ENDIAN);
            wire.position(4);
            operations.add(wire.getInt());
            wire.position(28);
            int kind = wire.getInt();
            int owner = wire.getInt();
            int id = wire.getInt();
            int page = wire.getInt();
            resources.add(kind + "/" + owner + "/" + id + "/" + page);
            String text = kind == 1 ? display : kind == 2 ? "id string = Kanzi\nstream = str-5\n" : "format = RGBA8888\n";
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            int status = kind == 0 ? 0 : failure != 0 ? failure
                    : allMissing || kind == 1 && id != 2 ? 2 : 0;
            int start = Math.min(data.length, page * 4096);
            byte[] part = kind == 0 || status != 0 ? new byte[0]
                    : Arrays.copyOfRange(data, start, Math.min(data.length, start + 4096));
            int flags = kind == 0 ? 1 : status != 0 ? 0 : part.length < 4096 ? 1 : page == 3 ? 2 : 0;
            ByteBuffer response = ByteBuffer.allocate(64 + part.length).order(ByteOrder.BIG_ENDIAN);
            response.putInt(corruption == 1 ? 0 : 0x44435631).putInt(0x89);
            response.putInt(corruption == 2 ? 1000000 : 40 + part.length).putInt(0).putInt(0).putInt(0);
            response.putInt(corruption == 3 ? 2 : 1).putInt(0x46315031).putInt(status);
            response.putInt(kind).putInt(corruption == 4 ? owner + 1 : owner).putInt(id).putInt(page);
            response.putInt(corruption == 5 ? part.length + 1 : part.length);
            response.putInt(corruption == 6 ? 3 : flags).putInt(corruption == 7 ? 1 : 0).put(part);
            reply = response.array();
            offset = 0;
        }

        @Override
        public byte[] readExact(int count) throws IOException {
            assertTrue(count <= 80);
            if (offset + count > reply.length) {
                throw new IOException("truncated");
            }
            byte[] result = Arrays.copyOfRange(reply, offset, offset + count);
            offset += count;
            return result;
        }

        @Override
        public void beginCleanup() {
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
