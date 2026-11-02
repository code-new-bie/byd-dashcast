package com.byd.dashcast.phase1;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Paced moving test pattern, hardware AVC only, with output format and measured PTS/frame counts.
 * This does not capture the head-unit screen and does not certify the instrument decoder.
 *
 * @author Codex
 * @date 2026/10/10
 */
public final class VideoPatternProbe {
    public static final int WIDTH = 1920;
    public static final int HEIGHT = 720;
    public static final int FRAME_RATE = 30;
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private final AtomicBoolean cancelled;
    private final Observer observer;
    private final int width;
    private final int height;
    private byte[] configuration = new byte[0];
    private int outputFrames;
    private long firstPts = -1;
    private long lastPts = -1;
    private long lastOutputNanos;
    private boolean outputEos;
    private String stage = "select";
    private boolean used;

    /** Receives progress and encoded complete access units. */
    public interface Observer {
        /** @param text bounded diagnostic description */
        void log(String text);
        /** @param data Annex-B frame; @param ptsMicros codec PTS; @param keyFrame random access;
         * @throws IOException on storage/network failure */
        void frame(byte[] data, long ptsMicros, boolean keyFrame) throws IOException;
        /** Same-worker maintenance during bounded encoder/socket waits. */
        default void tick() throws IOException {
        }
    }

    /** @param cancelled cancellation flag; @param observer receives encoder data */
    public VideoPatternProbe(AtomicBoolean cancelled, Observer observer) {
        this(cancelled, observer, WIDTH, HEIGHT);
    }

    /** Fixed inspected geometries only: legacy local probe or DCV1 small-window video. */
    public VideoPatternProbe(AtomicBoolean cancelled, Observer observer, int width, int height) {
        this.cancelled = Objects.requireNonNull(cancelled);
        this.observer = Objects.requireNonNull(observer);
        if (!(width == WIDTH && height == HEIGHT)
                && !(width == NoAuthVideoProtocol.WIDTH && height == NoAuthVideoProtocol.HEIGHT)) {
            throw new IllegalArgumentException("Uninspected encoder geometry");
        }
        this.width = width;
        this.height = height;
    }

    /** @param seconds 1 to 60; @throws IOException on codec/output failure */
    public void run(int seconds) throws IOException {
        if (seconds < 1 || seconds > 60) {
            throw new IllegalArgumentException("Duration must be 1..60 seconds");
        }
        if (used) {
            throw new IOException("Encoder probe cannot be reused");
        }
        used = true;
        MediaCodec codec = null;
        boolean started = false;
        IOException failure = null;
        try {
            MediaCodecInfo info = selectHardwareEncoder();
            observer.log("Selected codec=" + info.getName() + " hardware=" + info.isHardwareAccelerated()
                    + " input=recordable-Surface/EGL baseline-level4 (no CPU YUV queue)");
            phase("create");
            codec = MediaCodec.createByCodecName(info.getName());
            phase("configure");
            codec.configure(createFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            phase("create_input_surface");
            try (SurfacePatternRenderer renderer = new SurfacePatternRenderer(codec.createInputSurface(), width, height)) {
                phase("start");
                codec.start();
                started = true;
                long start = System.nanoTime();
                encode(codec, renderer, seconds);
                logOutput(start);
            }
        } catch (IOException error) {
            failure = error;
            observer.log("ENCODER_FAILED phase=" + stage + " reason=" + error.getMessage());
            throw error;
        } catch (RuntimeException error) {
            if (error instanceof MediaCodec.CodecException) {
                MediaCodec.CodecException codecError = (MediaCodec.CodecException) error;
                observer.log("ENCODER_CODEC_ERROR phase=" + stage + " code=0x"
                        + Integer.toHexString(codecError.getErrorCode())
                        + " diagnostic=" + codecError.getDiagnosticInfo()
                        + " recoverable=" + codecError.isRecoverable() + " transient=" + codecError.isTransient());
            }
            failure = new IOException("Encoder failed phase=" + stage + " type=" + error.getClass().getSimpleName(), error);
            throw failure;
        } finally {
            cleanup(codec, started, failure);
        }
    }

    private void phase(String value) {
        stage = value;
        observer.log("ENCODER_PHASE " + value);
    }

    private void cleanup(MediaCodec codec, boolean started, IOException failure) throws IOException {
        if (codec == null) {
            return;
        }
        IOException cleanupFailure = null;
        try {
            if (started) {
                codec.stop();
            }
        } catch (RuntimeException error) {
            cleanupFailure = new IOException("Encoder stop failed", error);
        } finally {
            try {
                codec.release();
            } catch (RuntimeException error) {
                if (cleanupFailure == null) {
                    cleanupFailure = new IOException("Encoder release failed", error);
                } else {
                    cleanupFailure.addSuppressed(error);
                }
            }
        }
        if (cleanupFailure != null) {
            observer.log("ENCODER_CLEANUP_FAILED; output does not certify a successful run");
            if (failure != null) {
                failure.addSuppressed(cleanupFailure);
            } else {
                throw cleanupFailure;
            }
        }
    }

    private void encode(MediaCodec codec, SurfacePatternRenderer renderer, int seconds) throws IOException {
        int queued = SurfaceEncoderPump.run(seconds, FRAME_RATE, cancelled, new SurfaceEncoderPump.Backend() {
            @Override
            public long nanoTime() {
                return System.nanoTime();
            }

            @Override
            public boolean drain() throws IOException {
                stage = "drain";
                VideoPatternProbe.this.drain(codec);
                return outputEos;
            }

            @Override
            public int outputFrames() {
                return outputFrames;
            }

            @Override
            public void render(int frame, long ptsMicros) throws IOException {
                stage = "render_swap";
                renderer.render(frame, ptsMicros);
            }

            @Override
            public void signalEndOfInput() {
                stage = "signal_eos";
                codec.signalEndOfInputStream();
            }

            @Override
            public void pause(long nanos) {
                LockSupport.parkNanos(nanos);
            }
        });
        observer.log("Input frames=" + queued + " cancellation=" + cancelled.get());
    }

    private void logOutput(long start) {
        double ptsRate = outputFrames > 1 && lastPts > firstPts
                ? (outputFrames - 1) * 1_000_000.0 / (lastPts - firstPts) : 0;
        double elapsed = lastOutputNanos > start ? (lastOutputNanos - start) / 1_000_000_000.0 : 0;
        observer.log(String.format(Locale.US,
                "ENCODER OUTPUT frames=%d firstPTS=%d lastPTS=%d ptsFPS=%.2f wallSeconds=%.3f wallFPS=%.2f EOS=%s",
                outputFrames, firstPts, lastPts, ptsRate, elapsed,
                elapsed > 0 ? outputFrames / elapsed : 0, outputEos));
        observer.log("ENCODER_STAGE ONLY: receiver submission and actual display require separate evidence.");
    }

    private void drain(MediaCodec codec) throws IOException {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int index = codec.dequeueOutputBuffer(info, 0);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat format = codec.getOutputFormat();
                observer.log("Output format=" + format);
                if (format.getInteger(MediaFormat.KEY_WIDTH) != width
                        || format.getInteger(MediaFormat.KEY_HEIGHT) != height) {
                    throw new IOException("Encoder output size mismatch");
                }
                configuration = readConfiguration(format);
                continue;
            }
            if (index < 0) {
                return;
            }
            try {
                if (info.size > 0) {
                    ByteBuffer bytes = codec.getOutputBuffer(index);
                    if (bytes == null) {
                        throw new IOException("Encoder returned null output buffer");
                    }
                    bytes.position(info.offset);
                    bytes.limit(info.offset + info.size);
                    consume(bytes, info);
                }
                outputEos |= (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            } finally {
                codec.releaseOutputBuffer(index, false);
            }
        }
    }

