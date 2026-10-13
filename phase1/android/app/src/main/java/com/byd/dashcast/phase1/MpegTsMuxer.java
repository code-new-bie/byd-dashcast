package com.byd.dashcast.phase1;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Single H.264 program with PAT/PMT, PES PTS and a PCR per access unit; no audio.
 *
 * @author Codex
 * @date 2026/10/10
 */
public final class MpegTsMuxer {
    public static final int PACKET_SIZE = 188;
    private static final int PMT_PID = 0x1000;
    private static final int VIDEO_PID = 0x100;
    private static final long CLOCK_MASK = (1L << 33) - 1;
    private final PacketSink sink;
    private int patCounter;
    private int pmtCounter;
    private int videoCounter;

    /** Receives one complete transport packet. */
    public interface PacketSink {
        /** @param packet exactly 188 bytes; @throws IOException on output failure */
        void write(byte[] packet) throws IOException;
    }

    /** @param sink destination of complete TS packets */
    public MpegTsMuxer(PacketSink sink) {
        if (sink == null) {
            throw new IllegalArgumentException("TS sink required");
        }
        this.sink = sink;
    }

    /** @throws IOException on output failure */
    public void writeTables() throws IOException {
        byte[] pat = {(byte)0x00, (byte)0xb0, 0x0d, 0, 1, (byte)0xc1, 0, 0, 0, 1, (byte)0xf0, 0};
        byte[] pmt = {2, (byte)0xb0, 0x12, 0, 1, (byte)0xc1, 0, 0,
                (byte)0xe1, 0, (byte)0xf0, 0, 0x1b, (byte)0xe1, 0, (byte)0xf0, 0};
        writeSection(0, patCounter++, pat);
        writeSection(PMT_PID, pmtCounter++, pmt);
    }

    /**
     * @param annexB one complete H.264 access unit, including start codes
     * @param presentationMicros nonnegative presentation timestamp
     * @param keyFrame random access indicator
     * @throws IOException on output failure
     */
    public void writeAccessUnit(byte[] annexB, long presentationMicros, boolean keyFrame) throws IOException {
        if (annexB == null || annexB.length == 0 || presentationMicros < 0) {
            throw new IllegalArgumentException("Access unit and nonnegative PTS required");
        }
        annexB = AvcByteStream.withoutAud(annexB);
        if (annexB.length == 0) {
            throw new IllegalArgumentException("AUD-only access unit is not video");
        }
        long clock = (presentationMicros / 1000 * 90 + presentationMicros % 1000 * 90 / 1000) & CLOCK_MASK;
        ByteArrayOutputStream pes = new ByteArrayOutputStream(annexB.length + 20);
        pes.write(new byte[] {0, 0, 1, (byte)0xe0, 0, 0, (byte)0x80, (byte)0x80, 5});
        pes.write(encodePts(clock));
        // Exactly one Access Unit Delimiter per picture; duplicate AUDs can create empty pictures.
        pes.write(new byte[] {0, 0, 0, 1, 9, (byte)0xf0});
        pes.write(annexB);
        byte[] payload = pes.toByteArray();
        int offset = 0;
        while (offset < payload.length) {
            boolean first = offset == 0;
            int length = Math.min(first ? 176 : 184, payload.length - offset);
            byte[] packet = header(VIDEO_PID, videoCounter++, first);
            int cursor = 4;
            if (first || length < 184) {
                packet[3] |= 0x20;
                int adaptationLength = 183 - length;
                packet[cursor++] = (byte)adaptationLength;
                if (adaptationLength > 0) {
                    packet[cursor++] = first ? (byte)(0x10 | (keyFrame ? 0x40 : 0)) : 0;
                    if (first) {
                        writePcr(packet, cursor, clock);
                    }
                }
                cursor = 5 + adaptationLength;
            }
            System.arraycopy(payload, offset, packet, cursor, length);
            sink.write(packet);
            offset += length;
        }
    }

    private void writeSection(int pid, int counter, byte[] section) throws IOException {
        byte[] packet = header(pid, counter, true);
        packet[4] = 0;
        System.arraycopy(section, 0, packet, 5, section.length);
        int crc = crc32(section, 0, section.length);
        int cursor = 5 + section.length;
        for (int shift = 24; shift >= 0; shift -= 8) {
            packet[cursor++] = (byte)(crc >>> shift);
        }
        sink.write(packet);
    }

    private static byte[] header(int pid, int counter, boolean start) {
        byte[] packet = new byte[PACKET_SIZE];
        Arrays.fill(packet, (byte)0xff);
        packet[0] = 0x47;
        packet[1] = (byte)((pid >>> 8) | (start ? 0x40 : 0));
        packet[2] = (byte)pid;
        packet[3] = (byte)(0x10 | (counter & 0x0f));
        return packet;
    }

    private static void writePcr(byte[] packet, int offset, long clock) {
        packet[offset] = (byte)(clock >>> 25);
        packet[offset + 1] = (byte)(clock >>> 17);
        packet[offset + 2] = (byte)(clock >>> 9);
        packet[offset + 3] = (byte)(clock >>> 1);
        packet[offset + 4] = (byte)(((clock & 1) << 7) | 0x7e);
        packet[offset + 5] = 0;
    }

    private static byte[] encodePts(long clock) {
        return new byte[] {(byte)(0x21 | ((clock >>> 29) & 0x0e)), (byte)(clock >>> 22),
                (byte)(((clock >>> 14) & 0xfe) | 1), (byte)(clock >>> 7), (byte)(((clock << 1) & 0xfe) | 1)};
    }

    /** @param data input; @param offset first byte; @param length byte count; @return MPEG-2 CRC-32 */
    public static int crc32(byte[] data, int offset, int length) {
        int crc = -1;
        for (int index = offset; index < offset + length; index++) {
            crc ^= (data[index] & 0xff) << 24;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc << 1) ^ ((crc < 0) ? 0x04c11db7 : 0);
            }
        }
        return crc;
    }
}
