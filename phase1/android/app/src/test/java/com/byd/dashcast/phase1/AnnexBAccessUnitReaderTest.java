package com.byd.dashcast.phase1;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** AU grouping and input bounds only; synthetic slice prefixes do not test a hardware decoder. */
public final class AnnexBAccessUnitReaderTest {
    private static final byte[] SPS = nal(4, 7, new byte[] {0x42, (byte)0xc0, 0x28, (byte)0x80});
    private static final byte[] PPS = nal(4, 8, new byte[] {(byte)0xce, (byte)0x80});

    @Test
    public void noAudStreamIsSplitByFirstMacroblockNotAdbChunks() throws Exception {
        Collector output = new Collector();
        byte[] first = join(SPS, PPS, slice(4, 5, 0, 2));
        byte[] second = slice(4, 1, 0, 0);
        byte[] wire = join(first, second);
        for (byte value : wire) {
            output.reader.onOutput(new byte[] {value}, 1);
        }
        output.reader.finish();
        assertEquals(2, output.frames.size());
        assertArrayEquals(first, output.frames.get(0));
        assertArrayEquals(second, output.frames.get(1));
        assertEquals(Arrays.asList(true, false), output.keys);
    }

    @Test
    public void mixedThreeAndFourByteStartCodesNormalizeWithoutChangingNalPayload() throws Exception {
        Collector output = new Collector();
        byte[] wire = join(SPS, PPS, slice(3, 5, 0, 2), slice(3, 1, 0, 0));
        output.reader.onOutput(wire, wire.length);
        output.reader.finish();
        assertArrayEquals(join(SPS, PPS, slice(4, 5, 0, 2)), output.frames.get(0));
        assertArrayEquals(slice(4, 1, 0, 0), output.frames.get(1));
    }

    @Test
    public void additionalSlicesStayInOnePicture() throws Exception {
        Collector output = new Collector();
        byte[] first = join(SPS, PPS, slice(4, 5, 0, 2), slice(4, 5, 120, 2));
        byte[] wire = join(first, slice(4, 1, 0, 0));
        output.reader.onOutput(wire, wire.length);
        output.reader.finish();
        assertEquals(2, output.frames.size());
        assertArrayEquals(first, output.frames.get(0));
    }

    @Test
    public void audAndParameterSetsBelongToTheNextPicture() throws Exception {
        Collector output = new Collector();
        byte[] aud = nal(4, 9, new byte[] {(byte)0xf0});
        byte[] first = join(aud, SPS, PPS, slice(4, 5, 0, 2));
        byte[] second = join(aud, slice(4, 1, 0, 0));
        byte[] wire = join(first, second);
        output.reader.onOutput(wire, wire.length);
        output.reader.finish();
        assertArrayEquals(first, output.frames.get(0));
        assertArrayEquals(second, output.frames.get(1));
    }

    @Test
    public void unsupportedBFramesMissingFirstSliceAndMalformedPrefixesFailClosed() {
        for (byte[] bad : new byte[][] {slice(4, 1, 0, 1), slice(4, 1, 1, 0),
                nal(4, 1, new byte[] {0, 0, 0, 0}), nal(4, 2, new byte[] {(byte)0x80}),
                join(SPS, PPS, slice(4, 5, 0, 2), slice(4, 1, 120, 0))}) {
            AnnexBAccessUnitReader reader = new AnnexBAccessUnitReader((bytes, key) -> {});
            assertThrows(IOException.class, () -> { reader.onOutput(bad, bad.length); reader.finish(); });
        }
    }

    @Test
    public void textInsteadOfH264AndNoPictureDoNotCountAsCaptureSuccess() {
        AnnexBAccessUnitReader text = new AnnexBAccessUnitReader((bytes, key) -> {});
        assertThrows(IOException.class, () -> text.onOutput(new byte[] {'E', 'r', 'r'}, 3));
        assertThrows(IOException.class, text::finish);
        AnnexBAccessUnitReader empty = new AnnexBAccessUnitReader((bytes, key) -> {});
        assertThrows(IOException.class, empty::finish);
        AnnexBAccessUnitReader config = new AnnexBAccessUnitReader((bytes, key) -> {});
        assertThrows(IOException.class, () -> { config.onOutput(SPS, SPS.length); config.finish(); });
    }

