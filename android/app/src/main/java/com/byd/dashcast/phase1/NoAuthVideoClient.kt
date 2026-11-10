package com.byd.dashcast.phase1

import java.io.Closeable
import java.io.IOException
import java.util.Objects
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import java.util.function.LongSupplier

/**
 * Single-use DCV1 client. STOP stays on the same ordered USB connection.
 * No credentials and no Wi-Fi/mobile/legacy-protocol fallback.
 * @author Codex
 * @date 2026/10/11
 */
internal class NoAuthVideoClient(
    connector: Connector?,
    cancelled: AtomicBoolean?,
    logger: Logger?,
    nanoTime: LongSupplier?,
    private val continuous: Boolean,
) : Closeable {
    fun interface Connector {
        @Throws(IOException::class)
        fun connect(port: Int, cancelled: AtomicBoolean): NoAuthVideoTransport
    }

    fun interface Logger {
        fun log(message: String)
    }

    private val connector: Connector = Objects.requireNonNull(connector, "connector")!!
    private val cancelled: AtomicBoolean = Objects.requireNonNull(cancelled, "cancelled")!!
    private val logger: Logger = Objects.requireNonNull(logger, "logger")!!
    private val nanoTime: LongSupplier = Objects.requireNonNull(nanoTime, "nanoTime")!!
    private var video: NoAuthVideoTransport? = null
    private var evidence: NoAuthVideoProtocol.Reply? = null
    private var negotiated = false
    private var started = false
    private var closed = false
    private var terminalCleanup = false
    private var rejected = false
    private var acknowledged = 0L
    private var unlimited = false
    private var serials: StreamSerials? = null
    private var heartbeats = 0
    private var lastHeartbeat = 0L
    private var firstPts = -1L
    private var previousPtsMillis = -1L
    private var payloadBytes = 0L
    private var cleanupConfirmed = true
    private var maxAckNanos = 0L
    private var totalAckNanos = 0L
    private var displayProbe: InstrumentDisplayProbe? = null
    private var composition: CompositionControl? = null
    private var productProfile = false
    private var centerWindow = false
    private var compositionActivated = false
    private var productSourceBegan = 0L

    constructor(connector: Connector?, cancelled: AtomicBoolean?, logger: Logger?) :
        this(connector, cancelled, logger, LongSupplier { System.nanoTime() }, false)

    constructor(connector: Connector?, cancelled: AtomicBoolean?, logger: Logger?, nanoTime: LongSupplier?) :
        this(connector, cancelled, logger, nanoTime, false)

    constructor(connector: Connector?, cancelled: AtomicBoolean?, logger: Logger?, continuous: Boolean) :
        this(connector, cancelled, logger, LongSupplier { System.nanoTime() }, continuous)

    @Throws(IOException::class)
    fun enableUnlimitedStream() {
        if (video != null || closed || !continuous) {
            throw IOException("UNLIMITED_REQUIRES_FRESH_CONTINUOUS_SESSION")
        }
        unlimited = true
        serials = StreamSerials()
    }

    @JvmOverloads
    @Throws(IOException::class)
    fun enableProductComposition(nonce: Int, centerWindow: Boolean = false) {
        if (video != null || closed || !continuous) {
            throw IOException("PRODUCT_COMPOSITION_REQUIRES_FRESH_CONTINUOUS_SESSION")
        }
        composition = ProductCompositionSession(logger, nanoTime, nonce, centerWindow)
        productProfile = true
        this.centerWindow = centerWindow
    }

    @Throws(IOException::class)
    fun enableCompositionTrial(nonce: Int, stopRequested: Runnable) {
        if (video != null || closed || !continuous) {
            throw IOException("COMPOSITION_REQUIRES_FRESH_CONTINUOUS_SESSION")
        }
        composition = InstrumentCompositionTrial(logger, nanoTime, nonce, stopRequested)
    }

    /** Explicit F1 diagnostic only; normal projection never sends the new operation. */
    @Throws(IOException::class)
    fun enableDisplayReadback() {
        if (video != null || closed || continuous) {
            throw IOException("F1_REQUIRES_FRESH_SHORT_DIAGNOSTIC")
        }
        displayProbe = InstrumentDisplayProbe(cancelled, logger, nanoTime)
    }

    @Throws(IOException::class)
    fun begin() {
        checkCancellation()
        if (video != null || closed) {
            throw IOException("DCV1_SESSION_CANNOT_BE_REUSED")
        }
        val channel = connector.connect(NoAuthVideoProtocol.PORT, cancelled)
        video = channel
        try {
            val header = channel.readExact(NoAuthVideoProtocol.HEADER_BYTES)
            val offer = channel.readExact(NoAuthVideoProtocol.OFFER_BYTES)
            NoAuthVideoProtocol.validateOffer(header, offer)
            composition?.prepare(channel)
            displayProbe?.negotiate(channel)
            exchange(if (unlimited) NoAuthVideoProtocol.helloUnlimited() else NoAuthVideoProtocol.hello(continuous),
                NoAuthVideoProtocol.HELLO, 0, 0)
            negotiated = true
            cleanupConfirmed = false
            lastHeartbeat = nanoTime.asLong
            val reply = exchange(
                if (centerWindow) NoAuthVideoProtocol.startCenterWindow() else NoAuthVideoProtocol.start(composition != null),
                NoAuthVideoProtocol.START, 0, 0)
            if (reply.flags and 1 == 0 || reply.submissions != 0) {
                throw IOException("DCV1_MEDIA_CONTEXT_NOT_READY")
            }
            started = true
            productSourceBegan = nanoTime.asLong
            logger.log("RECEIVER_READY DCV1/" + (if (unlimited) 3 else if (continuous) 2 else 1) +
                " no video authorization; " + NoAuthVideoProtocol.WIDTH + "x" + NoAuthVideoProtocol.HEIGHT + "/" +
                NoAuthVideoProtocol.FPS + "fps; NOT picture confirmation")
        } catch (error: IOException) {
            try {
                close()
            } catch (cleanupError: IOException) {
                error.addSuppressed(cleanupError)
            }
            throw error
        }
    }

    @Throws(IOException::class)
    fun frame(annexB: ByteArray?, ptsMicros: Long) {
        checkCancellation()
        if (!started || closed || rejected || ptsMicros < 0) {
            throw IOException("DCV1_STREAM_NOT_READY_OR_INVALID_PTS")
        }
        if (firstPts < 0) {
            firstPts = ptsMicros
        }
        val relativeMillis = (ptsMicros - firstPts) / 1000
        if (ptsMicros < firstPts || relativeMillis < previousPtsMillis ||
            (previousPtsMillis >= 0 && relativeMillis - previousPtsMillis > 2000)) {
            throw IOException("DCV1_NON_MONOTONIC_OR_GAPPED_PTS")
        }
        val sequence = if (unlimited) serials!!.nextFrame() else (acknowledged + 1).toInt()
        val wirePts = if (unlimited) relativeMillis and ContinuousSessionPolicy.UINT32_MASK else relativeMillis
        val packet = if (unlimited) {
            NoAuthVideoProtocol.frameUnlimited(annexB, sequence, wirePts, acknowledged == 0L)
        } else {
            NoAuthVideoProtocol.frame(annexB, sequence, wirePts, continuous)
        }
        // ADB pulses before each read, but cannot pulse INSIDE a batched stdout
        // callback while it synchronously paces/sends many pictures. A legal
        // <=4096-byte WRTE of tiny AUs can occupy seconds under ACK backpressure.
        // Service media and HMI on this same ordered owner before another FRAME.
        maintain()
        val ackStart = nanoTime.asLong
        val reply = exchange(packet, NoAuthVideoProtocol.FRAME, sequence, wirePts)
        evidence = reply
        val ackNanos = maxOf(0L, nanoTime.asLong - ackStart)
        maxAckNanos = maxOf(maxAckNanos, ackNanos)
        totalAckNanos = ContinuousSessionPolicy.add(totalAckNanos, ackNanos)
        if (unlimited) {
            serials!!.acceptFrame(sequence)
        }
        acknowledged = ContinuousSessionPolicy.add(acknowledged, 1)
        // A cold launcher can emit a short blank animation and then leave its display.
        // Never hide OEM background on that initial FRAME ACK alone.
        val control = composition
        if (control != null && !compositionActivated && ((!productProfile && acknowledged == 1L) ||
                (productProfile && ProductVideoReadiness.canActivate(acknowledged, relativeMillis)))) {
            control.activate(video!!)
            compositionActivated = true
        }
        previousPtsMillis = relativeMillis
        payloadBytes = ContinuousSessionPolicy.add(payloadBytes, annexB!!.size.toLong())
        displayProbe?.step(video!!)
        if (acknowledged == 1L || acknowledged % (if (continuous) 150 else 30) == 0L) {
            logger.log(reply.summary())
            if (continuous) {
                logger.log(statistics())
            }
        }
    }

    @Throws(IOException::class)
    private fun heartbeat() {
        val counter = if (unlimited) serials!!.nextHeartbeat() else ++heartbeats
        val reply = exchange(
            if (unlimited) NoAuthVideoProtocol.heartbeatUnlimited(counter) else NoAuthVideoProtocol.heartbeat(counter, continuous),
            NoAuthVideoProtocol.HEARTBEAT, 0, 0)
        evidence = reply
        if (reply.submissions != (if (unlimited) serials!!.frameBits() else acknowledged.toInt())) {
            throw IOException("DCV1_HEARTBEAT_SUBMISSION_MISMATCH")
        }
        lastHeartbeat = nanoTime.asLong
    }

    @Throws(IOException::class)
    fun requireWindowReport() {
        checkCancellation()
        if (!started || closed || rejected || acknowledged < 2) {
            throw IOException("WINDOW_UNCONFIRMED: fewer than two submitted frames")
        }
        for (attempt in 0 until 3) {
            heartbeat()
            val reply = evidence!!
            logger.log(reply.summary())
            if (reply.windowReported()) {
                logger.log("RECEIVER_WINDOW_REPORTED: Screen visible property, NOT observed motion/decoded-frame count")
                return
            }
            if (attempt < 2) {
                LockSupport.parkNanos(100_000_000L)
                checkCancellation()
            }
        }
        throw IOException("WINDOW_UNCONFIRMED: receiver did not report its visible video window")
    }

    /** Called by both FRAME and idle, serialized on the FRAME/STOP owner. */
    @Throws(IOException::class)
    fun maintain() {
        if (!started || closed || rejected) {
            return
        }
        if (productProfile && !compositionActivated && nanoTime.asLong - productSourceBegan >= 12_000_000_000L) {
            throw IOException("PRODUCT_SOURCE_NOT_READY: background remains unchanged; map startup produced insufficient video")
        }
        checkCancellation()
        if (nanoTime.asLong - lastHeartbeat >= 1_000_000_000L) {
            heartbeat()
        }
        composition?.tick(video!!)
    }

    @Throws(IOException::class)
    private fun exchange(request: ByteArray, type: Int, sequence: Int, pts: Long): NoAuthVideoProtocol.Reply {
        val channel = video!!
        channel.write(request)
        val header = channel.readExact(NoAuthVideoProtocol.HEADER_BYTES)
        val payload = channel.readExact(NoAuthVideoProtocol.ACK_BYTES)
        try {
            return if (unlimited) {
                NoAuthVideoProtocol.replyUnlimited(header, payload, type, sequence, pts)
            } else {
                NoAuthVideoProtocol.reply(header, payload, type, sequence, pts, continuous)
            }
        } catch (error: NoAuthVideoProtocol.Rejected) {
            rejected = true
            terminalCleanup = error.terminalCleanupReported()
            logger.log(error.message.toString())
            throw error
        }
    }

    @Throws(IOException::class)
    private fun checkCancellation() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) {
            throw IOException("Video verification cancelled")
        }
    }

    fun statistics(): String = "DCV1 acknowledged=" + acknowledged + " H264 bytes=" + payloadBytes +
        " source_duration_ms=" + maxOf(0L, previousPtsMillis) +
        " max_ack_ms=" + maxAckNanos / 1_000_000L +
        " mean_ack_ms=" + (if (acknowledged == 0L) 0L else totalAckNanos / acknowledged / 1_000_000L) +
        "; ACK timing is NOT end-to-end display latency"

    fun cleanupConfirmed(): Boolean = cleanupConfirmed

    fun readyForStreaming(): Boolean = !productProfile || compositionActivated

    /** Do not wait for RPC replies on a physically removed device, or claim remote success. */
    @Throws(IOException::class)
    fun closeAfterPhysicalDetach() {
        if (closed) {
            return
        }
        closed = true
        cleanupConfirmed = false
        val channel = video
        if (channel != null) {
            try {
                channel.close()
            } finally {
                video = null
            }
        }
        logger.log("PHYSICAL_DETACH_REMOTE_CLEANUP_PENDING; no fabricated STOP/restore acknowledgement")
    }

    @Throws(IOException::class)
    private fun instrumentText(file: Int): String {
        val channel = video!!
        channel.write(NoAuthVideoProtocol.logRequest(file))
        val length = NoAuthVideoProtocol.logReplyLength(channel.readExact(NoAuthVideoProtocol.HEADER_BYTES))
        return if (length == 0) "" else String(channel.readExact(length), Charsets.UTF_8)
    }

    /** Best effort, after STOP: an instrument without this message simply closes the connection. */
    private fun collectInstrumentLogs() {
        val names = arrayOf("usb.log", "cast.log")
        try {
            val version = instrumentText(NoAuthVideoProtocol.LOG_FILE_VERSION)
            if (version.isNotEmpty()) {
                InstrumentVersionMemo.report(version)
                logger.log("INSTRUMENT_VERSION $version")
            }
            for (file in names.indices) {
                val text = instrumentText(file)
                InstrumentLogStore.save(names[file], text)
                logger.log("INSTRUMENT_LOG " + names[file] + " bytes=" + text.toByteArray(Charsets.UTF_8).size)
            }
        } catch (error: IOException) {
            logger.log("INSTRUMENT_LOG_UNAVAILABLE " + error.message)
        }
    }

    @Throws(IOException::class)
    override fun close() {
        if (closed) {
            return
        }
        closed = true
        displayProbe?.end()
        val channel = video ?: return
        val restoreInterrupt = Thread.interrupted()
        var backgroundFailure: IOException? = null
        try {
            channel.beginCleanup()
            val control = composition
            if (control != null) {
                try {
                    control.restore(channel)
                } catch (error: IOException) {
                    backgroundFailure = error
                }
            }
            if (terminalCleanup) {
                cleanupConfirmed = true
                logger.log("ERROR_CLEANUP_REPORTED: negative FRAME ACK followed native cleanup; no STOP on closed peer")
            } else if (negotiated) {
                val reply = exchange(NoAuthVideoProtocol.stop(), NoAuthVideoProtocol.STOP, 0, 0)
                if (!reply.cleanupReported()) {
                    throw IOException("DCV1 receiver did not report media/window cleanup")
                }
                cleanupConfirmed = true
                logger.log("STOP_CLEANUP_REPORTED: native media/window cleanup returned 0; manually check window exit")
                collectInstrumentLogs()
            }
            if (backgroundFailure != null) {
                cleanupConfirmed = false
                throw backgroundFailure
            }
        } catch (error: IOException) {
            if (backgroundFailure != null && backgroundFailure !== error) {
                error.addSuppressed(backgroundFailure)
            }
            logger.log("STOP_UNCONFIRMED " + error.message + "; do not retry before checking instrument/window")
            throw error
        } finally {
            channel.close()
            video = null
            if (restoreInterrupt) {
                Thread.currentThread().interrupt()
            }
            logger.log(statistics() + "; USB role NOT automatically changed")
        }
    }
}
