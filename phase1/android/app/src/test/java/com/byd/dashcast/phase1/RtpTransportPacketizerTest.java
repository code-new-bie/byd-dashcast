package com.byd.dashcast.phase1;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.nio.ByteBuffer;
import static org.junit.Assert.*;

/** @author Codex @date 2026/10/10 */
public final class RtpTransportPacketizerTest {
    @Test
    public void integralTsPacketsFitMtuAndSequenceWraps() throws Exception {
        List<byte[]> packets = new ArrayList<>();
        RtpTransportPacketizer rtp = new RtpTransportPacketizer(
                (data, length) -> packets.add(Arrays.copyOf(data, length)), 0x12345678, 65535);
        rtp.beginAccessUnit(1_000_000L);
        byte[] ts = new byte[188];
        ts[0] = 0x47;
        for (int index = 0; index < 8; index++) {
            rtp.write(ts);
        }
        rtp.flush();
        assertEquals(2, packets.size());
        assertEquals(1328, packets.get(0).length);
        assertEquals(200, packets.get(1).length);
        assertEquals(33, packets.get(0)[1]);
        assertEquals(65535, ByteBuffer.wrap(packets.get(0)).getShort(2) & 0xffff);
        assertEquals(0, ByteBuffer.wrap(packets.get(1)).getShort(2) & 0xffff);
        assertEquals(90000, ByteBuffer.wrap(packets.get(0)).getInt(4));
        assertEquals(0x12345678, ByteBuffer.wrap(packets.get(0)).getInt(8));
    }

    @Test
    public void changingTimestampFlushesOldClockFirst() throws Exception {
        List<byte[]> packets = new ArrayList<>();
        RtpTransportPacketizer rtp = new RtpTransportPacketizer(
                (data, length) -> packets.add(Arrays.copyOf(data, length)), 7, 0);
        byte[] ts = new byte[188];
        ts[0] = 0x47;
        rtp.beginAccessUnit(0);
        rtp.write(ts);
        rtp.beginAccessUnit(1_000_000L);
        rtp.write(ts);
        rtp.flush();
        assertEquals(0, ByteBuffer.wrap(packets.get(0)).getInt(4));
        assertEquals(90000, ByteBuffer.wrap(packets.get(1)).getInt(4));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsPartialTsPacket() throws Exception {
        new RtpTransportPacketizer((bytes, length) -> {}, 0, 0).write(new byte[187]);
    }
}
