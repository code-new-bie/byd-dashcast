package com.byd.dashcast.phase1;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * DCV1/1: one private USB TCP stream, version negotiation without video credentials.
 * ACK reports media/window state, never a decoded-frame count or human-observed picture.
 * @author Codex
 * @date 2026/10/11
 */
final class NoAuthVideoProtocol {
    static final int PORT = 31707;
    static final int HEADER_BYTES = 24;
    static final int ACK_BYTES = 16;
    static final int OFFER_BYTES = 80;
    static final int MAX_FRAME_BYTES = 0x200000;
    static final int MAX_TEST_FRAMES = 1800;
    static final int WIDTH = 640;
    static final int HEIGHT = 240;
    static final int FPS = 30;
    static final int START = 1;
    static final int FRAME = 2;
    static final int STOP = 3;
    static final int HELLO = 7;
    static final int HEARTBEAT = 8;
    private static final byte[] MAGIC = "DCV1".getBytes(StandardCharsets.US_ASCII);

    private NoAuthVideoProtocol() {
    }

    static byte[] hello() {
        ByteBuffer payload = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
        payload.putLong(0).put(MAGIC).putInt(1);
        return packet(HELLO, 0, 0, 0, payload.array());
    }

    static byte[] start() {
        ByteBuffer payload = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN);
        payload.putInt(640).putInt(240).putInt(WIDTH).putInt(HEIGHT).putInt(FPS).putInt(0);
        return packet(START, 0, 0, 0, payload.array());
    }

    static byte[] heartbeat(int counter) throws IOException {
        if (counter < 1 || counter > MAX_TEST_FRAMES + 4) {
            throw new IOException("DCV1_HEARTBEAT_LIMIT");
        }
        ByteBuffer payload = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN);
        payload.putLong(0).putLong(0).putInt(counter);
        return packet(HEARTBEAT, 0, 0, 0, payload.array());
    }

    static byte[] stop() {
        return packet(STOP, 0, 0, 0, new byte[0]);
    }

    static byte[] frame(byte[] annexB, int sequence, long ptsMillis) throws IOException {
        if (annexB == null || annexB.length == 0 || annexB.length > MAX_FRAME_BYTES
                || sequence < 1 || sequence > MAX_TEST_FRAMES || ptsMillis < 0 || ptsMillis > 0xffffffffL) {
            throw new IOException("DCV1_FRAME_LIMIT");
        }
        int types = inspectAnnexB(annexB);
        boolean idr = (types & (1 << 5)) != 0;
        if (sequence == 1 && (!idr || (types & (1 << 7)) == 0 || (types & (1 << 8)) == 0)) {
            throw new IOException("DCV1_FIRST_FRAME_NEEDS_SPS_PPS_IDR");
        }
        if (((types & (1 << 1)) == 0 && !idr) || (idr && (types & (1 << 1)) != 0)) {
            throw new IOException("DCV1_INVALID_ACCESS_UNIT");
        }
        return packet(FRAME, sequence, ptsMillis, idr ? 1 : 0, annexB);
    }

    private static byte[] packet(int type, int sequence, long pts, int flags, byte[] payload) {
        ByteBuffer result = ByteBuffer.allocate(HEADER_BYTES + payload.length).order(ByteOrder.BIG_ENDIAN);
        result.put(MAGIC).putInt(type).putInt(payload.length).putInt(sequence).putInt((int) pts).putInt(flags);
        result.put(payload);
        return result.array();
    }

    static void validateOffer(byte[] header, byte[] payload) throws IOException {
        validateEnvelope(header, 0x85, OFFER_BYTES, 0, 0);
        if (payload == null || payload.length != OFFER_BYTES) {
            throw new IOException("DCV1_BAD_OFFER_LENGTH");
        }
        for (byte value : payload) {
            if (value != 0) {
                throw new IOException("DCV1_UNEXPECTED_IDENTITY_OR_AUTH_OFFER");
            }
        }
    }

    static Reply reply(byte[] header, byte[] payload, int operation, int sequence, long pts) throws IOException {
        validateEnvelope(header, operation | 0x80, ACK_BYTES, sequence, pts);
        if (payload == null || payload.length != ACK_BYTES) {
            throw new IOException("DCV1_BAD_ACK_LENGTH");
        }
        ByteBuffer values = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        long result = Integer.toUnsignedLong(values.getInt());
        int flags = values.getInt();
        int frames = values.getInt();
        long lastPts = Integer.toUnsignedLong(values.getInt());
        if ((flags & ~31) != 0 || frames < 0 || frames > MAX_TEST_FRAMES) {
            throw new IOException("DCV1_INVALID_OR_FAILED_MEDIA_STATE");
        }
        if (result != 0) {
            throw new Rejected(operation, result, new Reply(flags, frames, lastPts));
        }
        if ((flags & 8) != 0) {
            throw new IOException("DCV1_INVALID_OR_FAILED_MEDIA_STATE cleanup_error=true");
        }
        if (operation == FRAME && (frames != sequence || lastPts != pts)) {
            throw new IOException("DCV1_SUBMISSION_READBACK_MISMATCH");
        }
        return new Reply(flags, frames, lastPts);
    }

    private static void validateEnvelope(byte[] raw, int type, int length, int sequence, long pts)
            throws IOException {
        if (raw == null || raw.length != HEADER_BYTES) {
            throw new IOException("DCV1_BAD_HEADER_LENGTH");
        }
        for (int index = 0; index < MAGIC.length; index++) {
            if (raw[index] != MAGIC[index]) {
                throw new IOException("DCV1_WRONG_FIRMWARE_PROTOCOL");
            }
        }
        ByteBuffer values = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        values.position(4);
        if (values.getInt() != type || values.getInt() != length || values.getInt() != sequence
                || Integer.toUnsignedLong(values.getInt()) != pts || values.getInt() != 0) {
            throw new IOException("DCV1_REPLY_ENVELOPE_MISMATCH");
        }
    }

    private static int inspectAnnexB(byte[] data) throws IOException {
        int types = 0;
        int offset = 0;
        int count = 0;
        while (offset < data.length) {
            int prefix = prefix(data, offset);
            if (prefix == 0 || offset + prefix >= data.length || ++count > 256) {
                throw new IOException("DCV1_MALFORMED_ANNEX_B");
            }
            int start = offset + prefix;
            int header = data[start] & 255;
            int type = header & 31;
            if ((header & 128) != 0 || type < 1 || type > 23) {
                throw new IOException("DCV1_INVALID_NAL_HEADER");
            }
            types |= 1 << type;
            offset = start + 1;
            while (offset < data.length && prefix(data, offset) == 0) {
                offset++;
            }
        }
        return types;
    }

    private static int prefix(byte[] data, int offset) {
        if (offset + 3 <= data.length && data[offset] == 0 && data[offset + 1] == 0) {
            if (data[offset + 2] == 1) {
                return 3;
            }
            if (offset + 4 <= data.length && data[offset + 2] == 0 && data[offset + 3] == 1) {
                return 4;
            }
        }
        return 0;
    }

    static final class Reply {
        final int flags;
        final int submissions;
        final long lastPts;

        Reply(int flags, int submissions, long lastPts) {
            this.flags = flags;
            this.submissions = submissions;
            this.lastPts = lastPts;
        }

        boolean windowReported() {
            return flags == 23 && submissions >= 2;
        }

        boolean cleanupReported() {
            return flags == 0;
        }

        String summary() {
            return "RECEIVER_MEDIA context=" + ((flags & 1) != 0)
                    + " submitted=" + submissions + " video_window=" + ((flags & 4) != 0)
                    + " screen_visible=" + ((flags & 16) != 0) + " last_pts_ms=" + lastPts
                    + "; NOT decoded/post-frame counts, manual picture observation required";
        }
    }

    /** A negative ACK is terminal; media state remains available for cleanup decisions. */
    static final class Rejected extends IOException {
        private static final long serialVersionUID = 1L;
        final int operation;
        final long code;
        final Reply evidence;

        Rejected(int operation, long code, Reply evidence) {
            super("DCV1_RECEIVER_REJECTED operation=" + operation + " code=" + code
                    + " stage=" + stage(code) + " flags=" + evidence.flags
                    + " submissions=" + evidence.submissions + " last_pts_ms=" + evidence.lastPts
                    + " cleanup_error=" + ((evidence.flags & 8) != 0));
            this.operation = operation;
            this.code = code;
            this.evidence = evidence;
        }

        private static String stage(long code) {
            if (code == 2) { return "AVC_PARSER"; }
            if (code == 3) { return "MEDIA_SUBMIT"; }
            if (code == 4) { return "VIDEO_WINDOW"; }
            if (code == 5) { return "MEDIA_STATE"; }
            return "LEGACY_OR_OTHER";
        }

        boolean terminalCleanupReported() {
            // Only the new FRAME stage codes guarantee cleanup BEFORE the negative ACK.
            return operation == FRAME && code >= 2 && code <= 5 && evidence.cleanupReported();
        }
    }
}
