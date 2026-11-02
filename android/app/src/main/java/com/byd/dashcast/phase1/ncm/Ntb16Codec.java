package com.byd.dashcast.phase1.ncm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Strict NTB16/no-CRC framing for the negotiated reference profile.
 * @author Codex
 * @date 2026/10/10
 */
public final class Ntb16Codec {
    private static final int HEADER_BYTES = 12;
    private static final int MAX_FRAMES = 32;
    private static final int MAX_ETHERNET_BYTES = 1514;

    private Ntb16Codec() {
    }

    public static byte[] encode(List<byte[]> frames, int sequence, int maxBytes,
            int ndpAlignment, int divisor, int remainder) throws IOException {
        if (frames == null || frames.isEmpty() || frames.size() > MAX_FRAMES
                || maxBytes < 2048 || maxBytes > 65535 || ndpAlignment < 4 || ndpAlignment > 64
                || divisor < 1 || divisor > 64 || remainder < 0 || remainder >= divisor) {
            throw new IOException("Unsupported NTB16 parameters");
        }
        int ndp = align(HEADER_BYTES, ndpAlignment);
        int ndpBytes = 8 + 4 * (frames.size() + 1);
        int[] offsets = new int[frames.size()];
        int end = ndp + ndpBytes;
        for (int index = 0; index < frames.size(); index++) {
            byte[] frame = frames.get(index);
            if (frame == null || frame.length < 14 || frame.length > MAX_ETHERNET_BYTES) {
                throw new IOException("Ethernet datagram outside negotiated MTU");
            }
            end += (remainder - end % divisor + divisor) % divisor;
            offsets[index] = end;
            end += frame.length;
        }
        end = align(end, 4);
        if (end > maxBytes) {
            throw new IOException("NTB16 exceeds device output limit");
        }
        byte[] result = new byte[end];
        signature(result, 0, "NCMH");
        put16(result, 4, HEADER_BYTES);
        put16(result, 6, sequence & 65535);
        put16(result, 8, end);
        put16(result, 10, ndp);
        signature(result, ndp, "NCM0");
        put16(result, ndp + 4, ndpBytes);
        for (int index = 0; index < frames.size(); index++) {
            byte[] frame = frames.get(index);
            put16(result, ndp + 8 + 4 * index, offsets[index]);
            put16(result, ndp + 10 + 4 * index, frame.length);
            System.arraycopy(frame, 0, result, offsets[index], frame.length);
        }
        return result;
    }

    public static List<byte[]> decode(byte[] data, int received) throws IOException {
        if (data == null || received < HEADER_BYTES || received > data.length
                || !matches(data, 0, "NCMH") || get16(data, 4) != HEADER_BYTES) {
            throw new IOException("Invalid NTB16 header");
        }
        int blockBytes = get16(data, 8);
        int ndp = get16(data, 10);
        if (blockBytes != received || ndp < HEADER_BYTES || ndp > blockBytes - 12
                || !matches(data, ndp, "NCM0") || get16(data, ndp + 6) != 0) {
            throw new IOException("Unsupported or truncated NTB16 block/NDP");
        }
        int ndpBytes = get16(data, ndp + 4);
        if (ndpBytes < 12 || ndpBytes % 4 != 0 || ndpBytes > blockBytes - ndp
                || (ndpBytes - 8) / 4 > MAX_FRAMES + 1) {
            throw new IOException("Invalid NDP16 length");
        }
        List<byte[]> frames = new ArrayList<>(MAX_FRAMES);
        int previousEnd = HEADER_BYTES;
        boolean terminated = false;
        for (int entry = ndp + 8; entry < ndp + ndpBytes; entry += 4) {
            int offset = get16(data, entry);
            int length = get16(data, entry + 2);
            if (offset == 0 && length == 0) {
                terminated = true;
                break;
            }
            if (offset < previousEnd || length < 14 || length > MAX_ETHERNET_BYTES
                    || offset > blockBytes - length
                    || offset < ndp + ndpBytes && offset + length > ndp) {
                throw new IOException("Invalid or overlapping Ethernet datagram");
            }
            frames.add(Arrays.copyOfRange(data, offset, offset + length));
            previousEnd = offset + length;
        }
        if (!terminated) {
            throw new IOException("NDP16 terminator missing");
        }
        return frames;
    }

    private static boolean matches(byte[] data, int offset, String value) {
        for (int index = 0; index < value.length(); index++) {
            if (data[offset + index] != value.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private static void signature(byte[] data, int offset, String value) {
        for (int index = 0; index < value.length(); index++) {
            data[offset + index] = (byte)value.charAt(index);
        }
    }

    private static int align(int value, int alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }

    private static int get16(byte[] data, int offset) {
        return (data[offset] & 255) | (data[offset + 1] & 255) << 8;
    }

    private static void put16(byte[] data, int offset, int value) {
        data[offset] = (byte)value;
        data[offset + 1] = (byte)(value >>> 8);
    }
}
