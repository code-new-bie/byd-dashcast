package com.byd.dashcast.phase1

import java.io.IOException
import java.util.Objects
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Paced surface input with bounded EOS drain; independent of Android for regression tests.
 * Output/frame accounting must come from the codec, never from the submitted input count.
 *
 * @author Codex
 * @date 2026/10/10
 */
internal object SurfaceEncoderPump {
    private const val SECOND_NANOS = 1_000_000_000L
    private const val EOS_TIMEOUT_NANOS = 5 * SECOND_NANOS
    private const val POLL_NANOS = 1_000_000L

    /** Returns input frames only after output EOS and exact output accounting. */
    @JvmStatic
    @Throws(IOException::class)
    fun run(seconds: Int, fps: Int, cancelled: AtomicBoolean?, backend: Backend?): Int {
        if (seconds < 1 || seconds > 60 || fps < 1 || fps > 60) {
            throw IllegalArgumentException("Duration/fps outside bounded validation range")
        }
        Objects.requireNonNull(cancelled)
        Objects.requireNonNull(backend)
        cancelled!!
        backend!!
        val start = backend.nanoTime()
        var queued = 0
        var inputEos = false
        var eosStarted = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) {
                cancelled.set(true)
            }
            // Drain BEFORE swapBuffers: a full codec output queue must not block surface input.
            if (backend.drain()) {
                if (!inputEos || backend.outputFrames() != queued) {
                    throw IOException("Incomplete encoder output: queued=" + queued +
                        " received=" + backend.outputFrames() + " inputEOS=" + inputEos)
                }
                return queued
            }
            var now = backend.nanoTime()
            if (!inputEos) {
                if (cancelled.get() || queued >= seconds * fps) {
                    backend.signalEndOfInput()
                    inputEos = true
                    eosStarted = backend.nanoTime()
                } else if (now - start >= queued * SECOND_NANOS / fps) {
                    backend.render(queued, 1_000_000L + queued * 1_000_000L / fps)
                    queued++
                }
            }
            now = backend.nanoTime()
            if (inputEos && now - eosStarted > EOS_TIMEOUT_NANOS) {
                throw IOException("Encoder EOS timeout; incomplete output")
            }
            if (!inputEos && now - start > (seconds + 10L) * SECOND_NANOS) {
                throw IOException("Encoder input stalled; output incomplete")
            }
            backend.pause(POLL_NANOS)
        }
    }

    /** Only the production adapter touches MediaCodec/EGL. */
    interface Backend {
        fun nanoTime(): Long

        @Throws(IOException::class)
        fun drain(): Boolean

        fun outputFrames(): Int

        @Throws(IOException::class)
        fun render(frame: Int, ptsMicros: Long)

        @Throws(IOException::class)
        fun signalEndOfInput()

        fun pause(nanos: Long)
    }
}
