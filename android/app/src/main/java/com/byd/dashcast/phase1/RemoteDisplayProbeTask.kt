package com.byd.dashcast.phase1

import android.content.Context
import java.io.IOException
import java.util.Objects
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/**
 * A foreground-service-owned, five-second virtual display probe; no instrument video or file.
 * @author Codex
 * @date 2026/10/20
 */
internal class RemoteDisplayProbeTask(context: Context, target: ProjectionTarget?, log: Consumer<String>?) :
    ProjectionSessionController.Task {
    private val context: Context = context.applicationContext
    private val target: ProjectionTarget = Objects.requireNonNull(target)!!
    private val log: Consumer<String> = Objects.requireNonNull(log)!!

    init {
        if (!this.target.isRemote) {
            throw IllegalArgumentException("Remote probe requires a selected application")
        }
    }

    @Throws(IOException::class)
    override fun run(stop: AtomicBoolean, abort: AtomicBoolean, streaming: Runnable, cleaning: Runnable) {
        val contract = AvcReceiverContract()
        var frames = 0
        val observer = object : VideoPatternProbe.Observer {
            override fun log(text: String) {
                log.accept(text)
            }

            @Throws(IOException::class)
            override fun frame(data: ByteArray, ptsMicros: Long, keyFrame: Boolean) {
                if (!stop.get()) {
                    contract.accept(data, log)
                    frames++
                    streaming.run()
                }
            }
        }
        val adb = AdbShellClient("127.0.0.1", DashcastPorts.LOCAL_ADB, 20000, AndroidAdbIdentity.load(), log, abort)
        val source = AdbScreenVideoSource(adb, observer, log)
        var failure: IOException? = null
        try {
            source.runContinuous(5, context.applicationInfo.sourceDir, stop, cleaning,
                CaptureOptions.of(CaptureOptions.Mode.FIT), target)
        } catch (error: IOException) {
            failure = error
        } catch (error: RuntimeException) {
            failure = IOException("REMOTE_PROBE_RUNTIME_FAILURE", error)
        }
        RemoteProbeCompletion.require(source.exitConfirmed(), stop.get(), frames, failure)
        log.accept((if (stop.get()) "REMOTE_DISPLAY_PROBE_STOPPED" else "REMOTE_DISPLAY_RUNTIME_PASS") +
            " target=" + target + " frames=" + frames +
            "; owned EXIT/resources confirmed; NOT instrument picture/FPS evidence")
    }
}
