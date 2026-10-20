package com.byd.dashcast.phase1;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.byd.dashcast.phase1.NoAuthVideoProtocolTest.*;
import static org.junit.Assert.*;

/** Independent scripted binary receiver; tests cancellation/error/cleanup, not QNX display. */
public final class NoAuthVideoClientTest {
    @Test
    public void failedBackgroundRestoreStillSendsMediaStopButCannotClaimCleanSession() throws Exception {
        Link link = new Link().offer().background(0, 0, 0).ack(7, 0, 0, 0, 0, 0)
                .ack(1, 0, 0, 1, 0, 0).ack(2, 1, 0, 7, 1, 0).background(1, 1, 77)
                .ack(3, 0, 0, 0, 1, 0);
        link.failBackgroundRestore = true;
        NoAuthVideoClient client = new NoAuthVideoClient((p, f) -> link,
                new AtomicBoolean(), s -> {}, () -> 0L, true);
        client.enableCompositionTrial(77, () -> {});
        client.begin();
        client.frame(IDR, 0);
        assertThrows(IOException.class, client::close);
        assertFalse(client.cleanupConfirmed());
        assertTrue(link.closed && link.reads.isEmpty());
        assertEquals(3, link.writes.get(link.writes.size() - 1)[7]);
    }

    @Test
    public void idleMaintenanceKeepsOneRealFrameAliveAndStillConfirmsStop() throws Exception {
        Link link = new Link().ready().ack(2, 1, 0, 7, 1, 0);
        for (int i = 0; i < 6; i++) {
            link.ack(8, 0, 0, 7, 1, 0);
        }
        link.ack(3, 0, 0, 0, 1, 0);
        long[] clock = {0};
        NoAuthVideoClient client = new NoAuthVideoClient((p, f) -> link,
                new AtomicBoolean(), s -> {}, () -> clock[0], true);
        client.begin();
        client.frame(IDR, 0);
        for (int i = 1; i <= 6; i++) {
            clock[0] = i * 1_000_000_000L;
            client.maintain();
            client.maintain();
        }
        assertTrue(client.statistics().contains("acknowledged=1"));
        client.close();
        assertTrue(client.cleanupConfirmed());
        assertEquals(6, link.writes.stream().filter(p -> p[7] == 8).count());
        assertEquals(1, link.writes.stream().filter(p -> p[7] == 2).count());
        assertTrue(link.reads.isEmpty());
    }

    @Test
    public void compositionIsContinuousOnlyAndUsesDistinctFixedPreset() throws Exception {
        NoAuthVideoClient shortClient = client(new Link(), new AtomicBoolean(), new ArrayList<>());
        assertThrows(IOException.class, () -> shortClient.enableCompositionTrial(77, () -> {}));
        int[] preset = new int[6];
        java.nio.ByteBuffer.wrap(NoAuthVideoProtocol.start(true), 24, 24).asIntBuffer().get(preset);
        assertArrayEquals(new int[] {0, 0, 1920, 720, 30, 1}, preset);
    }

