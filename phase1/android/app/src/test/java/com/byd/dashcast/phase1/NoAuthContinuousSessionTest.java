package com.byd.dashcast.phase1;

import org.junit.Test;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

/** 54,000 frames/30 minutes of virtual timestamps over an in-memory ordered peer, not real USB. */
public final class NoAuthContinuousSessionTest {
    private static final class Peer implements NoAuthVideoTransport {
        final ArrayDeque<byte[]> replies = new ArrayDeque<>();
        int frames;
        long lastPts;
        int heartbeats;
        boolean stopped;

        Peer() {
            replies.add(NoAuthVideoProtocolTest.header(0x85, 80, 0, 0));
            replies.add(new byte[80]);
        }

        @Override public void write(byte[] data) {
            ByteBuffer wire = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
            wire.position(4);
            int kind = wire.getInt();
            wire.getInt();
            int sequence = wire.getInt();
            long pts = Integer.toUnsignedLong(wire.getInt());
            if (kind == 7) { assertEquals(2, data[39]); }
            if (kind == 2) { assertEquals(++frames, sequence); lastPts = pts; }
            if (kind == 8) { heartbeats++; }
            if (kind == 3) { stopped = true; }
            replies.add(NoAuthVideoProtocolTest.header(kind | 0x80, 16, sequence, pts));
            replies.add(NoAuthVideoProtocolTest.state(kind == 3 || kind == 7 ? 0 : frames == 0 ? 1 : 23,
                    frames, kind == 3 ? 0 : lastPts));
        }

        @Override public byte[] readExact(int count) { byte[] data = replies.remove(); assertEquals(count, data.length); return data; }
        @Override public void beginCleanup() {}
        @Override public void close() {}
    }

    @Test
    public void continuousFramesPassOldMinuteLimitWithHeartbeatAndSameStreamStop() throws Exception {
        Peer peer = new Peer();
        AtomicLong clock = new AtomicLong();
        NoAuthVideoClient client = new NoAuthVideoClient((port, stop) -> peer,
                new AtomicBoolean(), message -> {}, clock::get, true);
        try (NoAuthVideoClient owned = client) {
            owned.begin();
            for (int index = 0; index < 54000; index++) {
                clock.set(index * 1_000_000_000L / 30);
                owned.frame(index == 0 ? NoAuthVideoProtocolTest.IDR : NoAuthVideoProtocolTest.P,
                        1_000_000L + index * 1_000_000L / 30);
            }
            owned.requireWindowReport();
            assertTrue(owned.statistics().contains("acknowledged=54000"));
            assertTrue(owned.statistics().contains("source_duration_ms=1799966"));
        }
        assertEquals(54000, peer.frames);
        assertTrue(peer.heartbeats >= 1700 && peer.heartbeats <= 1801);
        assertTrue(peer.stopped && client.cleanupConfirmed());
        assertTrue(peer.replies.isEmpty());
    }

    @Test
    public void shortAndLongContractsKeepIndependentFrameBounds() throws Exception {
        assertThrows(IOException.class, () -> NoAuthVideoProtocol.frame(NoAuthVideoProtocolTest.P, 1801, 60000));
        NoAuthVideoProtocol.frame(NoAuthVideoProtocolTest.P, 1801, 60000, true);
        NoAuthVideoProtocol.frame(NoAuthVideoProtocolTest.P, 65000, 1800000, true);
        assertThrows(IOException.class, () -> NoAuthVideoProtocol.frame(NoAuthVideoProtocolTest.P, 65001, 1800000, true));
        assertThrows(IOException.class, () -> NoAuthVideoProtocol.heartbeat(65005, true));
    }

    @Test
    public void encoderEndClosesNativeBeforeWaitingForAndroidCaptureCleanupExit() throws Exception {
        Peer peer = new Peer();
        NoAuthVideoClient client = new NoAuthVideoClient((port, stop) -> peer,
                new AtomicBoolean(), message -> {}, () -> 0L, true);
        client.begin();
        FramedCaptureReader reader = new FramedCaptureReader(new VideoPatternProbe.Observer() {
            @Override public void log(String message) {}
            @Override public void frame(byte[] data, long pts, boolean key) throws IOException {
                client.frame(data, pts);
            }
        }, () -> {
            try {
                client.requireWindowReport();
                client.close();
            } catch (IOException error) {
                throw new AssertionError(error);
            }
        });
        for (int index = 0; index < 2; index++) {
            byte[] data = index == 0 ? NoAuthVideoProtocolTest.IDR : NoAuthVideoProtocolTest.P;
            reader.onOutput(CaptureFrameProtocol.header(data.length, index * 33333L, index == 0), 24);
            reader.onOutput(data, data.length);
        }
        reader.onOutput(CaptureFrameProtocol.end(33333L), 24);
        assertTrue(peer.stopped && client.cleanupConfirmed());
        // shell EXIT may follow much later; the instrument video-idle watchdog cannot race this STOP.
        reader.finish(false);
    }
}
