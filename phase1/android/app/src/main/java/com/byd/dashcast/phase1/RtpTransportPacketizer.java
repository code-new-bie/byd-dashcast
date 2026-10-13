package com.byd.dashcast.phase1;

import java.io.IOException;

/**
 * RFC 2250 MP2T RTP payload (PT=33), up to seven whole TS packets per datagram.
 *
 * @author Codex
 * @date 2026/10/10
 */
public final class RtpTransportPacketizer {
    private static final int HEADER_SIZE = 12;
    private static final int TS_PER_DATAGRAM = 7;
    private final DatagramSink sink;
    private final int sourceId;
    private int sequence;
    private long timestamp;
    private int count;
    private byte[] buffer = new byte[HEADER_SIZE + TS_PER_DATAGRAM * MpegTsMuxer.PACKET_SIZE];

    /** Delivers a complete RTP datagram; does not imply reception. */
    public interface DatagramSink {
        /** @param bytes packet bytes; @param length valid length; @throws IOException on send failure */
        void send(byte[] bytes, int length) throws IOException;
    }

    /** @param sink datagram destination; @param sourceId random session SSRC; @param sequence initial sequence */
    public RtpTransportPacketizer(DatagramSink sink, int sourceId, int sequence) {
        if (sink == null) {
            throw new IllegalArgumentException("RTP sink required");
        }
        this.sink = sink;
        this.sourceId = sourceId;
        this.sequence = sequence;
    }

    /** @param senderMicros monotonic sender clock, NOT video PTS; @throws IOException on flush failure */
    public void beginAccessUnit(long senderMicros) throws IOException {
        flush();
        timestamp = senderMicros / 1000 * 90 + senderMicros % 1000 * 90 / 1000;
    }

    /** @param packet whole TS packet; @throws IOException on output failure */
    public void write(byte[] packet) throws IOException {
        if (packet == null || packet.length != MpegTsMuxer.PACKET_SIZE || packet[0] != 0x47) {
            throw new IllegalArgumentException("Expected 188-byte TS packet");
        }
        System.arraycopy(packet, 0, buffer, HEADER_SIZE + count * MpegTsMuxer.PACKET_SIZE, packet.length);
        count++;
        if (count == TS_PER_DATAGRAM) {
            flush();
        }
    }

    /** @throws IOException on output failure */
    public void flush() throws IOException {
        if (count == 0) {
            return;
        }
        buffer[0] = (byte)0x80;
        buffer[1] = 33;
        buffer[2] = (byte)(sequence >>> 8);
        buffer[3] = (byte)sequence++;
        putInt(4, (int)timestamp);
        putInt(8, sourceId);
        sink.send(buffer, HEADER_SIZE + count * MpegTsMuxer.PACKET_SIZE);
        count = 0;
    }

    private void putInt(int offset, int value) {
        buffer[offset] = (byte)(value >>> 24);
        buffer[offset + 1] = (byte)(value >>> 16);
        buffer[offset + 2] = (byte)(value >>> 8);
        buffer[offset + 3] = (byte)value;
    }
}
