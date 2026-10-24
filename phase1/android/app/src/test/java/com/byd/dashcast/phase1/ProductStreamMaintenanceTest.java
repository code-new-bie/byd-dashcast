package com.byd.dashcast.phase1;

import org.junit.Test;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.byd.dashcast.phase1.NoAuthVideoProtocolTest.*;
import static org.junit.Assert.*;

/** Runs the actual client and composition coordinator, not a rewritten scheduler.
 * The independent peer expires its five-second lease even when media stays busy.
 * @author Codex
 * @date 2026/10/24
 */
public final class ProductStreamMaintenanceTest {
    private static final class Peer implements NoAuthVideoTransport {
        long now = 100_000_000_000L;
        long expiry = Long.MAX_VALUE;
        int state;
        int owner;
        int mode;
        int reason;
        int sequence;
        int frames;
        int renewals;
        int activations;
        int restores;
        int stops;
        long lastPts;
        boolean noDraw;
        boolean closed;
        long frameAckNanos;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final List<Integer> wire = new ArrayList<>();
        final ArrayDeque<byte[]> replies = new ArrayDeque<>();

        NoAuthVideoClient client(boolean center) throws Exception {
            closed = false;
            replies.add(header(0x85, 80, 0, 0)); replies.add(new byte[80]);
            NoAuthVideoClient client = new NoAuthVideoClient((port, flag) -> this,
                    cancelled, message -> {}, () -> now, true);
            client.enableUnlimitedStream();
            client.enableProductComposition(77, center);
            client.begin();
            return client;
        }

        @Override public void write(byte[] data) throws IOException {
            if (state == 1 && now >= expiry) { state = 2; reason = 1; mode = 0; }
            ByteBuffer request = ByteBuffer.wrap(data);
            int type = request.getInt(4);
            wire.add(type);
            if (type == 10) {
                int operation = request.getInt(28);
                if (operation == 1) {
                    assertTrue(state != 1); owner = request.getInt(32);
                    state = 1; reason = 0; mode = request.getInt(36) == 5001 ? 1 : 0;
                    expiry = now + 5_000_000_000L; sequence += 2; activations++;
                } else if (operation == 3) {
                    assertEquals(1, state); assertEquals(owner, request.getInt(32));
                    assertEquals(5000, request.getInt(36));
                    expiry = now + 5_000_000_000L; sequence += 2; renewals++;
                } else if (operation == 2) {
                    assertEquals(owner, request.getInt(32)); state = 2; mode = 0;
                    sequence += 2; restores++;
                }
                int[] values = new int[32];
                values[0] = 0x31474244; values[1] = 1; values[2] = 128;
                values[3] = sequence; values[8] = sequence; values[9] = state;
                values[10] = 123; values[11] = reason; values[18] = 1;
                values[12] = (int)(now / 1_000_000_000L);
                values[13] = (int)(now % 1_000_000_000L);
                values[14] = state == 1 ? (int)(expiry / 1_000_000_000L) : 0;
                values[15] = state == 1 ? (int)(expiry % 1_000_000_000L) : 0;
                values[16] = values[17] = state == 1 && !noDraw ? (int)(now / 1_000_000L) : 0;
                values[20] = values[21] = owner; values[30] = mode;
                values[29] = state == 1 ? 0x4e4c4301 : 0x4e4c4300;
                try {
                    // Reflection keeps the same test executable against frozen RWV4 and new RWV5.
                    values[31] = ProductCompositionSession.class.getDeclaredField("CAPABILITY").getInt(null);
                } catch (ReflectiveOperationException error) { throw new IOException(error); }
                ByteBuffer body = ByteBuffer.allocate(144).putInt(1).putInt(0x42475031)
                        .putInt(operation).putInt(0);
                for (int value : values) { body.putInt(value); }
                replies.add(header(0x8a, 144, 0, 0));
                replies.add(java.util.Arrays.copyOfRange(body.array(), 0, 80));
                replies.add(java.util.Arrays.copyOfRange(body.array(), 80, 144));
                return;
            }
            if (type == 1) { frames = 0; lastPts = 0; }
            if (type == 2) {
                frames++; lastPts = Integer.toUnsignedLong(request.getInt(16));
                now += frameAckNanos;
            }
            if (type == 3) { stops++; }
            int flags = type == 1 ? 1 : type == 2 || type == 8 ? 7 : 0;
            replies.add(header(type | 0x80, 16, type == 2 ? request.getInt(12) : 0,
                    type == 2 ? lastPts : 0));
            replies.add(state(flags, frames, type == 3 ? 0 : lastPts));
        }

        @Override public byte[] readExact(int size) throws IOException {
            byte[] data = replies.pollFirst();
            if (data == null || data.length != size) { throw new IOException("test peer reply order"); }
            return data;
        }
        @Override public void beginCleanup() { }
        @Override public void close() { closed = true; }
    }