    private void consume(ByteBuffer bytes, MediaCodec.BufferInfo info) throws IOException {
        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            configuration = AvcByteStream.normalizeConfiguration(bytes);
            return;
        }
        byte[] annexB = AvcByteStream.normalize(bytes);
        if ((info.flags & MediaCodec.BUFFER_FLAG_PARTIAL_FRAME) != 0) {
            throw new IOException("Partial codec access unit not supported");
        }
        boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        if (key) {
            if (configuration.length == 0) {
                throw new IOException("No AVC SPS/PPS available for random access");
            }
            byte[] joined = new byte[configuration.length + annexB.length];
            System.arraycopy(configuration, 0, joined, 0, configuration.length);
            System.arraycopy(annexB, 0, joined, configuration.length, annexB.length);
            annexB = joined;
        }
        if (lastPts >= 0 && info.presentationTimeUs <= lastPts) {
            throw new IOException("Non-monotonic PTS; expected zero B-frames");
        }
        observer.frame(annexB, info.presentationTimeUs, key);
        if (firstPts < 0) {
            firstPts = info.presentationTimeUs;
        }
        lastPts = info.presentationTimeUs;
        outputFrames++;
        lastOutputNanos = System.nanoTime();
    }

    private MediaFormat createFormat() {
        MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, width == WIDTH ? 6_000_000 : 1_000_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
        // 1920x720/30 exceeds AVC level 3.2's macroblock limit. Baseline forbids B-frames;
        // do not send the additional max-bframes vendor option that screenrecord does not need.
        format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4);
        return format;
    }

    private MediaCodecInfo selectHardwareEncoder() throws IOException {
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder() || !info.isHardwareAccelerated()) {
                continue;
            }
            for (String type : info.getSupportedTypes()) {
                if (!MIME.equalsIgnoreCase(type)) {
                    continue;
                }
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                if (caps.isFormatSupported(createFormat())) {
                    return info;
                }
            }
        }
        throw new IOException("No hardware AVC encoder supporting " + width + "x" + height
                + "/30 baseline level4 Surface");
    }

    private static byte[] readConfiguration(MediaFormat format) {
        ByteArrayOutputStream result = new ByteArrayOutputStream(128);
        for (String key : new String[] {"csd-0", "csd-1"}) {
            if (!format.containsKey(key)) {
                continue;
            }
            ByteBuffer buffer = format.getByteBuffer(key);
            if (buffer != null && buffer.hasRemaining()) {
                byte[] bytes = AvcByteStream.normalizeConfiguration(buffer);
                result.write(bytes, 0, bytes.length);
            }
        }
        return result.toByteArray();
    }

}
