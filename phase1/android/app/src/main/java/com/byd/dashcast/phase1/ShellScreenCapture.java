package com.byd.dashcast.phase1;

import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.view.Surface;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * Android-10 shell-only SurfaceFlinger capture, launched through normal authorized ADB.
 * Own temporary display and hardware Baseline encoder only; no MediaProjection, secure
 * display, APK privilege grant, adbd modification, route changes or persistence.
 * @author Codex
 * @date 2026/10/12
 */
public final class ShellScreenCapture {
    private ShellScreenCapture() {
    }

    public static void main(String[] args) {
        try {
            if (args.length != 1 || Process.myUid() != 2000 || Build.VERSION.SDK_INT != 29) {
                throw new IOException("SHELL_CAPTURE_REQUIRES_AUTHORIZED_UID2000_ANDROID10");
            }
            int seconds = Integer.parseInt(args[0]);
            if (seconds < 1 || seconds > 10) {
                throw new IOException("SHELL_CAPTURE_DURATION_LIMIT");
            }
            capture(seconds);
        } catch (Exception error) {
            System.err.println("SHELL_CAPTURE_FAILED " + error);
            for (Throwable suppressed : error.getSuppressed()) {
                System.err.println("SHELL_CAPTURE_CLEANUP_FAILED " + suppressed);
            }
            System.exit(1);
        }
    }

