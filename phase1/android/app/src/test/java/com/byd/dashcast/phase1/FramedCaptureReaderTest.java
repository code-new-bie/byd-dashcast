package com.byd.dashcast.phase1;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public final class FramedCaptureReaderTest {
    @Test
    public void idleAndReadTicksNeverCreatePicturesOrAdvancePts() throws Exception {
        int[] picturesAndTicks = {0, 0};
        FramedCaptureReader reader = new FramedCaptureReader(new VideoPatternProbe.Observer() {
            @Override public void log(String text) {}
            @Override public void frame(byte[] b, long pts, boolean key) { picturesAndTicks[0]++; }
            @Override public void tick() { picturesAndTicks[1]++; }
        });
        reader.onOutput(CaptureFrameProtocol.idle(0), 24);
        byte[] first = frame(123, NoAuthVideoProtocolTest.IDR);
        reader.onOutput(first, first.length);
        reader.onOutput(CaptureFrameProtocol.idle(123), 24);
        reader.onIdle();
        reader.onOutput(CaptureFrameProtocol.end(123), 24);
        assertThrows(IOException.class, () -> reader.finish(false));
        reader.finish(true);
        reader.onIdle();
        assertArrayEquals(new int[] {1, 3}, picturesAndTicks);
        assertThrows(IOException.class, () -> reader.onOutput(CaptureFrameProtocol.idle(123), 24));
    }

    @Test
    public void idleMustMatchLastActualPictureTimestamp() throws Exception {
        FramedCaptureReader reader = new FramedCaptureReader(new Sink());
        byte[] first = frame(100, new byte[] {1});
        reader.onOutput(first, first.length);
        assertThrows(IOException.class, () -> reader.onOutput(CaptureFrameProtocol.idle(101), 24));
        FramedCaptureReader empty = new FramedCaptureReader(new Sink());
        assertThrows(IOException.class, () -> empty.onOutput(CaptureFrameProtocol.idle(1), 24));
    }

    @Test
    public void wrongProtocolReportsOnlyTheFourStructuralBytes() throws Exception {
        byte[] header = "HotFixRoot: synthetic-test".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        IOException error = assertThrows(IOException.class,
                () -> CaptureFrameProtocol.parse(java.util.Arrays.copyOf(header, 24)));
        assertEquals("DCS1_WRONG_CAPTURE_PROTOCOL magic_hex=486f7446", error.getMessage());
    }

    private static final class Sink implements VideoPatternProbe.Observer {
        final List<Long> pts = new ArrayList<>();
        final List<byte[]> frames = new ArrayList<>();
        @Override public void log(String message) {}
        @Override public void frame(byte[] data, long timestamp, boolean key) { frames.add(data); pts.add(timestamp); }
    }

    private static byte[] frame(long pts, byte[] body) throws Exception {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(CaptureFrameProtocol.header(body.length, pts, true));
        stream.write(body);
        return stream.toByteArray();
    }

    @Test
    public void fragmentedFramesPreserveActualEncoderTimestampsAndPayload() throws Exception {
        Sink sink = new Sink();
        FramedCaptureReader reader = new FramedCaptureReader(sink);
        byte[] first = frame(123456789L, NoAuthVideoProtocolTest.IDR);
        byte[] second = frame(123490122L, NoAuthVideoProtocolTest.P);
        for (byte value : first) {
            reader.onOutput(new byte[] {value}, 1);
        }
        reader.onOutput(second, second.length);
        reader.onOutput(CaptureFrameProtocol.end(123490122L), 24);
        reader.finish(false);
        assertEquals(List.of(123456789L, 123490122L), sink.pts);
        assertArrayEquals(NoAuthVideoProtocolTest.IDR, sink.frames.get(0));
        assertArrayEquals(NoAuthVideoProtocolTest.P, sink.frames.get(1));
        assertThrows(IOException.class, () -> reader.onOutput(first, first.length));
    }

    @Test
    public void rejectsWrongMagicOversizeNegativePtsFlagsAndReservedBeforeAllocation() throws Exception {
        for (int field : new int[] {0, 4, 8, 16, 20}) {
            byte[] header = CaptureFrameProtocol.header(1, 1, false);
            ByteBuffer values = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
            if (field == 8) {
                values.putLong(field, -1);
            } else {
                values.putInt(field, field == 4 ? 0x200001 : 99);
            }
            FramedCaptureReader reader = new FramedCaptureReader(new Sink());
            assertThrows(IOException.class, () -> reader.onOutput(header, header.length));
        }
    }

    @Test
    public void truncatedFramesAndNonMonotonicPtsCannotFinishSuccessfully() throws Exception {
        Sink sink = new Sink();
        FramedCaptureReader partial = new FramedCaptureReader(sink);
        byte[] complete = frame(1, new byte[] {1, 2});
        partial.onOutput(complete, complete.length - 1);
        assertThrows(IOException.class, () -> partial.finish(true));
        FramedCaptureReader repeated = new FramedCaptureReader(sink);
        repeated.onOutput(complete, complete.length);
        assertThrows(IOException.class, () -> repeated.onOutput(complete, complete.length));
        assertThrows(IOException.class, () -> repeated.finish(true));
    }

    @Test
    public void fullThirtyMinuteFrameBudgetIsStreamedWithoutAggregateBuffer() throws Exception {
        final int[] count = {0};
        FramedCaptureReader reader = new FramedCaptureReader(new VideoPatternProbe.Observer() {
            @Override public void log(String message) {}
            @Override public void frame(byte[] data, long pts, boolean key) { count[0]++; }
        });
        for (int index = 0; index < ProjectionLimits.MAX_FRAMES; index++) {
            byte[] wire = frame(index * 33333L, new byte[] {1});
            reader.onOutput(wire, wire.length);
        }
        reader.onOutput(CaptureFrameProtocol.end((ProjectionLimits.MAX_FRAMES - 1) * 33333L), 24);
        reader.finish(false);
        assertEquals(65000, count[0]);
    }

    @Test
    public void stopBeforeAnyPictureIsNotACompletedVideoVerification() throws Exception {
        FramedCaptureReader stopped = new FramedCaptureReader(new Sink());
        stopped.onOutput(CaptureFrameProtocol.end(0), 24);
        stopped.finish(true);
        assertThrows(IOException.class, () -> new FramedCaptureReader(new Sink()).finish(false));
        assertThrows(IOException.class, () -> CaptureFrameProtocol.header(0, 0, false));
        assertThrows(IOException.class, () -> CaptureFrameProtocol.header(0x200001, 0, false));
    }

    @Test
    public void endMustBeExplicitMatchLastPtsAndForbidFurtherFrames() throws Exception {
        FramedCaptureReader missing = new FramedCaptureReader(new Sink());
        byte[] first = frame(1, new byte[] {1});
        missing.onOutput(first, first.length);
        assertThrows(IOException.class, () -> missing.finish(true));
        assertThrows(IOException.class, () -> missing.onOutput(CaptureFrameProtocol.end(2), 24));
        FramedCaptureReader ended = new FramedCaptureReader(new Sink());
        ended.onOutput(first, first.length);
        ended.onOutput(CaptureFrameProtocol.end(1), 24);
        assertThrows(IOException.class, () -> ended.onOutput(first, first.length));
    }
}
