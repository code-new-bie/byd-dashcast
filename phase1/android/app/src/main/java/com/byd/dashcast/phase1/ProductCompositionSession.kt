package com.byd.dashcast.phase1

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.locks.LockSupport
import java.util.function.LongSupplier

/** Healthy owned-stream renewal; the HMI independently restores within five seconds of silence.
 * @author Codex
 * @date 2026/10/21
 */
internal class ProductCompositionSession @JvmOverloads constructor(
    private val log: NoAuthVideoClient.Logger,
    private val clock: LongSupplier,
    private val nonce: Int,
    private val centerWindow: Boolean = false
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
    private var requestBegan = 0L
    private var waitingAcknowledgement = false

    init { require(nonce > 0) { "PRODUCT_COMPOSITION_NONCE" } }

    override fun prepare(channel: NoAuthVideoTransport) {
        check(!sent && before == null) { "PRODUCT_COMPOSITION_SINGLE_USE" }
        val reply = exchange(channel, QUERY)
        requireReply(reply)
        if (reply.word(31) != CAPABILITY || reply.word(30) !in 0..1 || reply.word(29) != NAVIGATION_READY) {
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
        requestBegan = began
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
        if (!active && reply.word(3) != reply.word(8)) {
            // The receiver has queued this START, while the render-thread snapshot still
            // describes the preceding RESTORE (state=2/reason=0/previous owner). That is
            // not revocation of this session. Only our pending request gets this grace.
            if (reply.word(20) != nonce) { throw IOException("PRODUCT_INITIAL_PENDING_OWNER_CHANGED") }
            if (now - requestBegan >= ACTIVATION_DEADLINE) {
                throw IOException("PRODUCT_REQUEST_ACK_UNCONFIRMED")
            }
            if (!waitingAcknowledgement) {
                waitingAcknowledgement = true
                log.log("PRODUCT_INITIAL_ACK_PENDING requested=${reply.unsigned(3)} acknowledged=${reply.unsigned(8)} " +
                    "state=${reply.word(9)} reason=${reply.word(11)} owner=${reply.word(21)}; old restore is not revocation")
            }
            return
        }
        if (reply.word(11) != 0 || reply.word(9) == 2) {
            throw IOException("PRODUCT_NATIVE_LEASE_REVOKED reason=${reply.word(11)}")
        }
        if (reply.word(3) != reply.word(8)) {
            // A queued renewal can be observed before the render thread consumes it.
            // Bound that request's acknowledgement, NOT the age of the whole session.
            // Never renew or refresh confirmed while acknowledgement is pending.
            if (active && reply.word(21) != nonce) {
                throw IOException("PRODUCT_OWNER_CHANGED_WHILE_PENDING")
            }
            if (now - requestBegan >= ACTIVATION_DEADLINE) {
                throw IOException("PRODUCT_REQUEST_ACK_UNCONFIRMED")
            }
            if (!waitingAcknowledgement) {
                waitingAcknowledgement = true
                log.log("PRODUCT_REQUEST_ACK_PENDING requested=${reply.unsigned(3)} acknowledged=${reply.unsigned(8)}; no renewal replay")
            }
            return
        }
        if (waitingAcknowledgement) {
            waitingAcknowledgement = false
            log.log("PRODUCT_REQUEST_ACK_CONFIRMED sequence=${reply.unsigned(8)}")
        }
        if (reply.word(21) != nonce) {
            if (active) { throw IOException("PRODUCT_OWNER_CHANGED") }
            if (now - began >= ACTIVATION_DEADLINE) { throw IOException("PRODUCT_OWNER_ACTIVATION_UNCONFIRMED") }
            return
        }
        if (reply.word(9) != 1) {
            if (now - began >= ACTIVATION_DEADLINE) { throw IOException("PRODUCT_ACTIVATION_UNCONFIRMED") }
            return
        }
        if (reply.word(29) != NAVIGATION_ACTIVE) {
            if (reply.word(29) == NAVIGATION_PENDING && now - began < ACTIVATION_DEADLINE) { return }
            throw IOException("PRODUCT_NAVIGATION_LAYOUT_UNCONFIRMED")
        }
        if (reply.word(30) != if (centerWindow) 1 else 0) {
            throw IOException("PRODUCT_LAYOUT_PROFILE_MISMATCH")
        }
        if (!active) {
            active = true
            log.log("PRODUCT_NATIVE_BACKGROUND_ACTIVE layout=native-navigation; native property effects confirmed, visual output still requires observation")
        }
        if (now - lastRenew >= RENEW_INTERVAL) {
            if (!drawingConfirmed && (delta(reply, 16) == 0L || delta(reply, 17) == 0L)) {
                throw IOException("PRODUCT_BACKGROUND_DRAW_NOT_CONFIRMED")
            }
            drawingConfirmed = true
            val renewed = exchange(channel, RENEW)
            requireQueued(renewed)
            lastRenew = now
            requestBegan = now
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
        InstrumentCompositionTrial.exchangePacket(channel, operation, request(operation, nonce, centerWindow))

    companion object {
        const val CAPABILITY = 0x52575636 // RWV6: OEM contour, continuous feather and protected information
        const val NAVIGATION_READY = 0x4e4c4300
        const val NAVIGATION_ACTIVE = 0x4e4c4301
        const val NAVIGATION_PENDING = 0x4e4c4302
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
            return request(operation, nonce, false)
        }

        @JvmStatic
        fun request(operation: Int, nonce: Int, centerWindow: Boolean): ByteArray {
            require(operation in QUERY..RENEW && nonce > 0) { "PRODUCT_LEASE_REQUEST" }
            if (centerWindow && operation == START) {
                return ByteBuffer.allocate(40).putInt(0x44435631).putInt(10).putInt(16)
                    .putInt(0).putInt(0).putInt(0).putInt(1).putInt(START).putInt(nonce).putInt(5001).array()
            }
            if (operation != RENEW) {
                return InstrumentBackgroundPoc.request(operation, if (operation == QUERY) 0 else nonce)
            }
            return ByteBuffer.allocate(40).putInt(0x44435631).putInt(10).putInt(16)
                .putInt(0).putInt(0).putInt(0).putInt(1).putInt(RENEW).putInt(nonce).putInt(5000).array()
        }
    }
}
