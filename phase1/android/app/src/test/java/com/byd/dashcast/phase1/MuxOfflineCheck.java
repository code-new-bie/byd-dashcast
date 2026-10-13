package com.byd.dashcast.phase1;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Offline integration harness. Input must contain AUD-delimited 30fps baseline H.264.
 * Output TS and RTP-reassembled TS can be independently decoded by FFmpeg.
 *
 * @author Codex
 * @date 2026/10/10
 */
public final class MuxOfflineCheck {
    private MuxOfflineCheck() {}

    /** @param args input H264, output TS, RTP-reassembled output TS; @throws Exception on failed check */
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("input.h264 output.ts reassembled.ts");
        }
        byte[] source = Files.readAllBytes(Path.of(args[0]));
        List<Integer> accessUnits = new ArrayList<>();
        for (int index = 0; index < source.length - 4; index++) {
            int prefix = source[index] == 0 && source[index + 1] == 0
                    ? source[index + 2] == 1 ? 3 : source[index + 2] == 0 && source[index + 3] == 1 ? 4 : 0 : 0;
            if (prefix > 0) {
                if ((source[index + prefix] & 31) == 9) {
                    accessUnits.add(index);
                }
                index += prefix - 1;
            }
        }
        if (accessUnits.size() != 30 || accessUnits.get(0) != 0) {
            throw new IllegalArgumentException("Expected exactly 30 AUD-delimited pictures, beginning at byte zero");
        }
        accessUnits.add(source.length);
        try (OutputStream output = Files.newOutputStream(Path.of(args[1]));
                OutputStream received = Files.newOutputStream(Path.of(args[2]))) {
            RtpTransportPacketizer rtp = new RtpTransportPacketizer((packet, length) -> {
                if ((length - 12) % 188 != 0 || length > 1328 || packet[1] != 33) {
                    throw new IllegalStateException("Invalid RTP MP2T datagram");
                }
                received.write(packet, 12, length - 12);
            }, 0x12345678, 0);
            MpegTsMuxer muxer = new MpegTsMuxer(packet -> {
                output.write(packet);
                rtp.write(packet);
            });
            for (int index = 0; index < 30; index++) {
                rtp.beginAccessUnit(index * 1_000_000L / 30);
                if (index % 3 == 0) {
                    muxer.writeTables();
                }
                byte[] frame = Arrays.copyOfRange(source, accessUnits.get(index), accessUnits.get(index + 1));
                muxer.writeAccessUnit(frame, 1_000_000L + index * 1_000_000L / 30, index == 0);
                rtp.flush();
            }
        }
        if (!Arrays.equals(Files.readAllBytes(Path.of(args[1])), Files.readAllBytes(Path.of(args[2])))) {
            throw new IllegalStateException("RTP reconstruction differs from stored TS");
        }
    }
}