    @Test public void busyThirtyFpsWithoutAnyIdleCallbackRenewsForTwoMinutes() throws Exception {
        for (boolean center : new boolean[] {false, true}) {
            Peer peer = new Peer();
            try (NoAuthVideoClient client = peer.client(center)) {
                for (int frame = 0; frame < 3601; frame++) {
                    peer.now = 100_000_000_000L + frame * 33_333_334L;
                    client.frame(frame == 0 ? IDR : P, frame * 33_333L);
                    if (client.readyForStreaming()) {
                        assertEquals("busy frames must not starve the native lease", 1, peer.state);
                    }
                }
                assertTrue(peer.renewals >= 110);
                assertEquals(1, peer.activations);
                assertEquals(center ? 1 : 0, peer.mode);
            }
            assertEquals(1, peer.restores); assertEquals(1, peer.stops); assertTrue(peer.closed);
        }
    }

    @Test public void busySixteenFpsDoesNotNeedAThirtyFpsThresholdOrIdleCallback() throws Exception {
        Peer peer = new Peer();
        try (NoAuthVideoClient client = peer.client(false)) {
            for (int frame = 0; frame <= 480; frame++) {
                peer.now = 100_000_000_000L + frame * 62_500_000L;
                client.frame(frame == 0 ? IDR : P, frame * 62_500L);
                if (client.readyForStreaming()) { assertEquals(1, peer.state); }
            }
            assertTrue(peer.renewals > 20);
        }
    }

    private void activate(Peer peer, NoAuthVideoClient client) throws Exception {
        for (int frame = 0; frame <= 40; frame++) {
            peer.now += 33_333_334L;
            client.frame(frame == 0 ? IDR : P, frame * 33_333L);
        }
        assertTrue(client.readyForStreaming());
    }

    @Test public void staticSourceIdleStillRenewsWithoutInventingFrames() throws Exception {
        Peer peer = new Peer();
        try (NoAuthVideoClient client = peer.client(false)) {
            activate(peer, client);
            int frames = peer.frames;
            for (int tick = 0; tick < 80; tick++) {
                peer.now += 250_000_000L; client.maintain();
                assertEquals(1, peer.state);
            }
            assertEquals(frames, peer.frames); assertTrue(peer.renewals >= 19);
        }
    }

    @Test public void busyPathRejectsNativeRevocationBeforeAnotherFrame() throws Exception {
        for (boolean foreign : new boolean[] {false, true}) {
            Peer peer = new Peer(); NoAuthVideoClient client = peer.client(false);
            activate(peer, client);
            if (foreign) { peer.owner = 88; } else { peer.state = 2; peer.reason = 1; }
            int frames = peer.frames; peer.now += 300_000_000L;
            assertThrows(IOException.class, () -> client.frame(P, 41 * 33_333L));
            assertEquals(frames, peer.frames);
            peer.owner = 77; peer.reason = 0;
            client.close(); assertTrue(client.cleanupConfirmed());
        }
    }

    @Test public void fourSecondUnconfirmedBusyGapCannotBeRenewedBlindly() throws Exception {
        Peer peer = new Peer(); NoAuthVideoClient client = peer.client(false);
        activate(peer, client);
        int frames = peer.frames, renewals = peer.renewals;
        peer.now += 4_000_000_000L;
        IOException error = assertThrows(IOException.class, () -> client.frame(P, 41 * 33_333L));
        assertTrue(error.getMessage().contains("CONFIRMATION_LOST"));
        assertEquals(frames, peer.frames); assertEquals(renewals, peer.renewals);
        client.close(); assertTrue(client.cleanupConfirmed());
    }

    @Test public void noNativeDrawCannotBeMaskedByBusyVideoFrames() throws Exception {
        Peer peer = new Peer(); peer.noDraw = true;
        NoAuthVideoClient client = peer.client(false); activate(peer, client);
        peer.now += 1_000_000_000L;
        IOException error = assertThrows(IOException.class, () -> client.frame(P, 41 * 33_333L));
        assertTrue(error.getMessage().contains("DRAW_NOT_CONFIRMED"));
        assertEquals(0, peer.renewals); client.close();
    }

    @Test public void stopAndNewSessionDoNotCarryAnOldOwnerOrReplayStart() throws Exception {
        Peer peer = new Peer();
        for (int round = 0; round < 3; round++) {
            try (NoAuthVideoClient client = peer.client(round % 2 != 0)) {
                activate(peer, client);
                for (int tick = 0; tick < 10; tick++) { peer.now += 250_000_000L; client.maintain(); }
            }
            assertEquals(2, peer.state); assertTrue(peer.closed); assertTrue(peer.replies.isEmpty());
        }
        assertEquals(3, peer.activations); assertEquals(3, peer.restores); assertEquals(3, peer.stops);
    }

    @Test public void cancelNeverRenewsAndRestoreStillPrecedesStop() throws Exception {
        Peer peer = new Peer(); NoAuthVideoClient client = peer.client(false); activate(peer, client);
        int renewals = peer.renewals; peer.cancelled.set(true); peer.now += 1_000_000_000L;
        assertThrows(IOException.class, () -> client.frame(P, 41 * 33_333L));
        assertEquals(renewals, peer.renewals); client.close();
        assertEquals(3, (int)peer.wire.get(peer.wire.size() - 1));
        assertEquals(1, peer.restores); assertEquals(1, peer.stops);
    }

