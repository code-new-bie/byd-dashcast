package com.byd.dashcast.phase1

import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.view.Surface
import java.io.IOException
import java.lang.reflect.Method
import java.util.function.Consumer

/**
 * Android-10 shell-only SurfaceFlinger capture, launched through normal authorized ADB.
 * Own temporary display and hardware Baseline encoder only; no MediaProjection, secure
 * display, APK privilege grant, adbd modification, route changes or persistence.
 *
 * app_process starts this class by name (`com.byd.dashcast.phase1.ShellScreenCapture`), so the name
 * and the static `main(String[])` must stay.
 * @author Codex
 * @date 2026/10/12
 */
object ShellScreenCapture {
    /**
     * Keep a static Surface input alive at the requested 30 FPS when the display has no new
     * buffer. This is a codec-side repeat of the last picture, not a fabricated motion frame;
     * The input FPS limit and transport pacer still bound the stream; source PTS is preserved.
     */
    internal const val REPEAT_PREVIOUS_FRAME_AFTER_US = ProjectionLimits.TARGET_FRAME_INTERVAL_US

    private class CleanupProof {
        var captureClosed = true
        var controlClosed = true

        fun complete(): Boolean = captureClosed && controlClosed
    }

    @JvmStatic
    fun main(args: Array<String>) {
        // Context/LoadedApk initialization on the OEM ROM can print hot-fix diagnostics to System.out.
        // Binary DCS1/AVC uses FileDescriptor.out directly and must never share that PrintStream.
        ShellCaptureOutput.isolateFrameworkDiagnostics()
        var captureMayOwnResources = false
        val proof = CleanupProof()
        try {
            val mirrorTicket = DuplexTicket.read(args)
            val argv = DuplexTicket.without(args)
            val options = CaptureOptions.fromShellArguments(argv)
            val target = ProjectionTarget.fromShellArguments(argv)
            val live = argv.size >= 2 && "live" == argv[1]
            val remoteProbe = argv.size >= 2 && "remote" == argv[1]
            if ((!live && !remoteProbe && argv.size != 1) ||
                Process.myUid() != 2000 || Build.VERSION.SDK_INT != 29) {
                throw IOException("SHELL_CAPTURE_REQUIRES_AUTHORIZED_UID2000_ANDROID10")
            }
            val seconds = Integer.parseInt(argv[0])
            if (live) { ContinuousSessionPolicy.requireCaptureSeconds(seconds) }
            if (!live && (seconds < 1 || seconds > 10)) {
                throw IOException("SHELL_CAPTURE_DURATION_LIMIT")
            }
            // Every check above is pure preflight. From this point onward owned
            // framework/control/capture resources must be released by their owner.
            captureMayOwnResources = true
            if ((target.isRemote || target.isNamed) && Looper.myLooper() == null) {
                // app_process invokes this main directly; ActivityThread and framework Handlers need a Looper.
                @Suppress("DEPRECATION")
                Looper.prepareMainLooper()
                System.err.println("SHELL_CAPTURE_REMOTE_LOOPER_READY")
            }
            if (live) {
                proof.controlClosed = false
                val control = CaptureStopControl(System.`in`)
                var captureFailure: Exception? = null
                try {
                    control.start()
                    capture(seconds, control, options, target, mirrorTicket, proof)
                } catch (error: Exception) {
                    captureFailure = error
                    throw error
                } finally {
                    try {
                        control.close()
                        proof.controlClosed = true
                    } catch (cleanup: Exception) {
                        val first = captureFailure
                        if (first != null) { first.addSuppressed(cleanup) } else { throw cleanup }
                    }
                }
            } else {
                capture(seconds, null, options, target, null, proof)
            }
            if (!proof.complete()) { throw IOException("SHELL_CAPTURE_CLEANUP_UNCONFIRMED") }
            System.err.println("SHELL_CAPTURE_RESOURCES_RELEASED")
            System.err.flush()
            // ActivityThread/Looper/Binder initialization can leave non-daemon framework
            // threads after every owned resource is closed. Emit the real shell-v2 EXIT.
            System.exit(0)
        } catch (error: Exception) {
            System.err.println("SHELL_CAPTURE_FAILED " + error)
            for (suppressed in error.suppressed) {
                System.err.println("SHELL_CAPTURE_CLEANUP_FAILED " + suppressed)
            }
            if (!captureMayOwnResources || proof.complete()) {
                System.err.println("SHELL_CAPTURE_RESOURCES_RELEASED phase=" +
                    (if (captureMayOwnResources) "owned_closed" else "preflight created=false"))
            }
            System.exit(1)
        }
    }

