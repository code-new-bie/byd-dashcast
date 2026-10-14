package com.byd.dashcast.phase1;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/** Incremental DCS1 decoder. No video recording, aggregate buffer or unbounded queue.
 * @author Codex
 * @date 2026/10/13
 */
final class FramedCaptureReader implements AdbShellClient.ShellOutputHandler {
    private final VideoPatternProbe.Observer observer;
    private final Runnable endHandler;
    private final byte[] header = new byte[CaptureFrameProtocol.HEADER_BYTES];
    private int headerUsed;
    private byte[] frame;
    private int frameUsed;
    private long pts;
    private boolean key;
    private long lastPts = -1;
    private int frames;
    private long bytes;
    private boolean failed;
    private boolean finished;
    private boolean ended;

    FramedCaptureReader(VideoPatternProbe.Observer observer) {
        this(observer, () -> {});
    }

    FramedCaptureReader(VideoPatternProbe.Observer observer, Runnable endHandler) {
        this.observer = Objects.requireNonNull(observer);
        this.endHandler = Objects.requireNonNull(endHandler);
    }

    @Override
    public void onOutput(byte[] data, int length) throws IOException {
        if (data == null || length < 0 || length > data.length) {
            throw new IllegalArgumentException("Invalid DCS1 output chunk");
        }
        if (finished || failed || ended && length > 0) {
            throw new IOException("DCS1_CLOSED_OR_BYTE_LIMIT");
        }
        if (length > ProjectionLimits.MAX_CAPTURE_BYTES - bytes) {
            failed = true;
            throw new IOException("DCS1_BYTE_LIMIT");
        }
        bytes += length;
        try {
            int offset = 0;
            while (offset < length) {
                if (ended) {
                    throw new IOException("DCS1_DATA_AFTER_END");
                }
                if (frame == null) {
                    int count = Math.min(length - offset, header.length - headerUsed);
                    System.arraycopy(data, offset, header, headerUsed, count);
                    offset += count;
                    headerUsed += count;
                    if (headerUsed == header.length) {
                        beginFrame();
                    }
                } else {
                    int count = Math.min(length - offset, frame.length - frameUsed);
                    System.arraycopy(data, offset, frame, frameUsed, count);
                    offset += count;
                    frameUsed += count;
                    if (frameUsed == frame.length) {
                        observer.frame(frame, pts, key);
                        lastPts = pts;
                        frames++;
                        frame = null;
                        frameUsed = 0;
                        headerUsed = 0;
                    }
                }
            }
        } catch (IOException error) {
            failed = true;
            throw error;
        }
    }

    private void beginFrame() throws IOException {
        ByteBuffer values = CaptureFrameProtocol.parse(header);
        int size = values.getInt();
        pts = values.getLong();
        int flags = values.getInt();
        key = flags == 1;
        if (flags == 2) {
            if (pts != Math.max(0, lastPts)) {
                throw new IOException("DCS1_END_PTS_MISMATCH");
            }
            ended = true;
            headerUsed = 0;
            // End is emitted BEFORE shell display/codec teardown, so native STOP cannot
            // race the instrument's unchanged video-idle timeout during Android cleanup.
            endHandler.run();
            return;
        }
        if (frames >= ProjectionLimits.MAX_CAPTURE_FRAMES || pts <= lastPts) {
            throw new IOException("DCS1_FRAME_COUNT_OR_NON_MONOTONIC_ENCODER_PTS");
        }
        frame = new byte[size];
    }

    /** Only after successful shell EXIT. User stop may legitimately precede the first picture. */
    void finish(boolean stopped) throws IOException {
        if (failed || !ended || frame != null || headerUsed != 0 || (!stopped && frames < 2)) {
            throw new IOException("DCS1_INCOMPLETE_CAPTURE_OR_FEWER_THAN_TWO_PICTURES");
        }
        finished = true;
    }

    String statistics() {
        return "DCS1 capture_frames=" + frames + " bytes=" + bytes
                + " timestamps=encoder_PTS; NOT instrument decoded frames";
    }
}