    @Test
    public void f1IsExplicitShortOnlyAndChecksCapabilityBeforeHello() throws Exception {
        Link link = new Link().offer();
        link.reads.add(header(0x89, 40, 0, 0));
        link.reads.add(f1State(0, 0, 0, 0, 1));
        link.ack(7, 0, 0, 0, 0, 0).ack(1, 0, 0, 1, 0, 0)
                .ack(2, 1, 0, 7, 1, 0);
        link.reads.add(header(0x89, 40, 0, 0));
        link.reads.add(f1State(1, 0, 1, 2, 0));
        link.ack(3, 0, 0, 0, 1, 0);
        NoAuthVideoClient client = client(link, new AtomicBoolean(), new ArrayList<>());
        client.enableDisplayReadback();
        client.begin();
        assertThrows(IOException.class, client::enableDisplayReadback);
        client.frame(IDR, 0);
        client.close();
        assertTrue(client.cleanupConfirmed());
        int[] expected = {9, 7, 1, 2, 9, 3};
        assertEquals(expected.length, link.writes.size());
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], link.writes.get(i)[7]);
        }
        NoAuthVideoClient continuous = new NoAuthVideoClient((p, f) -> new Link(),
                new AtomicBoolean(), s -> { }, true);
        assertThrows(IOException.class, continuous::enableDisplayReadback);
    }

    @Test
    public void oldReceiverF1RejectionNeverSendsHelloStartOrVideo() throws Exception {
        Link link = new Link().offer();
        NoAuthVideoClient client = client(link, new AtomicBoolean(), new ArrayList<>());
        client.enableDisplayReadback();
        assertThrows(IOException.class, client::begin);
        assertEquals(1, link.writes.size());
        assertEquals(9, link.writes.get(0)[7]);
        assertTrue(link.closed && client.cleanupConfirmed());
    }

    private static byte[] f1State(int kind, int owner, int id, int status, int flags) {
        return java.nio.ByteBuffer.allocate(40).putInt(1).putInt(0x46315031).putInt(status)
                .putInt(kind).putInt(owner).putInt(id).putInt(0).putInt(0).putInt(flags).putInt(0).array();
    }

    @Test
    public void continuousClientNegotiatesVersionTwoAndStopConfirmsCleanup() throws Exception {
        Link link = new Link().ready().ack(3, 0, 0, 0, 0, 0);
        NoAuthVideoClient client = new NoAuthVideoClient((port, flag) -> link,
                new AtomicBoolean(), message -> {}, () -> 0L, true);
        client.begin();
        assertEquals(2, link.writes.get(0)[39]);
        assertFalse(client.cleanupConfirmed());
        client.close();
        assertTrue(client.cleanupConfirmed());
    }

    @Test
    public void continuousVersionRejectionNeverStartsMediaOrFallsBackToShortProtocol() {
        Link link = new Link().offer();
        link.reads.add(header(0x87, 16, 0, 0));
        link.reads.add(state(0, 0, 0));
        link.reads.peekLast()[3] = 1;
        NoAuthVideoClient client = new NoAuthVideoClient((port, flag) -> link,
                new AtomicBoolean(), message -> {}, true);
        assertThrows(IOException.class, client::begin);
        assertEquals(1, link.writes.size());
        assertEquals(7, link.writes.get(0)[7]);
        assertTrue(link.closed && client.cleanupConfirmed());
    }
    private static final class Link implements NoAuthVideoTransport {
        final ArrayDeque<byte[]> reads = new ArrayDeque<>();
        final List<byte[]> writes = new ArrayList<>();
        boolean cleanup;
        boolean closed;
        boolean failStop;
        boolean failBackgroundRestore;

        Link background(int operation, int status, int nonce) {
            reads.add(header(0x8a, 144, 0, 0));
            java.nio.ByteBuffer body = java.nio.ByteBuffer.allocate(144);
            body.putInt(1).putInt(0x42475031).putInt(operation).putInt(0);
            int[] words = new int[32];
            words[0] = 0x31474244; words[1] = 1; words[2] = 128;
            words[9] = status; words[10] = 123; words[18] = 1; words[21] = nonce;
            words[14] = status == 1 ? 5 : 0;
            for (int value : words) { body.putInt(value); }
            reads.add(java.util.Arrays.copyOfRange(body.array(), 0, 80));
            reads.add(java.util.Arrays.copyOfRange(body.array(), 80, 144));
            return this;
        }

        Link offer() { reads.add(header(0x85, 80, 0, 0)); reads.add(new byte[80]); return this; }
        Link ack(int type, int sequence, long pts, int flags, int count, long lastPts) {
            reads.add(header(type | 0x80, 16, sequence, pts)); reads.add(state(flags, count, lastPts)); return this;
        }
        Link ready() { return offer().ack(7, 0, 0, 0, 0, 0).ack(1, 0, 0, 1, 0, 0); }

        @Override public void write(byte[] data) throws IOException {
            if (failStop && data[7] == 3) { throw new IOException("test link lost"); }
            if (failBackgroundRestore && data[7] == 10 && data[31] == 2) {
                throw new IOException("test background restore unavailable");
            }
            writes.add(data.clone());
        }
        @Override public byte[] readExact(int count) throws IOException {
            if (reads.isEmpty()) { throw new IOException("test EOF"); }
            byte[] reply = reads.removeFirst();
            assertEquals(count, reply.length);
            return reply;
        }
        @Override public void beginCleanup() { cleanup = true; }
        @Override public void close() { closed = true; }
    }

    private NoAuthVideoClient client(Link link, AtomicBoolean cancelled, List<String> logs) {
        return new NoAuthVideoClient((port, flag) -> {
            assertEquals(31707, port);
            assertSame(cancelled, flag);
            return link;
        }, cancelled, logs::add, () -> 0L);
    }

    @Test
    public void happyPathUsesOneNoAuthStreamAndReportsWindowNotDecodedFrames() throws Exception {
        Link link = new Link().ready().ack(2, 1, 0, 7, 1, 0).ack(2, 2, 33, 23, 2, 33)
                .ack(8, 0, 0, 23, 2, 33).ack(3, 0, 0, 0, 2, 0);
        List<String> logs = new ArrayList<>();
        try (NoAuthVideoClient client = client(link, new AtomicBoolean(), logs)) {
            client.begin();
            client.frame(IDR, 1000000);
            client.frame(P, 1033000);
            client.requireWindowReport();
            assertTrue(client.statistics().contains("acknowledged=2"));
        }
        assertTrue(link.closed && link.cleanup && link.reads.isEmpty());
        assertEquals(6, link.writes.size());
        assertEquals(7, link.writes.get(0)[7]);
        assertEquals(3, link.writes.get(5)[7]);
        assertTrue(logs.stream().anyMatch(line -> line.startsWith("STOP_CLEANUP_REPORTED")));
        assertTrue(logs.stream().anyMatch(line -> line.contains("NOT observed motion/decoded-frame count")));
    }

    @Test
    public void cancelStillAttemptsSameStreamStopExactlyOnce() throws Exception {
        Link link = new Link().ready().ack(3, 0, 0, 0, 0, 0);
        AtomicBoolean flag = new AtomicBoolean();
        NoAuthVideoClient client = client(link, flag, new ArrayList<>());
        client.begin(); flag.set(true);
        assertThrows(IOException.class, () -> client.frame(IDR, 0));
        client.close(); client.close();
        assertTrue(link.closed && link.cleanup);
        assertEquals(3, link.writes.size());
    }

    @Test
    public void interruptedWorkerRestoresInterruptAfterCleanup() throws Exception {
        Link link = new Link().ready().ack(3, 0, 0, 0, 0, 0);
        NoAuthVideoClient client = client(link, new AtomicBoolean(), new ArrayList<>());
        client.begin();
        Thread.currentThread().interrupt();
        try {
            client.close();
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertTrue(link.closed);
    }

    @Test
    public void wrongMagicClosesWithoutSendingOrOpeningWindow() {
        Link link = new Link().offer(); link.reads.peekFirst()[1] = 'I';
        NoAuthVideoClient client = client(link, new AtomicBoolean(), new ArrayList<>());
        assertThrows(IOException.class, client::begin);
        assertTrue(link.closed && link.writes.isEmpty());
        assertThrows(IOException.class, client::begin);
    }

    @Test
    public void failedStartStillRequestsCleanup() {
        Link link = new Link().offer().ack(7, 0, 0, 0, 0, 0).ack(1, 0, 0, 0, 0, 0)
                .ack(3, 0, 0, 0, 0, 0);
        NoAuthVideoClient client = client(link, new AtomicBoolean(), new ArrayList<>());
        assertThrows(IOException.class, client::begin);
        assertTrue(link.closed && link.cleanup);
        assertEquals(3, link.writes.size());
    }

    @Test
    public void wrongFrameAckNeverCountsAsSubmitted() throws Exception {
        Link link = new Link().ready().ack(2, 2, 0, 23, 2, 0).ack(3, 0, 0, 0, 0, 0);
        try (NoAuthVideoClient client = client(link, new AtomicBoolean(), new ArrayList<>())) {
            client.begin();
            assertThrows(IOException.class, () -> client.frame(IDR, 0));
            assertTrue(client.statistics().contains("acknowledged=0"));
        }
    }

    @Test
    public void missingVisibilityReportFailsDespiteSubmittedFrames() throws Exception {
        Link link = new Link().ready().ack(2, 1, 0, 3, 1, 0).ack(2, 2, 33, 3, 2, 33)
                .ack(8, 0, 0, 7, 2, 33).ack(8, 0, 0, 7, 2, 33).ack(8, 0, 0, 7, 2, 33)
                .ack(3, 0, 0, 0, 2, 0);
        try (NoAuthVideoClient client = client(link, new AtomicBoolean(), new ArrayList<>())) {
            client.begin(); client.frame(IDR, 0); client.frame(P, 33000);
            assertThrows(IOException.class, client::requireWindowReport);
        }
        assertTrue(link.closed);
    }

    @Test
    public void oneFrameCannotConfirmMotionAndStopStateMustBeEmpty() throws Exception {
        Link link = new Link().ready().ack(2, 1, 0, 23, 1, 0).ack(3, 0, 0, 1, 1, 0);
        NoAuthVideoClient client = client(link, new AtomicBoolean(), new ArrayList<>());
        client.begin(); client.frame(IDR, 0);
        assertThrows(IOException.class, client::requireWindowReport);
        assertThrows(IOException.class, client::close);
        assertTrue(link.closed);
        client.close();
    }

    @Test
    public void linkLossAtStopIsNotSwallowedOrReportedClean() throws Exception {
        Link link = new Link().ready(); link.failStop = true;
        List<String> logs = new ArrayList<>();
        NoAuthVideoClient client = client(link, new AtomicBoolean(), logs);
        client.begin();
        assertThrows(IOException.class, client::close);
        assertTrue(link.closed);
        assertTrue(logs.stream().anyMatch(line -> line.startsWith("STOP_UNCONFIRMED")));
        assertFalse(logs.stream().anyMatch(line -> line.startsWith("STOP_CLEANUP_REPORTED")));
    }

    @Test
    public void repeatedBeginAndInvalidPtsAreRejectedBeforeSending() throws Exception {
        Link link = new Link().ready().ack(2, 1, 0, 23, 1, 0).ack(3, 0, 0, 0, 1, 0);
        try (NoAuthVideoClient client = client(link, new AtomicBoolean(), new ArrayList<>())) {
            assertThrows(IOException.class, () -> client.frame(IDR, 0));
            client.begin();
            assertThrows(IOException.class, client::begin);
            client.frame(IDR, 1000000);
            for (long pts : new long[] {-1, 999999, 3001000}) {
                assertThrows(IOException.class, () -> client.frame(P, pts));
            }
        }
    }

    @Test
    public void closedOrCancelledNewSessionNeverConnects() throws Exception {
        Link link = new Link();
        NoAuthVideoClient client = client(link, new AtomicBoolean(true), new ArrayList<>());
        assertThrows(IOException.class, client::begin);
        client.close();
        assertFalse(link.closed);
        assertThrows(IOException.class, client::requireWindowReport);
    }

    @Test
    public void stagedErrorWithConfirmedCleanupDoesNotStopAnAlreadyClosedPeer() throws Exception {
        for (int code : new int[] {2, 3, 4, 5}) {
            Link link = new Link().ready();
            link.reads.add(header(0x82, 16, 1, 0));
            byte[] error = state(0, 0, 0); error[3] = (byte) code; link.reads.add(error);
            List<String> logs = new ArrayList<>();
            try (NoAuthVideoClient client = client(link, new AtomicBoolean(), logs)) {
                client.begin();
                IOException rejected = assertThrows(IOException.class, () -> client.frame(IDR, 0));
                assertTrue(rejected.getMessage().contains("flags=0 submissions=0"));
                assertThrows(IOException.class, () -> client.frame(IDR, 1));
            }
            assertEquals(3, link.writes.size());
            assertTrue(link.closed);
            assertTrue(logs.stream().anyMatch(text -> text.startsWith("ERROR_CLEANUP_REPORTED")));
        }
    }

    @Test
    public void failedCleanupAndLegacyErrorCannotSkipStopOrFakeClean() throws Exception {
        for (int[] rejected : new int[][] {{2, 8}, {1, 0}, {3, 1}}) {
            Link link = new Link().ready();
            link.reads.add(header(0x82, 16, 1, 0));
            byte[] error = state(rejected[1], 0, 0); error[3] = (byte) rejected[0]; link.reads.add(error);
            link.ack(3, 0, 0, 0, 0, 0);
            List<String> logs = new ArrayList<>();
            try (NoAuthVideoClient client = client(link, new AtomicBoolean(), logs)) {
                client.begin(); assertThrows(IOException.class, () -> client.frame(IDR, 0));
            }
            assertEquals(4, link.writes.size());
            assertFalse(logs.stream().anyMatch(text -> text.startsWith("ERROR_CLEANUP_REPORTED")));
        }
    }
}
