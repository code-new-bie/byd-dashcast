package com.byd.dashcast.phase1;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Validates Annex-B or converts four-byte length-prefixed AVC access units.
 *
 * @author Codex
 * @date 2026/10/10
 */
public final class AvcByteStream {
    private AvcByteStream() {}

    /** @param data Annex-B access unit; @return NALs except existing AUDs, before the muxer adds one AUD */
    public static byte[] withoutAud(byte[] data) {
        if (data == null || prefixLength(data, 0) == 0) {
            throw new IllegalArgumentException("Annex-B access unit required");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(data.length);
        int cursor = 0;
        while (cursor < data.length) {
            int prefix = prefixLength(data, cursor);
            if (prefix == 0 || cursor + prefix >= data.length) {
                throw new IllegalArgumentException("Truncated Annex-B NAL");
            }
            int next = cursor + prefix + 1;
            while (next < data.length && prefixLength(data, next) == 0) {
                next++;
            }
            if ((data[cursor + prefix] & 31) != 9) {
                output.write(data, cursor, next - cursor);
            }
            cursor = next;
        }
        return output.toByteArray();
    }

    private static int prefixLength(byte[] data, int offset) {
        if (offset + 3 > data.length || data[offset] != 0 || data[offset + 1] != 0) {
            return 0;
        }
        if (data[offset + 2] == 1) {
            return 3;
        }
        return offset + 4 <= data.length && data[offset + 2] == 0 && data[offset + 3] == 1 ? 4 : 0;
    }

    /** AVCDecoderConfigurationRecord or ordinary Annex-B/AVCC codec configuration. */
    public static byte[] normalizeConfiguration(ByteBuffer input) {
        if (input == null || !input.hasRemaining()) {
            throw new IllegalArgumentException("Empty AVC configuration");
        }
        ByteBuffer source = input.duplicate();
        byte[] data = new byte[source.remaining()];
        source.get(data);
        if (data.length < 7 || data[0] != 1 || (data[4] & 0xfc) != 0xfc) {
            return normalize(input);
        }
        if ((data[4] & 3) != 3 || (data[5] & 0xe0) != 0xe0) {
            throw new IllegalArgumentException("Only four-byte AVC length configuration supported");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(data.length + 16);
        int offset = 6;
        int spsCount = data[5] & 31;
        if (spsCount == 0) {
            throw new IllegalArgumentException("SPS missing in AVC configuration");
        }
        for (int index = 0; index < spsCount; index++) {
            offset = appendConfigurationNal(data, offset, 7, output);
        }
        if (offset >= data.length || data[offset] == 0) {
            throw new IllegalArgumentException("PPS missing in AVC configuration");
        }
        int ppsCount = data[offset++] & 255;
        for (int index = 0; index < ppsCount; index++) {
            offset = appendConfigurationNal(data, offset, 8, output);
        }
        if (offset != data.length) {
            throw new IllegalArgumentException("Unsupported AVC configuration extension");
        }
        return output.toByteArray();
    }

    private static int appendConfigurationNal(byte[] data, int offset, int type,
            ByteArrayOutputStream output) {
        if (offset > data.length - 2) {
            throw new IllegalArgumentException("Truncated AVC configuration NAL length");
        }
        int length = (data[offset] & 255) << 8 | (data[offset + 1] & 255);
        offset += 2;
        if (length < 1 || length > data.length - offset || (data[offset] & 128) != 0
                || (data[offset] & 31) != type) {
            throw new IllegalArgumentException("Invalid AVC configuration NAL");
        }
        output.write(0);
        output.write(0);
        output.write(0);
        output.write(1);
        output.write(data, offset, length);
        return offset + length;
    }

    /** @param input encoded bytes; @return Annex-B copy; @throws IllegalArgumentException on malformed input */
    public static byte[] normalize(ByteBuffer input) {
        if (input == null || !input.hasRemaining()) {
            throw new IllegalArgumentException("Empty AVC input");
        }
        ByteBuffer source = input.duplicate();
        byte[] data = new byte[source.remaining()];
        source.get(data);
        if (data.length >= 4 && data[0] == 0 && data[1] == 0
                && (data[2] == 1 || (data[2] == 0 && data[3] == 1))) {
            return data;
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(data.length + 16);
        int cursor = 0;
        while (cursor < data.length) {
            if (data.length - cursor < 4) {
                throw new IllegalArgumentException("Truncated AVC length");
            }
            long length = ((long)(data[cursor] & 0xff) << 24) | ((long)(data[cursor + 1] & 0xff) << 16)
                    | ((data[cursor + 2] & 0xff) << 8) | (data[cursor + 3] & 0xff);
            cursor += 4;
            if (length == 0 || length > data.length - cursor) {
                throw new IllegalArgumentException("Invalid AVC NAL length");
            }
            output.write(0);
            output.write(0);
            output.write(0);
            output.write(1);
            output.write(data, cursor, (int)length);
            cursor += (int)length;
        }
        return output.toByteArray();
    }
}
