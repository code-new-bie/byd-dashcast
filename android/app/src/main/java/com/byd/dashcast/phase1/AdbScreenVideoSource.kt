package com.byd.dashcast.phase1

import java.io.IOException
import java.util.Objects
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/**
 * Bounded live AVC stdout through authorized local ADB, framed as complete pictures.
 * The instrument path selects the shell-owned Baseline encoder, not stock screenrecord defaults.
 * Receipt timestamps are synthetic transport timestamps, not source frame PTS/latency measurements.
 *
 * @author Codex
 * @date 2026/10/11
 */
internal class AdbScreenVideoSource(
    capture: ControlledCapture?,
    observer: VideoPatternProbe.Observer?,
    log: Consumer<String>?,
) {
    fun interface ControlledCapture {
        @Throws(IOException::class)
        fun execute(command: String, handler: AdbShellClient.ShellOutputHandler, diagnostics: Consumer<String>,
            stop: AtomicBoolean?, beforeStop: Runnable, exitConfirmation: Consumer<Boolean>)
    }

    private val capture: ControlledCapture = Objects.requireNonNull(capture)!!
    private val observer: VideoPatternProbe.Observer = Objects.requireNonNull(observer)!!
    private val log: Consumer<String> = Objects.requireNonNull(log)!!
    private var exitConfirmed = false
    private var captureResourcesReleased = false
    private var mirrorTicket: String? = null

    constructor(client: AdbShellClient?, observer: VideoPatternProbe.Observer?, log: Consumer<String>?) :
        this(ControlledCapture(Objects.requireNonNull(client)!!::executeShellV2Controlled), observer, log)

    fun setMirrorTicket(ticket: String?) {
        if (!DuplexTicket.valid(ticket)) {
            throw IllegalArgumentException("Invalid mirror session")
        }
        mirrorTicket = ticket
    }

    @Throws(IOException::class)
    fun run(seconds: Int) {
        runCommand(command(seconds))
    }

    @Throws(IOException::class)
    fun runSmallWindow(seconds: Int) {
        runCommand(command(seconds))
    }

    @Throws(IOException::class)
    fun runSmallWindow(seconds: Int, installedApk: String?) {
        runCommand(ShellCaptureCommand.create(installedApk, seconds))
    }

    @Throws(IOException::class)
    fun runContinuous(seconds: Int, installedApk: String?, stopRequested: AtomicBoolean?, finishReceiver: Runnable) {
        runContinuous(seconds, installedApk, stopRequested, finishReceiver, null)
    }

    @Throws(IOException::class)
    fun runContinuous(seconds: Int, installedApk: String?, stopRequested: AtomicBoolean?, finishReceiver: Runnable,
        options: CaptureOptions?) {
        runContinuous(seconds, installedApk, stopRequested, finishReceiver, options, ProjectionTarget.primary())
    }

    @Throws(IOException::class)
    fun runContinuous(seconds: Int, installedApk: String?, stopRequested: AtomicBoolean?, finishReceiver: Runnable,
        options: CaptureOptions?, target: ProjectionTarget?) {
        val reader = FramedCaptureReader(observer, finishReceiver, ContinuousSessionPolicy.unlimited(seconds))
        val selectedTarget = Objects.requireNonNull(target, "Projection target required")!!
        log.accept("VIDEO_SOURCE=authorized_ADB_DCS1; timestamps=encoder_PTS; no video file recorded" +
            "; target=" + selectedTarget)
        val selectedOptions = options ?: CaptureOptions.of(CaptureOptions.Mode.STRETCH)
        var command = ShellCaptureCommand.continuous(installedApk, seconds, selectedOptions, selectedTarget)
        mirrorTicket?.let { command += " " + DuplexTicket.argument(it) }
        log.accept("CAPTURE_SELECTION=" + selectedOptions + "; immutable for this session; instrument rectangle unchanged")
        log.accept("PROJECTION_TARGET=$selectedTarget; virtual display is owned by the ADB shell helper")
        val stop = Objects.requireNonNull(stopRequested)!!
        capture.execute(command, reader, Consumer { message ->
            log.accept(message)
            captureResourcesReleased = captureResourcesReleased or message.contains("SHELL_CAPTURE_RESOURCES_RELEASED")
        }, stop, finishReceiver, Consumer { ended -> exitConfirmed = ended && captureResourcesReleased })
        reader.finish(stop.get())
        log.accept(reader.statistics())
    }

    fun exitConfirmed(): Boolean = exitConfirmed

    @Throws(IOException::class)
    private fun runCommand(command: String) {
        val start = System.nanoTime()
        var lastPts = -1L
        val reader = AnnexBAccessUnitReader(AnnexBAccessUnitReader.Observer { data, key ->
            val pts = maxOf(lastPts + 1, 1_000_000L + (System.nanoTime() - start) / 1000)
            observer.frame(data, pts, key)
            lastPts = pts
        })
        log.accept("VIDEO_SOURCE=authorized_ADB; nominal_receiver_fps=30; timestamps=receipt_NOT_source_PTS")
        log.accept("ADB_VIDEO_COMMAND $command")
        capture.execute(command, reader, log, null, Runnable {}, Consumer {})
        reader.finish()
        if (reader.frameCount() < 2) {
            throw IOException("ADB capture produced fewer than two complete pictures; moving video unverified")
        }
        log.accept("ADB_SCREEN_VIDEO bytes=" + reader.byteCount() + " complete_frames=" + reader.frameCount() +
            "; encoder FPS/latency and instrument display NOT certified by these counts")
    }

    companion object {
        @JvmStatic
        fun command(seconds: Int): String {
            if (seconds < 1 || seconds > 10) {
                throw IllegalArgumentException("ADB screen verification must be 1..10 seconds")
            }
            return "screenrecord --output-format=h264 --size 1920x720 --bit-rate 6000000" +
                " --time-limit " + seconds + " -"
        }

        @JvmStatic
        fun smallWindowCommand(seconds: Int): String = command(seconds)
    }
}
