package com.byd.dashcast.phase1;

import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.view.Surface;

import java.io.IOException;
import java.io.OutputStream;
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
    /**
     * Keep a static Surface input alive at the requested 30 FPS when the display has no new
     * buffer. This is a codec-side repeat of the last picture, not a fabricated motion frame;
     * The input FPS limit and transport pacer still bound the stream; source PTS is preserved.
     */
    static final long REPEAT_PREVIOUS_FRAME_AFTER_US = ProjectionLimits.TARGET_FRAME_INTERVAL_US;

    private ShellScreenCapture() {
    }

    private static final class CleanupProof {
        private boolean captureClosed = true;
        private boolean controlClosed = true;

        private boolean complete() { return captureClosed && controlClosed; }
    }

    public static void main(String[] args) {
        // Context/LoadedApk initialization on the OEM ROM can print hot-fix diagnostics to System.out.
        // Binary DCS1/AVC uses FileDescriptor.out directly and must never share that PrintStream.
        ShellCaptureOutput.isolateFrameworkDiagnostics();
        boolean captureMayOwnResources = false;
        CleanupProof proof = new CleanupProof();
        try {
            String mirrorTicket = DuplexTicket.read(args);
            args = DuplexTicket.without(args);
            CaptureOptions options = CaptureOptions.fromShellArguments(args);
            ProjectionTarget target = ProjectionTarget.fromShellArguments(args);
            boolean live = args.length >= 2 && "live".equals(args[1]);
            boolean remoteProbe = args.length >= 2 && "remote".equals(args[1]);
            if ((!live && !remoteProbe && args.length != 1)
                    || Process.myUid() != 2000 || Build.VERSION.SDK_INT != 29) {
                throw new IOException("SHELL_CAPTURE_REQUIRES_AUTHORIZED_UID2000_ANDROID10");
            }
            int seconds = Integer.parseInt(args[0]);
            if (live) { ContinuousSessionPolicy.requireCaptureSeconds(seconds); }
            if (!live && (seconds < 1 || seconds > 10)) {
                throw new IOException("SHELL_CAPTURE_DURATION_LIMIT");
            }
            // Every check above is pure preflight. From this point onward owned
            // framework/control/capture resources must be released by their owner.
            captureMayOwnResources = true;
            if ((target.isRemote() || target.isNamed()) && Looper.myLooper() == null) {
                // app_process invokes this main directly; ActivityThread and framework Handlers need a Looper.
                Looper.prepareMainLooper();
                System.err.println("SHELL_CAPTURE_REMOTE_LOOPER_READY");
            }
            if (live) {
                proof.controlClosed = false;
                CaptureStopControl control = new CaptureStopControl(System.in);
                Exception captureFailure = null;
                try {
                    control.start();
                    capture(seconds, control, options, target, mirrorTicket, proof);
                } catch (Exception error) {
                    captureFailure = error;
                    throw error;
                } finally {
                    try {
                        control.close();
                        proof.controlClosed = true;
                    } catch (Exception cleanup) {
                        if (captureFailure != null) { captureFailure.addSuppressed(cleanup); }
                        else { throw cleanup; }
                    }
                }
            } else {
                capture(seconds, null, options, target, null, proof);
            }
            if (!proof.complete()) { throw new IOException("SHELL_CAPTURE_CLEANUP_UNCONFIRMED"); }
            System.err.println("SHELL_CAPTURE_RESOURCES_RELEASED");
            System.err.flush();
            // ActivityThread/Looper/Binder initialization can leave non-daemon framework
            // threads after every owned resource is closed. Emit the real shell-v2 EXIT.
            System.exit(0);
        } catch (Exception error) {
            System.err.println("SHELL_CAPTURE_FAILED " + error);
            for (Throwable suppressed : error.getSuppressed()) {
                System.err.println("SHELL_CAPTURE_CLEANUP_FAILED " + suppressed);
            }
            if (!captureMayOwnResources || proof.complete()) {
                System.err.println("SHELL_CAPTURE_RESOURCES_RELEASED phase="
                        + (captureMayOwnResources ? "owned_closed" : "preflight created=false"));
            }
            System.exit(1);
        }
    }

    private static MediaFormat format() { return format(true); }

    private static MediaFormat format(boolean codecRepeat) {
        MediaFormat format = MediaFormat.createVideoFormat("video/avc", NoAuthVideoProtocol.WIDTH,
                NoAuthVideoProtocol.HEIGHT);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, NoAuthVideoProtocol.FPS);
        // Throttle Surface input before encoding; dropping encoded P frames breaks their reference chain.
        format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, (float)NoAuthVideoProtocol.FPS);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
        format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4);
        if (codecRepeat) { format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_PREVIOUS_FRAME_AFTER_US); }
        return format;
    }

    private static MediaCodec encoder() throws IOException {
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder() || !info.isHardwareAccelerated()) {
                continue;
            }
            for (String mime : info.getSupportedTypes()) {
                if ("video/avc".equalsIgnoreCase(mime) && info.getCapabilitiesForType(mime).isFormatSupported(format())) {
                    System.err.println("SHELL_CAPTURE_CODEC " + info.getName() + " baseline level4 "
                            + NoAuthVideoProtocol.WIDTH + "x" + NoAuthVideoProtocol.HEIGHT + "/30");
                    return MediaCodec.createByCodecName(info.getName());
                }
            }
        }
        throw new IOException("SHELL_CAPTURE_NO_HARDWARE_BASELINE_ENCODER");
    }

    private static void capture(int seconds, CaptureStopControl control, CaptureOptions options,
            ProjectionTarget target, String mirrorTicket, CleanupProof proof) throws Exception {
        proof.captureClosed = false;
        MediaCodec codec = null;
        Surface surface = null;
        AutoCloseable display = null;
        CenterFrameRenderer center = null;
        RemoteCaptureSession remote = null;
        boolean started = false;
        Exception failure = null;
        try {
            // Encoder discovery/creation may itself fail. It belongs inside the
            // same cleanup scope, rather than stranding a no-resource failure.
            codec = encoder();
            codec.configure(format(!options.contentViewport() && !options.centerWindow()), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = codec.createInputSurface();
            codec.start();
            started = true;
            Surface captureSurface = surface;
            int logicalWidth = NoAuthVideoProtocol.WIDTH;
            int logicalHeight = NoAuthVideoProtocol.HEIGHT;
            ProjectionEntryRouter.Selection selection = target.isRemote()
                    ? ProjectionEntryRouter.resolve(RemoteDisplaySession.shellContext(), target,
                        line -> System.err.println("SHELL_CAPTURE_" + line)) : null;
            BusinessDisplayProfile business = selection != null && selection.getNative() == null && mirrorTicket != null
                ? BusinessDisplayProfile.read(RemoteDisplaySession.shellContext()) : null;
            WorkspaceDisplayProfile workspace = business != null && target.isWorkspace()
                    ? WorkspaceDisplayProfile.forMode(options.centerWindow()) : null;
            if (options.contentViewport()) {
                ContentViewport viewport = options.centerWindow() ? ContentViewport.center() : ContentViewport.full();
                if (target.isNamed() && mirrorTicket != null) {
                    viewport = viewport.withSourceSize(ExternalDisplayWire.WIDTH, ExternalDisplayWire.HEIGHT);
                }
                if (business != null) {
                    // Keep the business application's original main-screen layout. The
                    // instrument's independent FIT/CROP mapping must not reflow that UI.
                    viewport = workspace != null ? ContentViewport.forWorkspace(workspace, options.centerWindow())
                            : ContentViewport.forBusiness(business, options.centerWindow());
                }
                center = new CenterFrameRenderer(surface, viewport, options.mode() == CaptureOptions.Mode.CROP);
                captureSurface = center.inputSurface();
                logicalWidth = viewport.getSourceWidth();
                logicalHeight = viewport.getSourceHeight();
            } else if (options.centerWindow()) {
                center = new CenterFrameRenderer(surface);
                captureSurface = center.inputSurface();
                logicalWidth = 1280;
            }
            if (target.isNamed()) {
                remote = mirrorTicket != null ? new ExternalCaptureSession(captureSurface, mirrorTicket)
                        : new NamedDisplaySession(captureSurface, logicalWidth, logicalHeight);
                display = remote;
            } else if (target.isRemote()) {
                if (selection != null && selection.getNative() != null) {
                    remote = new RemoteDisplaySession(captureSurface, target, logicalWidth, logicalHeight,
                            options, selection.getNative());
                } else if (mirrorTicket != null) {
                    ProjectionEntryRouter.Selection entry = selection;
                    if (center == null) { throw new IOException("MIRROR_CONTENT_RENDERER_REQUIRED"); }
                    remote = new MirrorAppSession(captureSurface, entry.getEntry(), logicalWidth, logicalHeight,
                            mirrorTicket, center, workspace != null ? workspace.getDensity() : business.getDensity());
                } else {
                    remote = new RemoteDisplaySession(captureSurface, target, logicalWidth, logicalHeight, options);
                }
                display = remote;
            } else {
                display = new Mirror(captureSurface, options, logicalWidth, logicalHeight);
            }
            stream(codec, seconds, control, center, remote);
        } catch (Exception error) {
            failure = error;
            throw error;
        } finally {
            Exception cleanup = null;
            try {
                if (display != null) {
                    display.close();
                }
            } catch (Exception error) {
                cleanup = error;
            }
            try {
                if (center != null) { center.close(); }
            } catch (Exception error) {
                cleanup = collect(cleanup, error);
            }
            try {
                if (started && codec != null) {
                    codec.stop();
                }
            } catch (RuntimeException error) {
                cleanup = collect(cleanup, error);
            }
            try {
                if (codec != null) { codec.release(); }
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
            } else if (failure == null || failure.getSuppressed().length == 0) {
                // The main owner emits the proof only AFTER stdin/control closes.
                // A later control-close failure must never leave an earlier proof.
                proof.captureClosed = true;
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

    private static void stream(MediaCodec codec, int seconds, CaptureStopControl control,
            CenterFrameRenderer center, RemoteCaptureSession remote) throws Exception {
        long began = System.nanoTime();
        long drainUntil = 0;
        boolean unlimited = control != null && ContinuousSessionPolicy.unlimited(seconds);
        OutputStream stdout = ShellCaptureOutput.videoStream();
        long lastPts = -1;
        long lastIdle = System.nanoTime();
        long frames = 0;
        boolean eos = false;
        byte[] config = new byte[0];
        OutputFrameRateEvidence frameRateEvidence = new OutputFrameRateEvidence(NoAuthVideoProtocol.FPS);
        long nextRollingGateFrame = OutputFrameRateEvidence.MIN_STABLE_FRAMES;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (!eos || System.nanoTime() - drainUntil < 0) {
            if (!eos && (ContinuousSessionPolicy.timedOut(seconds, began, System.nanoTime())
                    || control != null && control.stopped())) {
                codec.signalEndOfInputStream();
                eos = true;
                drainUntil = System.nanoTime() + 2_000_000_000L;
            }
            if (!eos && remote != null) { remote.update(codec); }
            if (!eos && center != null) { center.renderIfAvailable(remote == null || remote.renderAllowed(),
                    remote == null ? 0L : remote.renderSourceEpochNanos(),
                    remote == null || remote.advanceSourceAllowed()); }
            // Source handoff may deliberately discard encoder output until a fresh IDR.
            // Keep the existing same-stream lease alive without forwarding that stale data.
            if (control != null && System.nanoTime() - lastIdle >= 250_000_000L) {
                stdout.write(CaptureFrameProtocol.idle(Math.max(0, lastPts)));
                stdout.flush();
                lastIdle = System.nanoTime();
            }
            int index = codec.dequeueOutputBuffer(info, center == null || eos ? 10_000 : center.outputPollTimeoutUs());
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat output = codec.getOutputFormat();
                verifyOutputFormat(output);
                System.err.println("SHELL_CAPTURE_FORMAT " + output
                        + " target=" + NoAuthVideoProtocol.WIDTH + "x" + NoAuthVideoProtocol.HEIGHT
                        + "/" + NoAuthVideoProtocol.FPS
                        + " repeat_previous_frame_after_us=" + REPEAT_PREVIOUS_FRAME_AFTER_US);
                continue;
            }
            if (index < 0) {
                if (control != null && System.nanoTime() - lastIdle >= 250_000_000L) {
                    stdout.write(CaptureFrameProtocol.idle(Math.max(0, lastPts)));
                    stdout.flush();
                    lastIdle = System.nanoTime();
                }
                continue;
            }
            try {
                if (remote != null && (eos || (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)) {
                    // Do not forward queued pictures after stop, but still consume the EOS flag
                    // below. A continue here would lose a nonempty final EOS and fake a timeout.
                    info.size = 0;
                }
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
                        boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                        if (remote != null && (eos || !remote.acceptFrame(info.presentationTimeUs, key))) {
                            continue;
                        }
                        frames = ContinuousSessionPolicy.add(frames, 1);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_PARTIAL_FRAME) != 0 || info.presentationTimeUs <= lastPts
                                || !unlimited && frames > (control == null ? 360 : ProjectionLimits.MAX_CAPTURE_FRAMES)) {
                            throw new IOException("SHELL_CAPTURE_PARTIAL_B_FRAME_OR_FRAME_LIMIT");
                        }
                        long frameNanos = System.nanoTime();
                        frameRateEvidence.record(info.presentationTimeUs, frameNanos);
                        if (frameRateEvidence.sampleFrames() >= nextRollingGateFrame) {
                            OutputFrameRateEvidence.Result rolling = frameRateEvidence.result();
                            System.err.println(rolling.logLine() + " sample=rolling diagnostic_only=true");
                            nextRollingGateFrame = ContinuousSessionPolicy.add(nextRollingGateFrame,
                                    OutputFrameRateEvidence.MIN_STABLE_FRAMES);
                        }
                        byte[] picture = AvcByteStream.normalize(buffer);
                        if (key) {
                            if (config.length == 0) {
                                throw new IOException("SHELL_CAPTURE_MISSING_CONFIGURATION");
                            }
                            if (config.length > NoAuthVideoProtocol.MAX_FRAME_BYTES - picture.length) {
                                throw new IOException("SHELL_CAPTURE_CONFIG_FRAME_LIMIT");
                            }
                            byte[] combined = new byte[config.length + picture.length];
                            System.arraycopy(config, 0, combined, 0, config.length);
                            System.arraycopy(picture, 0, combined, config.length, picture.length);
                            picture = combined;
                        }
                        if (control != null) {
                            stdout.write(CaptureFrameProtocol.header(picture.length, info.presentationTimeUs, key));
                        }
                        stdout.write(picture);
                        stdout.flush();
                        lastIdle = System.nanoTime();
                        lastPts = info.presentationTimeUs;
                    }
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (unlimited && !control.stopped()) {
                        throw new IOException("SHELL_CAPTURE_UNEXPECTED_EOS_IN_UNLIMITED_SESSION");
                    }
                    if (frames < 2 && !(control != null && control.stopped())) {
                        throw new IOException("SHELL_CAPTURE_FEWER_THAN_TWO_PICTURES");
                    }
                    OutputFrameRateEvidence.Result evidence = frameRateEvidence.result();
                    // Display refresh, codec batching and ACK backpressure can jitter timestamps.
                    // Keep the measured result without terminating valid H.264 pictures.
                    System.err.println(evidence.logLine() + " sample=final diagnostic_only=true");
                    if (control != null) {
                        stdout.write(CaptureFrameProtocol.end(Math.max(0, lastPts)));
                        stdout.flush();
                    }
                    System.err.println("SHELL_CAPTURE_COMPLETE frames=" + frames
                            + " pts_fps_x100=" + evidence.ptsFpsX100
                            + " wall_fps_x100=" + evidence.wallFpsX100
                            + " min_pts_interval_us=" + evidence.minPtsIntervalUs
                            + " max_pts_interval_us=" + evidence.maxPtsIntervalUs
                            + " average_pts_interval_us=" + evidence.averagePtsIntervalUs
                            + "; encoder_output_measured, instrument_display_unverified");
                    return;
                }
            } finally {
                codec.releaseOutputBuffer(index, false);
            }
        }
        throw new IOException("SHELL_CAPTURE_EOS_TIMEOUT");
    }

    /**
     * The requested format is only a negotiation input. Validate the first actual output format
     * before forwarding any access unit, so a vendor crop/alignment or encoder fallback cannot be
     * mistaken for the requested 1920x720 stream.
     */
    private static void verifyOutputFormat(MediaFormat output) throws IOException {
        if (output == null || !output.containsKey(MediaFormat.KEY_WIDTH)
                || !output.containsKey(MediaFormat.KEY_HEIGHT)) {
            throw new IOException("SHELL_CAPTURE_OUTPUT_FORMAT_MISSING_SIZE");
        }
        int width = output.getInteger(MediaFormat.KEY_WIDTH);
        int height = output.getInteger(MediaFormat.KEY_HEIGHT);
        if (width != NoAuthVideoProtocol.WIDTH || height != NoAuthVideoProtocol.HEIGHT) {
            throw new IOException("SHELL_CAPTURE_OUTPUT_SIZE_MISMATCH expected="
                    + NoAuthVideoProtocol.WIDTH + "x" + NoAuthVideoProtocol.HEIGHT
                    + " actual=" + width + "x" + height);
        }
        if (output.containsKey(MediaFormat.KEY_FRAME_RATE)
                && output.getInteger(MediaFormat.KEY_FRAME_RATE) < 1) {
            throw new IOException("SHELL_CAPTURE_OUTPUT_FRAME_RATE_INVALID");
        }
    }

    static final class Mirror implements AutoCloseable {
        private final Class<?> control = Class.forName("android.view.SurfaceControl");
        private final CaptureOptions options;
        private final int outputWidth;
        private final int outputHeight;
        private IBinder token;

        Mirror(Surface surface, CaptureOptions options) throws Exception {
            this(surface, options, NoAuthVideoProtocol.WIDTH, NoAuthVideoProtocol.HEIGHT);
        }

        Mirror(Surface surface, CaptureOptions options, int outputWidth, int outputHeight) throws Exception {
            this(surface, options, outputWidth, outputHeight, 0);
        }

        Mirror(Surface surface, CaptureOptions options, int outputWidth, int outputHeight,
                int displayId) throws Exception {
            this.options = options;
            this.outputWidth = outputWidth;
            this.outputHeight = outputHeight;
            token = (IBinder) control.getMethod("createDisplay", String.class, boolean.class)
                    .invoke(null, "dashcast-phase1-capture", false);
            if (token == null) { throw new IOException("SHELL_CAPTURE_DISPLAY_CREATE_FAILED"); }
            try {
                control.getMethod("openTransaction").invoke(null);
                try { method("setDisplaySurface", IBinder.class, Surface.class).invoke(null, token, surface); }
                finally { control.getMethod("closeTransaction").invoke(null); }
                selectDisplay(displayId);
            } catch (Exception error) {
                try { close(); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
                throw error;
            }
        }

        void selectDisplay(int displayId) throws Exception {
            if (token == null || displayId < 0) { throw new IOException("SHELL_CAPTURE_SOURCE_DISPLAY_INVALID"); }
            Class<?> manager = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object global = manager.getMethod("getInstance").invoke(null);
            Object display = manager.getMethod("getDisplayInfo", int.class).invoke(global, displayId);
            if (display == null) {
                throw new IOException("SHELL_CAPTURE_PRIMARY_DISPLAY_MISSING");
            }
            int width = display.getClass().getField("logicalWidth").getInt(display);
            int height = display.getClass().getField("logicalHeight").getInt(display);
            int stack = display.getClass().getField("layerStack").getInt(display);
            if (width < 1 || height < 1 || width > 4096 || height > 4096 || stack < 0) {
                throw new IOException("SHELL_CAPTURE_UNEXPECTED_DISPLAY");
            }
            CaptureGeometry geometry = CaptureGeometry.create(width, height, options, outputWidth, outputHeight);
            try {
                control.getMethod("openTransaction").invoke(null);
                try {
                    method("setDisplayLayerStack", IBinder.class, int.class).invoke(null, token, stack);
                    method("setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class)
                            .invoke(null, token, 0, rect(geometry.source()), rect(geometry.destination()));
                } finally {
                    control.getMethod("closeTransaction").invoke(null);
                }
                System.err.println("SHELL_CAPTURE_MIRROR " + width + "x" + height + " layerStack=" + stack
                        + " source_display=" + displayId);
                System.err.println("SHELL_CAPTURE_GEOMETRY mode=" + options + " " + geometry
                        + "; native window unchanged; HMI occlusion not certified");
            } catch (Exception error) {
                try {
                    close();
                } catch (Exception cleanup) {
                    error.addSuppressed(cleanup);
                }
                throw error;
            }
        }

        private static Rect rect(CaptureGeometry.Bounds bounds) {
            return new Rect(bounds.left(), bounds.top(), bounds.right(), bounds.bottom());
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
