package com.byd.dashcast.phase1

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Objects
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import java.util.function.LongSupplier

/**
 * Explicit stationary-only background experiment, not fullscreen projection.
 * The HMI owns the fixed deadline; APK cancellation/disconnect cannot extend it.
 * @author Codex
 * @date 2026/10/20
 */
internal class InstrumentBackgroundPoc(
    cancelled: AtomicBoolean?,
    logger: NoAuthVideoClient.Logger?,
    private val nonce: Int,
    nanoTime: LongSupplier?,
    waiter: Waiter?,
) {
    /** Bounded injectable pause; production waits on the existing probe worker. */
    fun interface Waiter {
        /** Wait without creating another thread. */
        fun pause(nanos: Long)
    }

    private val cancelled: AtomicBoolean
    private val logger: NoAuthVideoClient.Logger
    private val nanoTime: LongSupplier
    private val waiter: Waiter
    private var requests = 0
    private var used = false

    constructor(cancelled: AtomicBoolean?, logger: NoAuthVideoClient.Logger?, nonce: Int) :
        this(cancelled, logger, nonce, LongSupplier { System.nanoTime() }, Waiter { LockSupport.parkNanos(it) })

    init {
        if (nonce <= 0) {
            throw IllegalArgumentException("BG_POC_INVALID_NONCE")
        }
        this.cancelled = Objects.requireNonNull(cancelled, "cancelled")!!
        this.logger = Objects.requireNonNull(logger, "logger")!!
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime")!!
        this.waiter = Objects.requireNonNull(waiter, "waiter")!!
    }

    /** Caller has validated the offer and exclusively owns this fresh NCM stream. */
    @Throws(IOException::class)
    fun run(channel: NoAuthVideoTransport) {
        if (used) {
            throw IOException("BG_POC_SINGLE_USE_ONLY")
        }
        used = true
        val began = nanoTime.asLong
        val before = awaitReady(channel, began)
        val pid = before.word(10)
        val epoch = before.word(18)
        var sent = false
        var restored = false
        var failure: IOException? = null
        try {
            checkTime(began)
            sent = true
            val queued = exchange(channel, START, nonce)
            if (queued.error != 0 && queued.error != 9) {
                throw IOException("BG_POC_START_REJECTED error=" + queued.error)
            }
            logger.log("BG_POC_REQUEST_SENT hold_ms=5000; queued is NOT HMI active/draw evidence")
            awaitRestored(channel, before, pid, epoch, began)
            restored = true
        } catch (error: IOException) {
            failure = error
            throw error
        } finally {
            if (sent && !restored) {
                try {
                    channel.beginCleanup()
                    val reply = exchange(channel, RESTORE, nonce)
                    logger.log("BG_POC_EARLY_RESTORE_REQUEST error=" + reply.error +
                        "; request is NOT restoration confirmation; HMI also expires independently")
                } catch (cleanupError: IOException) {
                    logger.log("BG_POC_RESTORE_UNCONFIRMED " + cleanupError.message +
                        "; observe instrument, do not retry; HMI deadline is independent of APK")
                    if (failure != null) {
                        failure.addSuppressed(cleanupError)
                    } else {
                        throw cleanupError
                    }
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun awaitReady(channel: NoAuthVideoTransport, began: Long): Reply {
        while (true) {
            checkTime(began)
            val reply = exchange(channel, QUERY, 0)
            if (reply.error == 0) {
                if (reply.word(9) == 1 || reply.word(3) != reply.word(8)) {
                    throw IOException("BG_POC_ALREADY_ACTIVE_OR_PENDING: no start or renewal sent")
                }
                logger.log("BG_POC_HMI_READY pid=" + reply.word(10) + " epoch=" + reply.word(18) +
                    " native_frames=" + reply.unsigned(24))
                return reply
            }
            if ((reply.error != 2 && reply.error != 3 && reply.error != 9) || nanoTime.asLong - began >= READY_NANOS) {
                throw IOException("BG_POC_HMI_NOT_READY error=" + reply.error + "; no start sent")
            }
            waiter.pause(POLL_NANOS)
        }
    }

    @Throws(IOException::class)
    private fun awaitRestored(channel: NoAuthVideoTransport, before: Reply, pid: Int, epoch: Int, began: Long) {
        var active = false
        while (true) {
            checkTime(began)
            waiter.pause(POLL_NANOS)
            checkTime(began)
            val reply = exchange(channel, QUERY, 0)
            if (reply.error == 9) {
                continue
            }
            if (reply.error != 0 || reply.word(10) != pid || reply.word(18) != epoch) {
                throw IOException("BG_POC_HMI_CHANGED_OR_UNAVAILABLE error=" + reply.error)
            }
            val ours = reply.word(21) == nonce && reply.word(3) == reply.word(8)
            if (!ours) {
                continue
            }
            if (reply.word(11) != 0) {
                throw IOException("BG_POC_HMI_REJECTED reason=" + reply.word(11))
            }
            if (reply.word(9) == 1 && !active) {
                active = true
                logger.log("BG_POC_HMI_ACTIVE deadline=" + reply.unsigned(14) + ":" + reply.unsigned(15) +
                    "; native foreground path unchanged, NOT a visual/safety certification")
            }
            if (reply.word(9) == 2 && active) {
                val bgDraws = (reply.unsigned(16) - before.unsigned(16)) and 0xffffffffL
                val rootDraws = (reply.unsigned(17) - before.unsigned(17)) and 0xffffffffL
                logger.log("BG_POC_NATIVE_RESTORED bg_skips=" + bgDraws + " root_skips=" + rootDraws +
                    " restore_frame=" + reply.unsigned(25) + "; still requires visual confirmation")
                if (bgDraws == 0L || rootDraws == 0L || bgDraws > 100000 || rootDraws > 100000) {
                    throw IOException("BG_POC_DRAW_NOT_OBSERVED: restored, but background hooks not both observed")
                }
                logger.log("背景控制往返完成：请人工确认背景确实消失约5秒后恢复、车速/挡位/告警全程保留。" +
                    "这不是全屏或道路安全验收。USB模式未改变。")
                return
            }
        }
    }

    @Throws(IOException::class)
    private fun exchange(channel: NoAuthVideoTransport, operation: Int, requestNonce: Int): Reply {
        if (++requests > MAX_REQUESTS + 1) {
            throw IOException("BG_POC_REQUEST_LIMIT")
        }
        channel.write(request(operation, requestNonce))
        val header = channel.readExact(NoAuthVideoProtocol.HEADER_BYTES)
        validateHeader(header)
        val body = ByteArray(REPLY_BYTES)
        var offset = 0
        while (offset < body.size) {
            val count = minOf(NoAuthVideoProtocol.OFFER_BYTES, body.size - offset)
            val part = channel.readExact(count)
            if (part.size != count) {
                throw IOException("BG_POC_SHORT_REPLY")
            }
            System.arraycopy(part, 0, body, offset, count)
            offset += count
        }
        return parseReply(body, operation)
    }

    @Throws(IOException::class)
    private fun checkTime(began: Long) {
        val elapsed = nanoTime.asLong - began
        if (cancelled.get() || Thread.currentThread().isInterrupted) {
            throw IOException("BG_POC_CANCELLED")
        }
        if (elapsed < 0 || elapsed >= TOTAL_NANOS || requests >= MAX_REQUESTS) {
            throw IOException("BG_POC_DEADLINE_OR_LIMIT: restoration unconfirmed; do not retry")
        }
    }

    /** Immutable internal wire snapshot; never exposes HMI pointers. */
    class Reply(@JvmField val error: Int, words: IntArray) {
        private val words: IntArray = words.clone()

        fun word(index: Int): Int = words[index]

        fun unsigned(index: Int): Long = Integer.toUnsignedLong(words[index])
    }

    companion object {
        const val QUERY = 0
        const val START = 1
        const val RESTORE = 2
        const val HOLD_MILLIS = 5000
        const val REPLY_BYTES = 144
        private const val OPERATION = 10
        private const val REQUEST_BYTES = 16
        private const val SCHEMA = 1
        private const val FINGERPRINT = 0x42475031
        private const val CONTROL_MAGIC = 0x31474244
        private const val POLL_NANOS = 100_000_000L
        private const val READY_NANOS = 3_000_000_000L
        private const val TOTAL_NANOS = 12_000_000_000L
        private const val MAX_REQUESTS = 100

        @JvmStatic
        @Throws(IOException::class)
        fun request(operation: Int, nonce: Int): ByteArray {
            if (operation < QUERY || operation > RESTORE || (if (operation == QUERY) nonce != 0 else nonce <= 0)) {
                throw IOException("BG_POC_INVALID_REQUEST")
            }
            return ByteBuffer.allocate(NoAuthVideoProtocol.HEADER_BYTES + REQUEST_BYTES).order(ByteOrder.BIG_ENDIAN)
                .putInt(0x44435631).putInt(OPERATION).putInt(REQUEST_BYTES).putInt(0).putInt(0).putInt(0)
                .putInt(SCHEMA).putInt(operation).putInt(nonce).putInt(if (operation == START) HOLD_MILLIS else 0).array()
        }

        @JvmStatic
        @Throws(IOException::class)
        fun validateHeader(header: ByteArray?) {
            if (header == null || header.size != NoAuthVideoProtocol.HEADER_BYTES) {
                throw IOException("BG_POC_INVALID_HEADER")
            }
            val values = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
            if (values.getInt() != 0x44435631 || values.getInt() != (OPERATION or 0x80) ||
                values.getInt() != REPLY_BYTES || values.getInt() != 0 || values.getInt() != 0 ||
                values.getInt() != 0) {
                throw IOException("BG_POC_MATCHING_FIRMWARE_REQUIRED: reply contract mismatch")
            }
        }

        @JvmStatic
        @Throws(IOException::class)
        fun parseReply(body: ByteArray?, operation: Int): Reply {
            if (body == null || body.size != REPLY_BYTES) {
                throw IOException("BG_POC_INVALID_REPLY_SIZE")
            }
            val buffer = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN)
            if (buffer.getInt() != SCHEMA || buffer.getInt() != FINGERPRINT || buffer.getInt() != operation) {
                throw IOException("BG_POC_INVALID_REPLY_SCHEMA")
            }
            val error = buffer.getInt()
            if (error < 0 || error > 9) {
                throw IOException("BG_POC_INVALID_ERROR")
            }
            val words = IntArray(32) { buffer.getInt() }
            val result = Reply(error, words)
            if (error == 0 && (words[0] != CONTROL_MAGIC || words[1] != SCHEMA || words[2] != 128 ||
                    words[10] <= 0 || words[18] == 0 || words[9] < 0 || words[9] > 2 ||
                    words[26] and 1 != 0 || words[3] and 1 != 0 || result.unsigned(13) >= 1_000_000_000L ||
                    result.unsigned(15) >= 1_000_000_000L)) {
                throw IOException("BG_POC_INVALID_HMI_SNAPSHOT")
            }
            if (error == 0 && words[9] == 1) {
                val remaining = (result.unsigned(14) - result.unsigned(12)) * 1_000_000_000L +
                    result.unsigned(15) - result.unsigned(13)
                if (remaining < 0 || remaining > HOLD_MILLIS * 1_000_000L) {
                    throw IOException("BG_POC_INVALID_HMI_DEADLINE")
                }
            }
            return result
        }
    }
}
