package com.byd.dashcast.phase1

import java.io.IOException
import java.util.Objects
import java.util.concurrent.locks.LockSupport
import java.util.function.LongSupplier

/**
 * One non-renewable native-background lease attached to actual full-size video.
 * @author Codex
 * @date 2026/10/20
 */
internal class InstrumentCompositionTrial(
    log: NoAuthVideoClient.Logger?,
    clock: LongSupplier?,
    private val nonce: Int,
    stop: Runnable?,
) : CompositionControl {
    private val log: NoAuthVideoClient.Logger
    private val clock: LongSupplier
    private val stop: Runnable
    private var before: InstrumentBackgroundPoc.Reply? = null
    private var sent = false
    private var active = false
    private var restored = false
    private var began = 0L
    private var lastPoll = 0L

    init {
        if (nonce <= 0) {
            throw IllegalArgumentException("COMPOSITION_NONCE")
        }
        this.log = Objects.requireNonNull(log)!!
        this.clock = Objects.requireNonNull(clock)!!
        this.stop = Objects.requireNonNull(stop)!!
    }

    @Throws(IOException::class)
    override fun prepare(channel: NoAuthVideoTransport) {
        val reply = exchange(channel, InstrumentBackgroundPoc.QUERY, 0)
        before = reply
        requireSame(reply)
        if (reply.word(9) == 1 || reply.word(3) != reply.word(8)) {
            throw IOException("COMPOSITION_BACKGROUND_ALREADY_ACTIVE_OR_PENDING")
        }
        log.log("COMPOSITION_PREPARED native_pid=" + reply.word(10) + " epoch=" + reply.word(18) +
            "; fixed full-size underlay, not a physical composition certification")
    }

    @Throws(IOException::class)
    override fun activate(channel: NoAuthVideoTransport) {
        if (sent || before == null) {
            throw IOException("COMPOSITION_START_REPLAY_OR_NOT_PREPARED")
        }
        sent = true
        began = clock.asLong
        val reply = exchange(channel, InstrumentBackgroundPoc.START, nonce)
        if (reply.error != 0 && reply.error != 9) {
            throw IOException("COMPOSITION_START_REJECTED error=" + reply.error)
        }
        log.log("COMPOSITION_VIDEO_SUBMITTED_BACKGROUND_REQUESTED hold_ms=5000; no renewal")
    }

    @Throws(IOException::class)
    override fun tick(channel: NoAuthVideoTransport) {
        if (!sent || restored) {
            return
        }
        val now = clock.asLong
        if (now - began < 0 || now - began > MAX_ACTIVE_NANOS) {
            throw IOException("COMPOSITION_RESTORE_DEADLINE")
        }
        if (now - lastPoll < POLL_NANOS) {
            return
        }
        lastPoll = now
        val reply = exchange(channel, InstrumentBackgroundPoc.QUERY, 0)
        if (reply.error == 9) {
            return
        }
        requireSame(reply)
        if (reply.word(3) != reply.word(8) || reply.word(21) != nonce) {
            return
        }
        if (reply.word(11) != 0) {
            throw IOException("COMPOSITION_HMI_REJECTED reason=" + reply.word(11))
        }
        if (reply.word(9) == 1 && !active) {
            active = true
            log.log("COMPOSITION_HMI_ACTIVE; observe video underneath native speed/gear/warnings")
        }
        if (reply.word(9) == 2) {
            if (!active || delta(reply, 16) == 0L || delta(reply, 17) == 0L) {
                throw IOException("COMPOSITION_DRAW_NOT_CONFIRMED")
            }
            restored = true
            log.log("COMPOSITION_NATIVE_RESTORED bg_skips=" + delta(reply, 16) +
                " root_skips=" + delta(reply, 17) + "; visual confirmation still required")
            stop.run()
        }
    }

    @Throws(IOException::class)
    override fun restore(channel: NoAuthVideoTransport) {
        if (!sent || restored) {
            return
        }
        exchange(channel, InstrumentBackgroundPoc.RESTORE, nonce)
        repeat(60) {
            val reply = exchange(channel, InstrumentBackgroundPoc.QUERY, 0)
            if (reply.error == 0) {
                requireSame(reply)
                if (reply.word(9) == 2 && reply.word(3) == reply.word(8) && reply.word(21) == nonce) {
                    restored = true
                    log.log("COMPOSITION_EARLY_RESTORE_CONFIRMED; then native media STOP")
                    return
                }
            } else if (reply.error != 9) {
                throw IOException("COMPOSITION_RESTORE_UNAVAILABLE error=" + reply.error)
            }
            LockSupport.parkNanos(25_000_000L)
        }
        throw IOException("COMPOSITION_RESTORE_UNCONFIRMED; native five-second expiry remains independent")
    }

    private fun delta(reply: InstrumentBackgroundPoc.Reply, index: Int): Long =
        (reply.unsigned(index) - before!!.unsigned(index)) and 0xffffffffL

    @Throws(IOException::class)
    private fun requireSame(reply: InstrumentBackgroundPoc.Reply) {
        val first = before
        if (reply.error != 0 ||
            (first != null && (reply.word(10) != first.word(10) || reply.word(18) != first.word(18)))) {
            throw IOException("COMPOSITION_HMI_CHANGED_OR_NOT_READY error=" + reply.error)
        }
    }

    companion object {
        private const val POLL_NANOS = 100_000_000L
        private const val MAX_ACTIVE_NANOS = 7_000_000_000L

        @Throws(IOException::class)
        private fun exchange(channel: NoAuthVideoTransport, operation: Int, nonce: Int): InstrumentBackgroundPoc.Reply =
            exchangePacket(channel, operation, InstrumentBackgroundPoc.request(operation, nonce))

        @JvmStatic
        @Throws(IOException::class)
        fun exchangePacket(channel: NoAuthVideoTransport, operation: Int, request: ByteArray): InstrumentBackgroundPoc.Reply {
            channel.write(request)
            InstrumentBackgroundPoc.validateHeader(channel.readExact(NoAuthVideoProtocol.HEADER_BYTES))
            val bytes = ByteArray(InstrumentBackgroundPoc.REPLY_BYTES)
            var offset = 0
            while (offset < bytes.size) {
                val count = minOf(NoAuthVideoProtocol.OFFER_BYTES, bytes.size - offset)
                val part = channel.readExact(count)
                if (part.size != count) {
                    throw IOException("COMPOSITION_SHORT_REPLY")
                }
                System.arraycopy(part, 0, bytes, offset, count)
                offset += count
            }
            return InstrumentBackgroundPoc.parseReply(bytes, operation)
        }
    }
}
