package com.byd.dashcast.phase1

import android.content.Context
import com.byd.dashcast.phase1.ncm.LogHub
import java.io.IOException
import java.security.SecureRandom
import java.util.Objects
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier
import java.util.function.Consumer

/**
 * Owns the existing NCM/DCV1/capture path, with no Activity reference or video disk recording.
 * @author Codex
 * @date 2026/10/13
 */
internal class ContinuousProjectionTask @JvmOverloads constructor(
    context: Context,
    seconds: Int,
    log: Consumer<String>,
    options: CaptureOptions? = CaptureOptions.of(CaptureOptions.Mode.STRETCH),
    target: ProjectionTarget? = ProjectionTarget.primary(),
    private val compositionTrial: Boolean = false,
) : ProjectionSessionController.Task {
    private val context: Context
    private val seconds: Int
    private val log: Consumer<String>
    private val options: CaptureOptions
    private val target: ProjectionTarget
    private var productComposition = false
    private val physicallyDetached = AtomicBoolean()

    init {
        ContinuousSessionPolicy.requireCaptureSeconds(seconds)
        this.context = context.applicationContext
        this.seconds = seconds
        this.log = log
        this.options = Objects.requireNonNull(options)!!
        this.target = Objects.requireNonNull(target)!!
    }

    /** Called only for the OS detach broadcast matching this Service's owned device. */
    fun notePhysicalDetach() {
        physicallyDetached.set(true)
    }

    fun enableProductComposition(): ContinuousProjectionTask {
        productComposition = true
        return this
    }

    @Throws(IOException::class)
    override fun run(stop: AtomicBoolean, abort: AtomicBoolean, streaming: Runnable, cleaning: Runnable) {
        var session: UsbNcmSession? = null
        var client: NoAuthVideoClient? = null
        var source: AdbScreenVideoSource? = null
        val frameRateGate = FrameRateGate(NoAuthVideoProtocol.FPS)
        val framePacer = FramePacer(NoAuthVideoProtocol.FPS)
        var failure: IOException? = null
        var usbClean = true
        var captureAttempted = false
        var mirrorTicket: String? = null
        var externalTicket: String? = null
        var receiverFailure: IOException? = null
        try {
            LogHub.banner("CONTINUOUS_VIDEO_ATTEMPT " + System.currentTimeMillis() + " seconds=" + seconds)
            if (stop.get()) {
                return
            }
            val ownedSession = UsbNcmSession.open(context, abort)
            session = ownedSession
            ownedSession.observePhysicalDetach(BooleanSupplier { physicallyDetached.get() })
            val ownedClient = NoAuthVideoClient(NoAuthVideoClient.Connector { port, flag -> ownedSession.connect(port, flag) },
                abort, NoAuthVideoClient.Logger { log.accept(it) }, true)
            client = ownedClient
            if (ContinuousSessionPolicy.unlimited(seconds)) {
                ownedClient.enableUnlimitedStream()
            }
            if (productComposition) {
                ownedClient.enableProductComposition(SecureRandom().nextInt(Int.MAX_VALUE - 1) + 1, options.centerWindow())
            } else if (compositionTrial) {
                ownedClient.enableCompositionTrial(SecureRandom().nextInt(Int.MAX_VALUE - 1) + 1, Runnable { stop.set(true) })
            }
            val contract = AvcReceiverContract()
            var begun = false
            var receiverFinished = false
            val finishReceiver = Runnable {
                if (receiverFinished) {
                    return@Runnable
                }
                receiverFinished = true
                cleaning.run()
                if (ownedSession.physicalLinkAbsent(context)) {
                    physicallyDetached.set(true)
                    stop.set(true)
                    abort.set(true)
                    log.accept("OWNED_USB_REMOVAL_CONFIRMED_BY_DEVICE_LIST")
                }
                if (physicallyDetached.get()) {
                    try {
                        ownedClient.closeAfterPhysicalDetach()
                    } catch (error: IOException) {
                        receiverFailure = collect(receiverFailure, error)
                    } catch (error: RuntimeException) {
                        receiverFailure = collect(receiverFailure, error)
                    }
                    return@Runnable
                }
                try {
                    if (!stop.get() && !abort.get() && begun) {
                        ownedClient.requireWindowReport()
                    }
                } catch (error: IOException) {
                    receiverFailure = collect(receiverFailure, error)
                } catch (error: RuntimeException) {
                    receiverFailure = collect(receiverFailure, error)
                } finally {
                    try {
                        ownedClient.close()
                    } catch (error: IOException) {
                        receiverFailure = collect(receiverFailure, error)
                    } catch (error: RuntimeException) {
                        receiverFailure = collect(receiverFailure, error)
                    }
                }
            }
            val frames = object : VideoPatternProbe.Observer {
                @Throws(IOException::class)
                override fun tick() {
                    if (!stop.get()) {
                        ownedClient.maintain()
                    }
                }

                override fun log(text: String) {
                    log.accept(text)
                }

                @Throws(IOException::class)
                override fun frame(data: ByteArray, ptsMicros: Long, keyFrame: Boolean) {
                    if (stop.get()) {
                        return
                    }
                    val outputPts = frameRateGate.outputPts(ptsMicros)
                    contract.accept(data, log)
                    if (!begun) {
                        ownedClient.begin()
                        begun = true
                    }
                    // Preserve real source PTS, but prevent encoder batches from bursting.
                    // Schedule each synchronous write on the
                    // monotonic 30 FPS clock; a slow ACK path is recorded as late, never hidden.
                    framePacer.awaitNextSlot()
                    ownedClient.frame(data, outputPts)
                    if (ownedClient.readyForStreaming()) {
                        streaming.run()
                    }
                }
            }
            val adb = AdbShellClient("127.0.0.1", DashcastPorts.LOCAL_ADB, 20000,
                // USB abort must not cancel the independent localhost channel before its owned STOP/EXIT.
                AndroidAdbIdentity.load(), log, AtomicBoolean())
            val ownedSource = AdbScreenVideoSource(adb, frames, log)
            source = ownedSource
            if (target.isNamed && ExternalDisplayAccess.ready()) {
                val ticket = ExternalDisplayAccess.beginCapture()
                externalTicket = ticket
                ownedSource.setMirrorTicket(ticket)
            }
            if (target.isRemote) {
                val entry = ProjectionEntryRouter.resolve(context, target, log)
                if (!target.isWorkspace) {
                    MirrorWorkspace.clearFinished()
                }
                val ticket = if (entry.native == null) {
                    MirrorBridge.begin(context, target.component().substring(0, target.component().indexOf('/')))
                } else {
                    DuplexTicket.create()
                }
                mirrorTicket = ticket
                if (target.isWorkspace) {
                    MirrorWorkspace.bind(target, ticket)
                }
                ownedSource.setMirrorTicket(ticket)
            }
            captureAttempted = true
            ownedSource.runContinuous(seconds, context.applicationInfo.sourceDir, stop, finishReceiver, options, target)
            receiverFailure?.let { throw it }
            log.accept(ownedClient.statistics())
        } catch (error: IOException) {
            failure = IOException("Continuous projection failed: $error", error)
        } catch (error: RuntimeException) {
            failure = IOException("Continuous projection failed: $error", error)
        } finally {
            try {
                ExternalDisplayAccess.endCapture(externalTicket)
            } catch (externalCleanup: Exception) {
                failure = collect(failure, externalCleanup)
            }
            MirrorBridge.end(mirrorTicket)
            log.accept(frameRateGate.statistics())
            log.accept(framePacer.statistics())
            cleaning.run()
            val ownedClient = client
            if (ownedClient != null) {
                try {
                    if (physicallyDetached.get()) {
                        ownedClient.closeAfterPhysicalDetach()
                    } else {
                        ownedClient.close()
                    }
                } catch (error: IOException) {
                    failure = collect(failure, error)
                } catch (error: RuntimeException) {
                    failure = collect(failure, error)
                }
            }
            val ownedSession = session
            if (ownedSession != null) {
                try {
                    log.accept(ownedSession.statistics())
                } finally {
                    try {
                        if (physicallyDetached.get()) {
                            ownedSession.closeDetached()
                        } else {
                            ownedSession.close()
                        }
                    } catch (error: IOException) {
                        usbClean = false
                        failure = collect(failure, error)
                    } catch (error: RuntimeException) {
                        usbClean = false
                        failure = collect(failure, error)
                    }
                }
            }
        }
        val captureClean = !captureAttempted || (source != null && source.exitConfirmed())
        val receiverClean = client == null || client.cleanupConfirmed()
        val localUsbClosed = usbClean && (session == null || session.localResourcesClosed())
        val partialOpenUnknown = session == null && openCleanupUnconfirmed(failure)
        if (physicallyDetached.get() && captureClean && localUsbClosed && !partialOpenUnknown) {
            log.accept("PHYSICAL_DETACH_LOCAL_CLEANUP_CONFIRMED; read-only peer recovery required after reconnect")
            throw ProjectionSessionController.DetachedSessionException(failure)
        }
        if (receiverFailure != null && failure == null) {
            failure = receiverFailure
        }
        if (failure != null) {
            log.accept("CONTINUOUS_FAILURE $failure")
            for (suppressed in failure.suppressed) {
                log.accept("CONTINUOUS_CLEANUP_FAILURE $suppressed")
            }
        }
        val outcome = CleanupRecoveryPolicy.classify(captureClean, localUsbClosed, partialOpenUnknown, receiverClean)
        val proof = "capture_exit=" + captureClean + " receiver=" + receiverClean +
            " USB=" + usbClean + " local_usb_closed=" + localUsbClosed +
            " partial_open_unknown=" + partialOpenUnknown +
            "; causes=" + CleanupRecoveryPolicy.describe(failure)
        log.accept("CLEANUP_EVIDENCE outcome=$outcome $proof")
        if (outcome == CleanupRecoveryPolicy.Outcome.PEER_RECOVERY_REQUIRED) {
            throw ProjectionSessionController.PeerCleanupPendingException(proof, failure)
        }
        if (outcome == CleanupRecoveryPolicy.Outcome.LOCAL_UNCONFIRMED) {
            if (CleanupRecoveryPolicy.captureOnly(captureClean, localUsbClosed, partialOpenUnknown)) {
                // Only the shell helper is in doubt; it can be found and ended from the shell afterwards.
                throw ProjectionSessionController.CaptureExitUnconfirmedException(
                    "CLEANUP_UNCONFIRMED $proof", failure, receiverClean)
            }
            throw ProjectionSessionController.CleanupUnconfirmedException("CLEANUP_UNCONFIRMED $proof", failure)
        }
        if (failure != null) {
            throw failure
        }
    }

    private companion object {
        fun collect(first: IOException?, next: Exception): IOException {
            if (first == null) {
                return IOException("Owned cleanup failed", next)
            }
            first.addSuppressed(next)
            return first
        }

        fun openCleanupUnconfirmed(error: Throwable?): Boolean {
            if (error == null) {
                return false
            }
            val message = error.message
            if (message != null && message.contains("CLEANUP_UNCONFIRMED")) {
                return true
            }
            for (suppressed in error.suppressed) {
                if (openCleanupUnconfirmed(suppressed)) {
                    return true
                }
            }
            return openCleanupUnconfirmed(error.cause)
        }
    }
}