    private static MediaFormat format() {
        MediaFormat format = MediaFormat.createVideoFormat("video/avc", 640, 240);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 1_000_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
        format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4);
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L);
        return format;
    }

    private static MediaCodec encoder() throws IOException {
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder() || !info.isHardwareAccelerated()) {
                continue;
            }
            for (String mime : info.getSupportedTypes()) {
                if ("video/avc".equalsIgnoreCase(mime) && info.getCapabilitiesForType(mime).isFormatSupported(format())) {
                    System.err.println("SHELL_CAPTURE_CODEC " + info.getName() + " baseline level4 640x240");
                    return MediaCodec.createByCodecName(info.getName());
                }
            }
        }
        throw new IOException("SHELL_CAPTURE_NO_HARDWARE_BASELINE_ENCODER");
    }

    private static void capture(int seconds) throws Exception {
        MediaCodec codec = encoder();
        Surface surface = null;
        Mirror mirror = null;
        boolean started = false;
        Exception failure = null;
        try {
            codec.configure(format(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = codec.createInputSurface();
            codec.start();
            started = true;
            mirror = new Mirror(surface);
            stream(codec, seconds);
        } catch (Exception error) {
            failure = error;
            throw error;
        } finally {
            Exception cleanup = null;
            try {
                if (mirror != null) {
                    mirror.close();
                }
            } catch (Exception error) {
                cleanup = error;
            }
            try {
                if (started) {
                    codec.stop();
                }
            } catch (RuntimeException error) {
                cleanup = collect(cleanup, error);
            }
            try {
                codec.release();
            } catch (RuntimeException error) {
                cleanup = collect(cleanup, error);
            }
            try {
                if (surface != null) {
                    surface.release();
                }
            } catch (RuntimeException error) {
                cleanup = collect(cleanup, error);
            }
            if (cleanup != null) {
                if (failure != null) {
                    failure.addSuppressed(cleanup);
                } else {
                    throw cleanup;
                }
            }
        }
    }

    private static Exception collect(Exception first, Exception next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private static void stream(MediaCodec codec, int seconds) throws IOException {
        long stopAt = System.nanoTime() + seconds * 1_000_000_000L;
        long lastPts = -1;
        int frames = 0;
        boolean eos = false;
        byte[] config = new byte[0];
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (System.nanoTime() < stopAt + 2_000_000_000L) {
            if (!eos && System.nanoTime() >= stopAt) {
                codec.signalEndOfInputStream();
                eos = true;
            }
            int index = codec.dequeueOutputBuffer(info, 10_000);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                System.err.println("SHELL_CAPTURE_FORMAT " + codec.getOutputFormat());
                continue;
            }
            if (index < 0) {
                continue;
            }
            try {
                if (info.size > 0) {
                    ByteBuffer buffer = codec.getOutputBuffer(index);
                    if (buffer == null || info.size > NoAuthVideoProtocol.MAX_FRAME_BYTES) {
                        throw new IOException("SHELL_CAPTURE_BAD_BUFFER");
                    }
                    buffer.position(info.offset);
                    buffer.limit(info.offset + info.size);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        config = AvcByteStream.normalizeConfiguration(buffer);
                    } else {
                        if ((info.flags & MediaCodec.BUFFER_FLAG_PARTIAL_FRAME) != 0 || info.presentationTimeUs <= lastPts
                                || ++frames > 360) {
                            throw new IOException("SHELL_CAPTURE_PARTIAL_B_FRAME_OR_FRAME_LIMIT");
                        }
                        if ((info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                            if (config.length == 0) {
                                throw new IOException("SHELL_CAPTURE_MISSING_CONFIGURATION");
                            }
                            System.out.write(config);
                        }
                        System.out.write(AvcByteStream.normalize(buffer));
                        System.out.flush();
                        if (System.out.checkError()) {
                            throw new IOException("SHELL_CAPTURE_STDOUT_CLOSED");
                        }
                        lastPts = info.presentationTimeUs;
                    }
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (frames < 2) {
                        throw new IOException("SHELL_CAPTURE_FEWER_THAN_TWO_PICTURES");
                    }
                    System.err.println("SHELL_CAPTURE_COMPLETE frames=" + frames + "; display unverified");
                    return;
                }
            } finally {
                codec.releaseOutputBuffer(index, false);
            }
        }
        throw new IOException("SHELL_CAPTURE_EOS_TIMEOUT");
    }

    private static final class Mirror implements AutoCloseable {
        private final Class<?> control = Class.forName("android.view.SurfaceControl");
        private IBinder token;

        Mirror(Surface surface) throws Exception {
            Class<?> manager = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object global = manager.getMethod("getInstance").invoke(null);
            Object display = manager.getMethod("getDisplayInfo", int.class).invoke(global, 0);
            if (display == null) {
                throw new IOException("SHELL_CAPTURE_PRIMARY_DISPLAY_MISSING");
            }
            int width = display.getClass().getField("logicalWidth").getInt(display);
            int height = display.getClass().getField("logicalHeight").getInt(display);
            int stack = display.getClass().getField("layerStack").getInt(display);
            if (width < 1 || height < 1 || width > 4096 || height > 4096 || stack < 0) {
                throw new IOException("SHELL_CAPTURE_UNEXPECTED_DISPLAY");
            }
            token = (IBinder) control.getMethod("createDisplay", String.class, boolean.class)
                    .invoke(null, "dashcast-phase1-capture", false);
            if (token == null) {
                throw new IOException("SHELL_CAPTURE_DISPLAY_CREATE_FAILED");
            }
            try {
                control.getMethod("openTransaction").invoke(null);
                try {
                    method("setDisplaySurface", IBinder.class, Surface.class).invoke(null, token, surface);
                    method("setDisplayLayerStack", IBinder.class, int.class).invoke(null, token, stack);
                    method("setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class)
                            .invoke(null, token, 0, new Rect(0, 0, width, height), new Rect(0, 0, 640, 240));
                } finally {
                    control.getMethod("closeTransaction").invoke(null);
                }
                System.err.println("SHELL_CAPTURE_MIRROR " + width + "x" + height + " layerStack=" + stack);
            } catch (Exception error) {
                try {
                    close();
                } catch (Exception cleanup) {
                    error.addSuppressed(cleanup);
                }
                throw error;
            }
        }

        private Method method(String name, Class<?>... parameters) throws NoSuchMethodException {
            return control.getMethod(name, parameters);
        }

        @Override
        public void close() throws Exception {
            if (token != null) {
                IBinder owned = token;
                token = null;
                control.getMethod("destroyDisplay", IBinder.class).invoke(null, owned);
            }
        }
    }
}