    private fun format(codecRepeat: Boolean = true): MediaFormat {
        val format = MediaFormat.createVideoFormat("video/avc", NoAuthVideoProtocol.WIDTH,
            NoAuthVideoProtocol.HEIGHT)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, NoAuthVideoProtocol.FPS)
        // Throttle Surface input before encoding; dropping encoded P frames breaks their reference chain.
        format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, NoAuthVideoProtocol.FPS.toFloat())
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)
        if (codecRepeat) { format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_PREVIOUS_FRAME_AFTER_US) }
        return format
    }

    @Throws(IOException::class)
    private fun encoder(): MediaCodec {
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (!info.isEncoder || !info.isHardwareAccelerated) {
                continue
            }
            for (mime in info.supportedTypes) {
                if ("video/avc".equals(mime, ignoreCase = true) && info.getCapabilitiesForType(mime).isFormatSupported(format())) {
                    System.err.println("SHELL_CAPTURE_CODEC " + info.name + " baseline level4 " +
                        NoAuthVideoProtocol.WIDTH + "x" + NoAuthVideoProtocol.HEIGHT + "/30")
                    return MediaCodec.createByCodecName(info.name)
                }
            }
        }
        throw IOException("SHELL_CAPTURE_NO_HARDWARE_BASELINE_ENCODER")
    }

    @Throws(Exception::class)
    private fun capture(seconds: Int, control: CaptureStopControl?, options: CaptureOptions,
        target: ProjectionTarget, mirrorTicket: String?, proof: CleanupProof) {
        proof.captureClosed = false
        var codec: MediaCodec? = null
        var surface: Surface? = null
        var display: AutoCloseable? = null
        var center: CenterFrameRenderer? = null
        var remote: RemoteCaptureSession? = null
        var started = false
        var failure: Exception? = null
        try {
            // Encoder discovery/creation may itself fail. It belongs inside the
            // same cleanup scope, rather than stranding a no-resource failure.
            val encoder = encoder()
            codec = encoder
            encoder.configure(format(!options.contentViewport() && !options.centerWindow()), null, null,
                MediaCodec.CONFIGURE_FLAG_ENCODE)
            val input = encoder.createInputSurface()
            surface = input
            encoder.start()
            started = true
            var captureSurface = input
            var logicalWidth = NoAuthVideoProtocol.WIDTH
            var logicalHeight = NoAuthVideoProtocol.HEIGHT
            val selection = if (target.isRemote) {
                ProjectionEntryRouter.resolve(RemoteDisplaySession.shellContext(), target,
                    Consumer { line -> System.err.println("SHELL_CAPTURE_" + line) })
            } else {
                null
            }
            val business = if (selection != null && selection.native == null && mirrorTicket != null) {
                BusinessDisplayProfile.read(RemoteDisplaySession.shellContext())
            } else {
                null
            }
            val workspace = if (business != null && target.isWorkspace) {
                WorkspaceDisplayProfile.forMode(options.centerWindow())
            } else {
                null
            }
            if (options.contentViewport()) {
                var viewport = if (options.centerWindow()) ContentViewport.center() else ContentViewport.full()
                if (target.isNamed && mirrorTicket != null) {
                    // The app starts a named session in the standby display's own mode, so this matches its size.
                    viewport = viewport.withSourceSize(ExternalDisplayWire.width(options.centerWindow()),
                        ExternalDisplayWire.height(options.centerWindow()))
                }
                if (business != null) {
                    // Keep the business application's original main-screen layout. The
                    // instrument's independent FIT/CROP mapping must not reflow that UI.
                    viewport = if (workspace != null) ContentViewport.forWorkspace(workspace, options.centerWindow())
                    else ContentViewport.forBusiness(business, options.centerWindow())
                }
                // RWVG: a source laid out for the viewport (map, named display) fills it, fade-in band included;
                // only a business layout of another aspect is still fitted into the clear opening.
                val renderer = CenterFrameRenderer(input, viewport,
                    options.mode() == CaptureOptions.Mode.CROP || business == null)
                center = renderer
                captureSurface = renderer.inputSurface()
                logicalWidth = viewport.sourceWidth
                logicalHeight = viewport.sourceHeight
            } else if (options.centerWindow()) {
                val renderer = CenterFrameRenderer(input)
                center = renderer
                captureSurface = renderer.inputSurface()
                logicalWidth = 1280
            }
            if (target.isNamed) {
                val session: RemoteCaptureSession = if (mirrorTicket != null) {
                    ExternalCaptureSession(captureSurface, mirrorTicket)
                } else {
                    NamedDisplaySession(captureSurface, logicalWidth, logicalHeight)
                }
                remote = session
                display = session
            } else if (target.isRemote) {
                val native = selection?.native
                val session: RemoteCaptureSession = if (native != null) {
                    RemoteDisplaySession(captureSurface, target, logicalWidth, logicalHeight, options, native)
                } else if (mirrorTicket != null) {
                    val renderer = center ?: throw IOException("MIRROR_CONTENT_RENDERER_REQUIRED")
                    MirrorAppSession(captureSurface, selection!!.entry!!, logicalWidth, logicalHeight,
                        mirrorTicket, renderer, workspace?.density ?: business!!.density)
                } else {
                    RemoteDisplaySession(captureSurface, target, logicalWidth, logicalHeight, options)
                }
                remote = session
                display = session
            } else {
                display = Mirror(captureSurface, options, logicalWidth, logicalHeight)
            }
            stream(encoder, seconds, control, center, remote)
        } catch (error: Exception) {
            failure = error
            throw error
        } finally {
            var cleanup: Exception? = null
            try {
                display?.close()
            } catch (error: Exception) {
                cleanup = error
            }
            try {
                center?.close()
            } catch (error: Exception) {
                cleanup = collect(cleanup, error)
            }
            val ownedCodec = codec
            try {
                if (started && ownedCodec != null) {
                    ownedCodec.stop()
                }
            } catch (error: RuntimeException) {
                cleanup = collect(cleanup, error)
            }
            try {
                ownedCodec?.release()
            } catch (error: RuntimeException) {
                cleanup = collect(cleanup, error)
            }
            try {
                surface?.release()
            } catch (error: RuntimeException) {
                cleanup = collect(cleanup, error)
            }
            val first = failure
            if (cleanup != null) {
                if (first != null) {
                    first.addSuppressed(cleanup)
                } else {
                    throw cleanup
                }
            } else if (first == null || first.suppressed.isEmpty()) {
                // The main owner emits the proof only AFTER stdin/control closes.
                // A later control-close failure must never leave an earlier proof.
                proof.captureClosed = true
            }
        }
    }

    private fun collect(first: Exception?, next: Exception): Exception {
        if (first == null) {
            return next
        }
        first.addSuppressed(next)
        return first
    }

    @Throws(Exception::class)
    private fun stream(codec: MediaCodec, seconds: Int, control: CaptureStopControl?,
        center: CenterFrameRenderer?, remote: RemoteCaptureSession?) {
        val began = System.nanoTime()
        var drainUntil = 0L
        val unlimited = control != null && ContinuousSessionPolicy.unlimited(seconds)
        val stdout = ShellCaptureOutput.videoStream()
        var lastPts = -1L
        var lastIdle = System.nanoTime()
        var frames = 0L
        var eos = false
        var config = ByteArray(0)
        val frameRateEvidence = OutputFrameRateEvidence(NoAuthVideoProtocol.FPS)
        var nextRollingGateFrame = OutputFrameRateEvidence.MIN_STABLE_FRAMES.toLong()
        val info = MediaCodec.BufferInfo()
        while (!eos || System.nanoTime() - drainUntil < 0) {
            if (!eos && (ContinuousSessionPolicy.timedOut(seconds, began, System.nanoTime()) ||
                    (control != null && control.stopped()))) {
                codec.signalEndOfInputStream()
                eos = true
                drainUntil = System.nanoTime() + 2_000_000_000L
            }
            if (!eos && remote != null) { remote.update(codec) }
            if (!eos && center != null) {
                center.renderIfAvailable(remote == null || remote.renderAllowed(),
                    if (remote == null) 0L else remote.renderSourceEpochNanos(),
                    remote == null || remote.advanceSourceAllowed())
            }
            // Source handoff may deliberately discard encoder output until a fresh IDR.
            // Keep the existing same-stream lease alive without forwarding that stale data.
            if (control != null && System.nanoTime() - lastIdle >= 250_000_000L) {
                stdout.write(CaptureFrameProtocol.idle(maxOf(0L, lastPts)))
                stdout.flush()
                lastIdle = System.nanoTime()
            }
            val index = codec.dequeueOutputBuffer(info, if (center == null || eos) 10_000L else center.outputPollTimeoutUs())
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val output = codec.outputFormat
                verifyOutputFormat(output)
                System.err.println("SHELL_CAPTURE_FORMAT " + output +
                    " target=" + NoAuthVideoProtocol.WIDTH + "x" + NoAuthVideoProtocol.HEIGHT +
                    "/" + NoAuthVideoProtocol.FPS +
                    " repeat_previous_frame_after_us=" + REPEAT_PREVIOUS_FRAME_AFTER_US)
                continue
            }
            if (index < 0) {
                if (control != null && System.nanoTime() - lastIdle >= 250_000_000L) {
                    stdout.write(CaptureFrameProtocol.idle(maxOf(0L, lastPts)))
                    stdout.flush()
                    lastIdle = System.nanoTime()
                }
                continue
            }
            try {
                if (remote != null && (eos || (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)) {
                    // Do not forward queued pictures after stop, but still consume the EOS flag
                    // below. A continue here would lose a nonempty final EOS and fake a timeout.
                    info.size = 0
                }
                if (info.size > 0) {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer == null || info.size > NoAuthVideoProtocol.MAX_FRAME_BYTES) {
                        throw IOException("SHELL_CAPTURE_BAD_BUFFER")
                    }
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        config = AvcByteStream.normalizeConfiguration(buffer)
                    } else {
                        val key = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        if (remote != null && (eos || !remote.acceptFrame(info.presentationTimeUs, key))) {
                            continue
                        }
                        frames = ContinuousSessionPolicy.add(frames, 1)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_PARTIAL_FRAME) != 0 || info.presentationTimeUs <= lastPts ||
                            (!unlimited && frames > (if (control == null) 360 else ProjectionLimits.MAX_CAPTURE_FRAMES))) {
                            throw IOException("SHELL_CAPTURE_PARTIAL_B_FRAME_OR_FRAME_LIMIT")
                        }
                        val frameNanos = System.nanoTime()
                        frameRateEvidence.record(info.presentationTimeUs, frameNanos)
                        if (frameRateEvidence.sampleFrames() >= nextRollingGateFrame) {
                            val rolling = frameRateEvidence.result()
                            System.err.println(rolling.logLine() + " sample=rolling diagnostic_only=true")
                            nextRollingGateFrame = ContinuousSessionPolicy.add(nextRollingGateFrame,
                                OutputFrameRateEvidence.MIN_STABLE_FRAMES.toLong())
                        }
                        var picture = AvcByteStream.normalize(buffer)
                        if (key) {
                            if (config.isEmpty()) {
                                throw IOException("SHELL_CAPTURE_MISSING_CONFIGURATION")
                            }
                            if (config.size > NoAuthVideoProtocol.MAX_FRAME_BYTES - picture.size) {
                                throw IOException("SHELL_CAPTURE_CONFIG_FRAME_LIMIT")
                            }
                            picture = config + picture
                        }
                        if (control != null) {
                            stdout.write(CaptureFrameProtocol.header(picture.size, info.presentationTimeUs, key))
                        }
                        stdout.write(picture)
                        stdout.flush()
                        lastIdle = System.nanoTime()
                        lastPts = info.presentationTimeUs
                    }
                }
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (unlimited && !control!!.stopped()) {
                        throw IOException("SHELL_CAPTURE_UNEXPECTED_EOS_IN_UNLIMITED_SESSION")
                    }
                    if (frames < 2 && !(control != null && control.stopped())) {
                        throw IOException("SHELL_CAPTURE_FEWER_THAN_TWO_PICTURES")
                    }
                    val evidence = frameRateEvidence.result()
                    // Display refresh, codec batching and ACK backpressure can jitter timestamps.
                    // Keep the measured result without terminating valid H.264 pictures.
                    System.err.println(evidence.logLine() + " sample=final diagnostic_only=true")
                    if (control != null) {
                        stdout.write(CaptureFrameProtocol.end(maxOf(0L, lastPts)))
                        stdout.flush()
                    }
                    System.err.println("SHELL_CAPTURE_COMPLETE frames=" + frames +
                        " pts_fps_x100=" + evidence.ptsFpsX100 +
                        " wall_fps_x100=" + evidence.wallFpsX100 +
                        " min_pts_interval_us=" + evidence.minPtsIntervalUs +
                        " max_pts_interval_us=" + evidence.maxPtsIntervalUs +
                        " average_pts_interval_us=" + evidence.averagePtsIntervalUs +
                        "; encoder_output_measured, instrument_display_unverified")
                    return
                }
            } finally {
                codec.releaseOutputBuffer(index, false)
            }
        }
        throw IOException("SHELL_CAPTURE_EOS_TIMEOUT")
    }

    /**
     * The requested format is only a negotiation input. Validate the first actual output format
     * before forwarding any access unit, so a vendor crop/alignment or encoder fallback cannot be
     * mistaken for the requested 1920x720 stream.
     */
    @Throws(IOException::class)
    private fun verifyOutputFormat(output: MediaFormat?) {
        if (output == null || !output.containsKey(MediaFormat.KEY_WIDTH) ||
            !output.containsKey(MediaFormat.KEY_HEIGHT)) {
            throw IOException("SHELL_CAPTURE_OUTPUT_FORMAT_MISSING_SIZE")
        }
        val width = output.getInteger(MediaFormat.KEY_WIDTH)
        val height = output.getInteger(MediaFormat.KEY_HEIGHT)
        if (width != NoAuthVideoProtocol.WIDTH || height != NoAuthVideoProtocol.HEIGHT) {
            throw IOException("SHELL_CAPTURE_OUTPUT_SIZE_MISMATCH expected=" +
                NoAuthVideoProtocol.WIDTH + "x" + NoAuthVideoProtocol.HEIGHT +
                " actual=" + width + "x" + height)
        }
        if (output.containsKey(MediaFormat.KEY_FRAME_RATE) &&
            output.getInteger(MediaFormat.KEY_FRAME_RATE) < 1) {
            throw IOException("SHELL_CAPTURE_OUTPUT_FRAME_RATE_INVALID")
        }
    }

    internal class Mirror @JvmOverloads @Throws(Exception::class) constructor(
        surface: Surface,
        private val options: CaptureOptions,
        private val outputWidth: Int = NoAuthVideoProtocol.WIDTH,
        private val outputHeight: Int = NoAuthVideoProtocol.HEIGHT,
        displayId: Int = 0,
    ) : AutoCloseable {
        private val control: Class<*> = Class.forName("android.view.SurfaceControl")
        private var token: IBinder? = null

        init {
            val created = control.getMethod("createDisplay", String::class.java, Boolean::class.javaPrimitiveType)
                .invoke(null, "dashcast-phase1-capture", false) as IBinder?
                ?: throw IOException("SHELL_CAPTURE_DISPLAY_CREATE_FAILED")
            token = created
            try {
                control.getMethod("openTransaction").invoke(null)
                try { method("setDisplaySurface", IBinder::class.java, Surface::class.java).invoke(null, created, surface) }
                finally { control.getMethod("closeTransaction").invoke(null) }
                selectDisplay(displayId)
            } catch (error: Exception) {
                try { close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
                throw error
            }
        }

        @Throws(Exception::class)
        fun selectDisplay(displayId: Int) {
            val owned = token
            if (owned == null || displayId < 0) { throw IOException("SHELL_CAPTURE_SOURCE_DISPLAY_INVALID") }
            val manager = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val global = manager.getMethod("getInstance").invoke(null)
            val display = manager.getMethod("getDisplayInfo", Int::class.javaPrimitiveType).invoke(global, displayId)
                ?: throw IOException("SHELL_CAPTURE_PRIMARY_DISPLAY_MISSING")
            val width = display.javaClass.getField("logicalWidth").getInt(display)
            val height = display.javaClass.getField("logicalHeight").getInt(display)
            val stack = display.javaClass.getField("layerStack").getInt(display)
            if (width < 1 || height < 1 || width > 4096 || height > 4096 || stack < 0) {
                throw IOException("SHELL_CAPTURE_UNEXPECTED_DISPLAY")
            }
            val geometry = CaptureGeometry.create(width, height, options, outputWidth, outputHeight)
            try {
                control.getMethod("openTransaction").invoke(null)
                try {
                    method("setDisplayLayerStack", IBinder::class.java, Int::class.javaPrimitiveType).invoke(null, owned, stack)
                    method("setDisplayProjection", IBinder::class.java, Int::class.javaPrimitiveType, Rect::class.java,
                        Rect::class.java).invoke(null, owned, 0, rect(geometry.source()), rect(geometry.destination()))
                } finally {
                    control.getMethod("closeTransaction").invoke(null)
                }
                System.err.println("SHELL_CAPTURE_MIRROR " + width + "x" + height + " layerStack=" + stack +
                    " source_display=" + displayId)
                System.err.println("SHELL_CAPTURE_GEOMETRY mode=" + options + " " + geometry +
                    "; native window unchanged; HMI occlusion not certified")
            } catch (error: Exception) {
                try {
                    close()
                } catch (cleanup: Exception) {
                    error.addSuppressed(cleanup)
                }
                throw error
            }
        }

        private fun rect(bounds: CaptureGeometry.Bounds): Rect =
            Rect(bounds.left(), bounds.top(), bounds.right(), bounds.bottom())

        @Throws(NoSuchMethodException::class)
        private fun method(name: String, vararg parameters: Class<*>?): Method = control.getMethod(name, *parameters)

        @Throws(Exception::class)
        override fun close() {
            val owned = token
            if (owned != null) {
                token = null
                control.getMethod("destroyDisplay", IBinder::class.java).invoke(null, owned)
            }
        }
    }
}
