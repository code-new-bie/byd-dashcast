package com.byd.dashcast.phase1;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** @author Codex @date 2026/10/10 */
public final class MpegTsMuxerTest {
    @Test
    public void tablesHaveValidMpegCrcAndAvcProgram() throws Exception {
        List<byte[]> packets = new ArrayList<>();
        new MpegTsMuxer(packets::add).writeTables();
        assertEquals(2, packets.size());
        for (byte[] packet : packets) {
            assertEquals(188, packet.length);
            int sectionLength = ((packet[6] & 0x0f) << 8) | (packet[7] & 0xff);
            assertEquals(0, MpegTsMuxer.crc32(packet, 5, 3 + sectionLength));
        }
        assertEquals(0x1b, packets.get(1)[17]);
        assertEquals(0xe1, packets.get(1)[18] & 0xff);
    }

    @Test
    public void pesCanBeReassembledWithPcrAndPts() throws Exception {
        List<byte[]> packets = new ArrayList<>();
        MpegTsMuxer muxer = new MpegTsMuxer(packets::add);
        byte[] frame = new byte[4099];
        for (int index = 0; index < frame.length; index++) {
            frame[index] = (byte)index;
        }
        frame[0] = 0;
        frame[1] = 0;
        frame[2] = 0;
        frame[3] = 1;
        frame[4] = 0x65;
        muxer.writeAccessUnit(frame, 1_000_000L, true);
        ByteArrayOutputStream assembled = new ByteArrayOutputStream();
        for (int index = 0; index < packets.size(); index++) {
            byte[] packet = packets.get(index);
            assertEquals(index & 15, packet[3] & 15);
            int offset = (packet[3] & 0x20) != 0 ? 5 + (packet[4] & 0xff) : 4;
            assembled.write(packet, offset, packet.length - offset);
        }
        assertEquals(0x50, packets.get(0)[5]);
        byte[] pes = assembled.toByteArray();
        long pts = ((long)(pes[9] & 0x0e) << 29) | ((long)(pes[10] & 0xff) << 22)
                | ((long)(pes[11] & 0xfe) << 14) | ((long)(pes[12] & 0xff) << 7) | ((pes[13] & 0xfe) >>> 1);
        assertEquals(90_000L, pts);
        assertEquals(frame.length + 20, pes.length);
        assertArrayEquals(frame, java.util.Arrays.copyOfRange(pes, 20, pes.length));
    }

    @Test
    public void veryShortFrameUsesValidAdaptationStuffing() throws Exception {
        List<byte[]> packets = new ArrayList<>();
        new MpegTsMuxer(packets::add).writeAccessUnit(new byte[] {0, 0, 1, 0x65}, 0, true);
        assertEquals(1, packets.size());
        assertEquals(159, packets.get(0)[4] & 0xff);
        assertEquals(188, packets.get(0).length);
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativePtsRejected() throws Exception {
        new MpegTsMuxer(packet -> {}).writeAccessUnit(new byte[] {1}, -1, false);
    }
}
