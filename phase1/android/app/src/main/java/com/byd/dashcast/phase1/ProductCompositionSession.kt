package com.byd.dashcast.phase1

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.locks.LockSupport
import java.util.function.LongSupplier

/** Healthy owned-stream renewal; the HMI independently restores within five seconds of silence.
 * @author Codex
 * @date 2026/10/21
 */
internal class ProductCompositionSession(
    private val log: NoAuthVideoClient.Logger,
    private val clock: LongSupplier,
    private val nonce: Int
) : CompositionControl {
    private var before: InstrumentBackgroundPoc.Reply? = null
    private var sent = false
    private var restored = false
    private var active = false
    private var drawingConfirmed = false
    private var began = 0L
    private var confirmed = 0L
    private var lastPoll = 0L
    private var lastRenew = 0L

    init { require(nonce > 0) { "PRODUCT_COMPOSITION_NONCE" } }

    override fun prepare(channel: NoAuthVideoTransport) {
        check(!sent && before == null) { "PRODUCT_COMPOSITION_SINGLE_USE" }
        val reply = exchange(channel, QUERY)
        requireReply(reply)
        if (reply.word(31) != CAPABILITY || reply.word(30) != 0 || reply.word(29) != NAVIGATION_READY) {
            throw IOException("PRODUCT_MATCHING_FIRMWARE_REQUIRED")
        }
        if (reply.word(9) == 1 || reply.word(3) != reply.word(8)) {
            throw IOException("PRODUCT_BACKGROUND_ALREADY_ACTIVE_OR_PENDING")
        }
        before = reply
        log.log("PRODUCT_CAPABILITY_READY lease_ms=5000 total_duration=unlimited layout=native-navigation")
    }

    override fun activate(channel: NoAuthVideoTransport) {
        if (sent || before == null) { throw IOException("PRODUCT_COMPOSITION_START_REPLAY") }
        sent = true
        began = clock.asLong
        confirmed = began
        lastRenew = began
        val reply = exchange(channel, START)
        requireQueued(reply)
        log.log("PRODUCT_COMPOSITION_STARTED; same-stream bounded lease, no permanent transparency")
    }

    override fun tick(channel: NoAuthVideoTransport) {
        if (!sent || restored) { return }
        val now = clock.asLong
        if (now < confirmed || now - confirmed >= CONFIRM_DEADLINE) {
            throw IOException("PRODUCT_LEASE_CONFIRMATION_LOST")
        }
        if (now - lastPoll < POLL_INTERVAL) { return }
        lastPoll = now
        val reply = exchange(channel, QUERY)
        if (reply.error == 9) { return }
        requireReply(reply)
        if (reply.word(3) != reply.word(8) || reply.word(21) != nonce) {
            if (now - began >= ACTIVATION_DEADLINE) { throw IOException("PRODUCT_OWNER_ACTIVATION_UNCONFIRMED") }
            return
        }
        if (reply.word(11) != 0 || reply.word(9) == 2) {
            throw IOException("PRODUCT_NATIVE_LEASE_REVOKED reason=${reply.word(11)}")
        }
        if (reply.word(9) != 1) {
            if (now - began >= ACTIVATION_DEADLINE) { throw IOException("PRODUCT_ACTIVATION_UNCONFIRMED") }
            return
        }
        if (reply.word(29) != NAVIGATION_ACTIVE) {
            throw IOException("PRODUCT_NAVIGATION_LAYOUT_UNCONFIRMED")
        }
        if (!active) {
            active = true
            log.log("PRODUCT_NATIVE_BACKGROUND_ACTIVE layout=native-navigation; OEM data/alerts retained")
        }
        if (now - lastRenew >= RENEW_INTERVAL) {
            if (!drawingConfirmed && (delta(reply, 16) == 0L || delta(reply, 17) == 0L)) {
                throw IOException("PRODUCT_BACKGROUND_DRAW_NOT_CONFIRMED")
            }
            drawingConfirmed = true
            val renewed = exchange(channel, RENEW)
            requireQueued(renewed)
            lastRenew = now
        }
        // A fresh HMI snapshot confirms the current lease, not physical decoder/display FPS.
        confirmed = now
    }

    override fun restore(channel: NoAuthVideoTransport) {
        if (!sent || restored) { return }
        val result = exchange(channel, RESTORE)
        requireQueued(result)
        repeat(60) {
            val reply = exchange(channel, QUERY)
            if (reply.error == 0) {
                requireReply(reply)
                if (reply.word(3) == reply.word(8) && reply.word(21) == nonce && reply.word(9) == 2 &&
                    reply.word(29) == NAVIGATION_READY) {
                    restored = true
                    log.log("PRODUCT_NATIVE_RESTORE_CONFIRMED; media STOP follows")
                    return
                }
            } else if (reply.error != 9) {
                throw IOException("PRODUCT_RESTORE_REJECTED error=${reply.error}")
            }
            LockSupport.parkNanos(25_000_000L)
        }
        throw IOException("PRODUCT_RESTORE_UNCONFIRMED; independent HMI expiry remains bounded")
    }

    private fun requireReply(reply: InstrumentBackgroundPoc.Reply) {
        val original = before
        if (reply.error != 0 || original != null &&
            (reply.word(10) != original.word(10) || reply.word(18) != original.word(18))) {
            throw IOException("PRODUCT_HMI_CHANGED_OR_UNAVAILABLE error=${reply.error}")
        }
    }

    private fun requireQueued(reply: InstrumentBackgroundPoc.Reply) {
        if (reply.error != 0 && reply.error != 9) {
            throw IOException("PRODUCT_LEASE_REQUEST_REJECTED error=${reply.error}")
        }
    }

    private fun delta(reply: InstrumentBackgroundPoc.Reply, word: Int): Long =
        (reply.unsigned(word) - requireNotNull(before).unsigned(word)) and 0xffffffffL

    private fun exchange(channel: NoAuthVideoTransport, operation: Int): InstrumentBackgroundPoc.Reply =
        InstrumentCompositionTrial.exchangePacket(channel, operation, request(operation, nonce))

    companion object {
        const val CAPABILITY = 0x52574e32
        const val NAVIGATION_READY = 0x4e415600
        const val NAVIGATION_ACTIVE = 0x4e415601
        private const val QUERY = 0
        private const val START = 1
        private const val RESTORE = 2
        private const val RENEW = 3
        private const val POLL_INTERVAL = 250_000_000L
        private const val RENEW_INTERVAL = 1_000_000_000L
        private const val CONFIRM_DEADLINE = 4_000_000_000L
        private const val ACTIVATION_DEADLINE = 1_500_000_000L

        @JvmStatic
        fun request(operation: Int, nonce: Int): ByteArray {
            require(operation in QUERY..RENEW && nonce > 0) { "PRODUCT_LEASE_REQUEST" }
            if (operation != RENEW) {
                return InstrumentBackgroundPoc.request(operation, if (operation == QUERY) 0 else nonce)
            }
            return ByteBuffer.allocate(40).putInt(0x44435631).putInt(10).putInt(16)
                .putInt(0).putInt(0).putInt(0).putInt(1).putInt(RENEW).putInt(nonce).putInt(5000).array()
        }
    }
}