    private static void adb(ByteArrayOutputStream out, int type, int arg0, int arg1, byte[] payload)
            throws IOException {
        int checksum = 0;
        for (byte b : payload) { checksum += b & 255; }
        out.write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(type).putInt(arg0)
                .putInt(arg1).putInt(payload.length).putInt(checksum).putInt(type ^ -1).array());
        out.write(payload);
    }

    private static byte[] shell(int channel, byte[] payload) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).put((byte)channel)
                .putInt(payload.length).array());
        out.write(payload); return out.toByteArray();
    }

    private void adbStream(boolean batch) throws Exception {
        String realPath = System.getProperty("dashcast.continuity.avc");
        List<byte[]> realPictures = new ArrayList<>();
        List<Boolean> realKeys = new ArrayList<>();
        if (realPath != null) {
            byte[] raw = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(realPath));
            assertTrue(raw.length < 1024 * 1024);
            AnnexBAccessUnitReader avc = new AnnexBAccessUnitReader((data, key) -> {
                realPictures.add(data); realKeys.add(key);
            });
            avc.onOutput(raw, raw.length); avc.finish(); assertEquals(180, realPictures.size());
        }
        Peer peer = new Peer(); peer.frameAckNanos = realPath == null ? 55_000_000L : 75_000_000L;
        NoAuthVideoClient client = peer.client(false);
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        adb(input, 0x4e584e43, 0x01000000, 4096,
                "device::features=shell_v2;\0".getBytes(StandardCharsets.UTF_8));
        adb(input, 0x59414b4f, 7, 1, new byte[0]);
        ByteArrayOutputStream pictures = new ByteArrayOutputStream();
        for (int frame = 0; frame < 180; frame++) {
            byte[] data = realPath == null ? (frame == 0 ? IDR : P) : realPictures.get(frame);
            boolean key = realPath == null ? frame == 0 : realKeys.get(frame);
            pictures.write(CaptureFrameProtocol.header(data.length, frame * 33_333L, key));
            pictures.write(data);
            // First bind/confirm normally, then a valid sub-4096-byte WRTE batches
            // 100 small protocol AUs (60 actual software AVC AUs when provided).
            // Synchronous FRAME ACK/pacer work consumes wall time
            // INSIDE onOutput; ADB beforeRead cannot pulse again until it returns.
            if (!batch || frame < 40 || frame == (realPath == null ? 139 : 99) || frame == 179) {
                byte[] payload = shell(1, pictures.toByteArray());
                if (batch && frame == (realPath == null ? 139 : 99)) { assertTrue(payload.length <= 4096); }
                for (int start = 0; start < payload.length; start += 4096) {
                    adb(input, 0x45545257, 7, 1, java.util.Arrays.copyOfRange(payload, start,
                            Math.min(start + 4096, payload.length)));
                }
                pictures.reset();
            }
        }
        adb(input, 0x45545257, 7, 1, shell(1, CaptureFrameProtocol.end(179 * 33_333L)));
        adb(input, 0x45545257, 7, 1, shell(3, new byte[] {0}));
        adb(input, 0x45534c43, 7, 1, new byte[0]);
        FramePacer pacer = new FramePacer(30, () -> peer.now, nanos -> peer.now += nanos);
        AvcReceiverContract contract = new AvcReceiverContract();
        FramedCaptureReader reader = new FramedCaptureReader(new VideoPatternProbe.Observer() {
            @Override public void log(String message) { }
            @Override public void tick() throws IOException { client.maintain(); }
            @Override public void frame(byte[] data, long pts, boolean key) throws IOException {
                if (realPath != null) { contract.accept(data, message -> {}); }
                pacer.awaitNextSlot(); client.frame(data, pts);
            }
        }, () -> { try { client.close(); } catch (IOException error) { throw new IllegalStateException(error); } }, true);
        AdbShellClient adbClient = new AdbShellClient("127.0.0.1", 5555, 20_000, null,
                message -> {}, new AtomicBoolean(), () -> peer.now);
        try {
            adbClient.executeControlledProtocol(new ByteArrayInputStream(input.toByteArray()),
                    new ByteArrayOutputStream(), "owned-capture-fixture", reader, new AtomicBoolean(),
                    () -> { try { client.close(); } catch (IOException error) { throw new IllegalStateException(error); } });
            reader.finish(false);
            assertEquals(180, peer.frames); assertTrue(peer.renewals >= 7);
            assertEquals(1, peer.restores); assertEquals(1, peer.stops);
        } finally { client.close(); }
    }

    @Test public void actualAdbReadPulseAlreadyMaintainsOrdinaryNonBatchedFrames() throws Exception {
        adbStream(false);
    }

    @Test public void actualAdbBatchedWrteCannotStarveLeaseInsideSynchronousFrameConsumer() throws Exception {
        adbStream(true);
    }
}
