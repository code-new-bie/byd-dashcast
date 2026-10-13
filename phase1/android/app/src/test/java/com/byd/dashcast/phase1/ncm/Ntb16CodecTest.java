package com.byd.dashcast.phase1.ncm;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** Negotiated bounds and malformed USB payload tests. */
public final class Ntb16CodecTest {
    @Test
    public void roundTripUsesNegotiatedAlignmentAndRemainder() throws Exception {
        List<byte[]> frames = Arrays.asList(new byte[60], new byte[1514], new byte[100]);
        byte[] wire = Ntb16Codec.encode(frames, 65536, 16384, 16, 8, 2);
        assertEquals(16, u16(wire, 10));
        assertEquals(0, u16(wire, 6));
        for (int index = 0; index < frames.size(); index++) {
            assertEquals(2, u16(wire, 16 + 8 + 4 * index) % 8);
            assertArrayEquals(frames.get(index), Ntb16Codec.decode(wire, wire.length).get(index));
        }
    }

    @Test
    public void acceptsNdpAfterDatagram() throws Exception {
        byte[] wire = new byte[88];
        System.arraycopy(new byte[]{'N', 'C', 'M', 'H', 12, 0, 1, 0, 88, 0, 76, 0}, 0, wire, 0, 12);
        System.arraycopy(new byte[]{'N', 'C', 'M', '0', 12, 0, 0, 0, 12, 0, 60, 0}, 0, wire, 76, 12);
        // NDP needs one terminator as well as a datagram entry: extend it to 16 bytes.
        wire = Arrays.copyOf(wire, 92);
        wire[8] = 92;
        wire[80] = 16;
        assertEquals(1, Ntb16Codec.decode(wire, wire.length).size());
    }

    @Test
    public void rejectsTruncationOverlapsCrcAndMissingTerminator() throws Exception {
        byte[] valid = Ntb16Codec.encode(Collections.singletonList(new byte[60]), 0, 2048, 4, 4, 0);
        assertThrows(IOException.class, () -> Ntb16Codec.decode(valid, valid.length - 1));
        byte[] overlap = valid.clone();
        overlap[20] = 12;
        assertThrows(IOException.class, () -> Ntb16Codec.decode(overlap, overlap.length));
        byte[] crc = valid.clone();
        crc[15] = '1';
        assertThrows(IOException.class, () -> Ntb16Codec.decode(crc, crc.length));
        byte[] chained = valid.clone();
        chained[18] = 12;
        assertThrows(IOException.class, () -> Ntb16Codec.decode(chained, chained.length));
        byte[] noTerminator = valid.clone();
        noTerminator[24] = 40;
        noTerminator[26] = 14;
        assertThrows(IOException.class, () -> Ntb16Codec.decode(noTerminator, noTerminator.length));
    }

    @Test
    public void rejectsOversizeFramesAndOverflowingNtb() {
        assertThrows(IOException.class, () -> Ntb16Codec.encode(Collections.singletonList(new byte[1515]),
                0, 16384, 4, 4, 0));
        assertThrows(IOException.class, () -> Ntb16Codec.encode(Arrays.asList(new byte[1514], new byte[1514]),
                0, 2048, 4, 4, 0));
        assertThrows(IOException.class, () -> Ntb16Codec.encode(Collections.singletonList(new byte[60]),
                0, 16384, 4, 4, 4));
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 255) | (data[offset + 1] & 255) << 8;
    }
}