    @Test
    public void callbackFailureCannotBeRetriedAsSuccessfulFinish() {
        AnnexBAccessUnitReader reader = new AnnexBAccessUnitReader((bytes, key) -> { throw new IOException("sink"); });
        byte[] wire = join(SPS, PPS, slice(4, 5, 0, 2));
        assertThrows(IOException.class, () -> { reader.onOutput(wire, wire.length); reader.finish(); });
        assertThrows(IOException.class, reader::finish);
        assertEquals(0, reader.frameCount());
    }

    @Test
    public void finishIsIdempotentButMoreOutputIsRejected() throws Exception {
        Collector output = new Collector();
        byte[] wire = join(SPS, PPS, slice(4, 5, 0, 2), new byte[] {0, 0});
        output.reader.onOutput(wire, wire.length);
        output.reader.finish();
        output.reader.finish();
        assertEquals(1, output.reader.frameCount());
        assertEquals(wire.length, output.reader.byteCount());
        assertThrows(IOException.class, () -> output.reader.onOutput(wire, wire.length));
    }

    @Test
    public void accessUnitAndFrameCountLimitsAreEnforced() {
        AnnexBAccessUnitReader huge = new AnnexBAccessUnitReader((bytes, key) -> {});
        byte[] payload = new byte[NoAuthVideoProtocol.MAX_FRAME_BYTES];
        Arrays.fill(payload, (byte)0x80);
        byte[] wire = join(new byte[] {0, 0, 0, 1, 0x65}, payload);
        assertThrows(IOException.class, () -> { huge.onOutput(wire, wire.length); huge.finish(); });
        AnnexBAccessUnitReader many = new AnnexBAccessUnitReader((bytes, key) -> {});
        byte[] picture = slice(4, 1, 0, 0);
        assertThrows(IOException.class, () -> {
            for (int index = 0; index < NoAuthVideoProtocol.MAX_TEST_FRAMES + 2; index++) {
                many.onOutput(picture, picture.length);
            }
            many.finish();
        });
        assertEquals(NoAuthVideoProtocol.MAX_TEST_FRAMES, many.frameCount());
    }

    @Test
    public void validatesInputsAndRequiresSpsPpsIdrForReceiverFirstFrame() {
        Collector output = new Collector();
        assertThrows(IllegalArgumentException.class, () -> output.reader.onOutput(null, 0));
        assertThrows(IllegalArgumentException.class, () -> output.reader.onOutput(new byte[1], 2));
        assertThrows(NullPointerException.class, () -> new AnnexBAccessUnitReader(null));
        byte[] bad = slice(4, 1, 0, 0);
        assertThrows(IOException.class, () -> { output.reader.onOutput(bad, bad.length); output.reader.finish(); });
    }

    private static byte[] slice(int prefix, int type, int firstMb, int sliceType) {
        String bits = ue(firstMb) + ue(sliceType) + "1";
        while (bits.length() % 8 != 0) {
            bits += "0";
        }
        byte[] payload = new byte[bits.length() / 8];
        for (int index = 0; index < payload.length; index++) {
            payload[index] = (byte)Integer.parseInt(bits.substring(index * 8, index * 8 + 8), 2);
        }
        return nal(prefix, type, payload);
    }

    private static String ue(int value) {
        String bits = Integer.toBinaryString(value + 1);
        return "0".repeat(bits.length() - 1) + bits;
    }

    private static byte[] nal(int prefix, int type, byte[] payload) {
        byte[] start = prefix == 3 ? new byte[] {0, 0, 1} : new byte[] {0, 0, 0, 1};
        return join(start, new byte[] {(byte)(type | 0x60)}, payload);
    }

    private static byte[] join(byte[]... values) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] value : values) {
            output.write(value, 0, value.length);
        }
        return output.toByteArray();
    }

    private static final class Collector {
        private final List<byte[]> frames = new ArrayList<>();
        private final List<Boolean> keys = new ArrayList<>();
        private final AnnexBAccessUnitReader reader = new AnnexBAccessUnitReader((data, key) -> {
            NoAuthVideoProtocol.frame(data, frames.size() + 1, 0);
            assertTrue(data.length > 4);
            frames.add(data);
            keys.add(key);
        });
    }
}
