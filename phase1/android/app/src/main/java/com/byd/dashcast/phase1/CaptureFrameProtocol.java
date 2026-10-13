package com.byd.dashcast.phase1;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** DCS1, local authorized ADB stdout only: bounded AU and actual encoder PTS.
 * @author Codex
 * @date 2026/10/13
 */
final class CaptureFrameProtocol {
    static final int HEADER_BYTES = 24;
    private static final int MAGIC = 0x44435331;

    private CaptureFrameProtocol() {
    }

    static byte[] header(int bytes, long ptsMicros, boolean key) throws IOException {
        if (bytes == 0) {
            throw new IOException("DCS1_EMPTY_PICTURE");
        }
        requireValues(bytes, ptsMicros, key ? 1 : 0, 0);
        return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
                .putInt(MAGIC).putInt(bytes).putLong(ptsMicros).putInt(key ? 1 : 0).putInt(0).array();
    }

    static byte[] end(long ptsMicros) throws IOException {
        requireValues(0, ptsMicros, 2, 0);
        return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
                .putInt(MAGIC).putInt(0).putLong(ptsMicros).putInt(2).putInt(0).array();
    }

    static ByteBuffer parse(byte[] header) throws IOException {
        if (header == null || header.length != HEADER_BYTES) {
            throw new IOException("DCS1_HEADER_LENGTH");
        }
        ByteBuffer values = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        if (values.getInt() != MAGIC) {
            throw new IOException("DCS1_WRONG_CAPTURE_PROTOCOL");
        }
        requireValues(values.getInt(), values.getLong(), values.getInt(), values.getInt());
        values.position(4);
        return values;
    }

    private static void requireValues(int bytes, long ptsMicros, int flags, int reserved) throws IOException {
        boolean end = bytes == 0 && flags == 2;
        if ((!end && (bytes < 1 || flags < 0 || flags > 1)) || bytes > NoAuthVideoProtocol.MAX_FRAME_BYTES
                || ptsMicros < 0 || reserved != 0) {
            throw new IOException("DCS1_INVALID_FRAME_VALUES");
        }
    }
}
