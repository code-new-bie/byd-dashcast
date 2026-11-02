package com.byd.dashcast.phase1;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;

/**
 * Bounded progressive P/I AVC framing for screenrecord output, with or without AUD NALs.
 * ADB chunk boundaries are not video frame boundaries. Reject B/SP/SI/data-partition streams.
 *
 * @author Codex
 * @date 2026/10/11
 */
final class AnnexBAccessUnitReader implements AdbShellClient.ShellOutputHandler {
    private static final byte[] PREFIX = {0, 0, 0, 1};
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int MAX_FRAME = NoAuthVideoProtocol.MAX_FRAME_BYTES;
    private final Observer observer;
    private final ByteArrayOutputStream nal = new ByteArrayOutputStream(16 * 1024);
    private final ByteArrayOutputStream unit = new ByteArrayOutputStream(64 * 1024);
    private int zeros;
    private int inputBytes;
    private int frames;
    private int unitNals;
    private boolean started;
    private boolean hasSlice;
    private boolean keyFrame;
    private boolean finished;
    private boolean failed;

    AnnexBAccessUnitReader(Observer observer) {
        this.observer = Objects.requireNonNull(observer);
    }

    @Override
    public void onOutput(byte[] data, int length) throws IOException {
        if (data == null || length < 0 || length > data.length) {
            throw new IllegalArgumentException("Invalid Annex-B output chunk");
        }
        if (finished || failed) {
            throw new IOException("AVC stream already finished/failed");
        }
        if (length > MAX_BYTES - inputBytes) {
            failed = true;
            throw new IOException("Screenrecord validation byte limit exceeded");
        }
        inputBytes += length;
        try {
            for (int index = 0; index < length; index++) {
                consumeByte(data[index] & 255);
            }
        } catch (IOException error) {
            failed = true;
            throw error;
        }
    }

    private void consumeByte(int value) throws IOException {
        if (value == 0) {
            if (++zeros > MAX_FRAME) {
                throw new IOException("Malformed/oversize zero run in AVC stream");
            }
            return;
        }
        if (value == 1 && zeros >= 2) {
            if (started) {
                consumeNal();
            }
            started = true;
            zeros = 0;
            return;
        }
        if (!started) {
            throw new IOException("Expected Annex-B screenrecord output, not text/another format");
        }
        if (zeros > MAX_FRAME - nal.size() - 1) {
            throw new IOException("AVC NAL limit exceeded");
        }
        while (zeros > 0) {
            nal.write(0);
            zeros--;
        }
        nal.write(value);
    }

    private void consumeNal() throws IOException {
        byte[] bytes = nal.toByteArray();
        nal.reset();
        if (bytes.length == 0) {
            throw new IOException("Empty AVC NAL");
        }
        int type = bytes[0] & 31;
        if ((bytes[0] & 128) != 0 || type < 1 || type > 12 || type >= 2 && type <= 4) {
            throw new IOException("Unsupported AVC NAL type=" + type);
        }
        boolean slice = type == 1 || type == 5;
        if (slice) {
            SliceBits bits = new SliceBits(bytes);
            int firstMb = bits.unsignedExpGolomb();
            int sliceType = bits.unsignedExpGolomb();
            if (firstMb > 8191 || sliceType > 9 || sliceType % 5 != 0 && sliceType % 5 != 2) {
                throw new IOException("Only bounded progressive P/I AVC slices are supported");
            }
            if (hasSlice && firstMb == 0) {
                emit();
            } else if (!hasSlice && firstMb != 0) {
                throw new IOException("AVC picture missing its first slice");
            }
            if (hasSlice && keyFrame != (type == 5)) {
                throw new IOException("Mixed IDR/non-IDR slices within one picture");
            }
        } else if (hasSlice && (type == 6 || type == 7 || type == 8 || type == 9)) {
            emit();
        }
        if (++unitNals > 256 || bytes.length > MAX_FRAME - PREFIX.length - unit.size()) {
            throw new IOException("AVC access-unit limit exceeded");
        }
        unit.write(PREFIX, 0, PREFIX.length);
        unit.write(bytes, 0, bytes.length);
        if (slice) {
            hasSlice = true;
            keyFrame = type == 5;
        }
    }

    private void emit() throws IOException {
        if (!hasSlice || frames >= NoAuthVideoProtocol.MAX_TEST_FRAMES) {
            throw new IOException("AVC picture/frame-count limit");
        }
        observer.frame(unit.toByteArray(), keyFrame);
        frames++;
        unit.reset();
        unitNals = 0;
        hasSlice = false;
        keyFrame = false;
    }

    /** Call only after a successful shell-v2 EXIT, never after transport/capture failure. */
    void finish() throws IOException {
        if (failed) {
            throw new IOException("Cannot finish a failed AVC stream");
        }
        if (finished) {
            return;
        }
        try {
            if (!started || nal.size() == 0) {
                throw new IOException("No complete final AVC NAL");
            }
            consumeNal();
            // trailing_zero_8bits are not NAL payload. Non-VCL trailer may follow the last picture.
            zeros = 0;
            if (hasSlice) {
                emit();
            }
            if (frames == 0) {
                throw new IOException("Screenrecord produced no AVC pictures");
            }
            finished = true;
        } catch (IOException error) {
            failed = true;
            throw error;
        }
    }

    int frameCount() {
        return frames;
    }

    int byteCount() {
        return inputBytes;
    }

    interface Observer {
        void frame(byte[] data, boolean keyFrame) throws IOException;
    }

    /** Reads only the two required slice prefix fields, skipping RBSP emulation-prevention bytes. */
    private static final class SliceBits {
        private final byte[] bytes;
        private int offset = 1;
        private int bitsLeft;
        private int current;
        private int zeroBytes;

        private SliceBits(byte[] bytes) {
            this.bytes = bytes;
        }

        private int unsignedExpGolomb() throws IOException {
            int leading = 0;
            while (bit() == 0) {
                if (++leading > 30) {
                    throw new IOException("Malformed AVC Exp-Golomb prefix");
                }
            }
            int suffix = 0;
            for (int index = 0; index < leading; index++) {
                suffix = suffix << 1 | bit();
            }
            return (1 << leading) - 1 + suffix;
        }

        private int bit() throws IOException {
            if (bitsLeft == 0) {
                if (offset < bytes.length && zeroBytes >= 2 && (bytes[offset] & 255) == 3) {
                    offset++;
                    zeroBytes = 0;
                }
                if (offset >= bytes.length) {
                    throw new IOException("Truncated AVC slice header");
                }
                current = bytes[offset++] & 255;
                zeroBytes = current == 0 ? zeroBytes + 1 : 0;
                bitsLeft = 8;
            }
            return current >>> --bitsLeft & 1;
        }
    }
}
