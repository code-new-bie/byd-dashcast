package com.byd.dashcast.phase1

import java.io.IOException

/** Read-only recovery after proven physical detach and completed local teardown.
 * Never sends HELLO, media START, background START/RENEW, STOP or a CAN command.
 * @author Codex
 * @date 2026/10/21
 */
internal object DetachedPeerRecovery {
    private const val RECOVERY_WAIT_NANOS = 6_000_000_000L
    internal fun localCleanupUnconfirmed(error: Throwable?): Boolean = error != null &&
        (error.message.orEmpty().contains("CLEANUP_UNCONFIRMED") || localCleanupUnconfirmed(error.cause) ||
            error.suppressed.any { localCleanupUnconfirmed(it) })

    @JvmStatic
    fun verify(channel: NoAuthVideoTransport, pause: Runnable) {
        NoAuthVideoProtocol.validateOffer(channel.readExact(NoAuthVideoProtocol.HEADER_BYTES),
            channel.readExact(NoAuthVideoProtocol.OFFER_BYTES))
        val first = query(channel)
        requireIdle(first)
        pause.run()
        val second = query(channel)
        validate(first, second)
    }

    /** Wait only for the existing five-second HMI lease; queries cannot renew it. */
    @JvmStatic
    fun verifyAwaitIdle(channel: NoAuthVideoTransport, pause: Runnable,
        clock: java.util.function.LongSupplier) {
        NoAuthVideoProtocol.validateOffer(channel.readExact(NoAuthVideoProtocol.HEADER_BYTES),
            channel.readExact(NoAuthVideoProtocol.OFFER_BYTES))
        val began = clock.asLong
        var previous: InstrumentBackgroundPoc.Reply? = null
        repeat(64) {
            if (clock.asLong - began >= RECOVERY_WAIT_NANOS) {
                throw IOException("PEER_RECOVERY_WAIT_EXPIRED; no START or role command sent")
            }
            val request = InstrumentBackgroundPoc.request(0, 0)
            java.nio.ByteBuffer.wrap(request).putInt(36, 1)
            val current = InstrumentCompositionTrial.exchangePacket(channel, 0, request)
            if (current.error == 8) {
                throw IOException("PEER_RECOVERY_NATIVE_CLEANUP_FAULT; receiver sticky guard retained")
            }
            if (current.word(31) != ProductCompositionSession.CAPABILITY ||
                current.error != 0 && current.error != 9) {
                throw IOException("PEER_RECOVERY_INCOMPATIBLE_OR_REJECTED error=${current.error}")
            }
            val idle = current.error == 0 && (current.word(9) == 0 || current.word(9) == 2) &&
                current.word(3) == current.word(8) && current.word(10) > 0 && current.word(18) != 0 &&
                current.word(30) == 0 && current.word(29) == ProductCompositionSession.NAVIGATION_READY
            if (idle) {
                val first = previous
                if (first != null && first.word(10) == current.word(10) && first.word(18) == current.word(18)) {
                    val frames = (current.unsigned(24) - first.unsigned(24)) and 0xffffffffL
                    if (frames in 1L..240L) { validate(first, current); return }
                }
                previous = current
            } else { previous = null }
            pause.run()
        }
        throw IOException("PEER_RECOVERY_WAIT_EXPIRED; no fresh renderer proof")
    }

    internal fun validate(first: InstrumentBackgroundPoc.Reply, second: InstrumentBackgroundPoc.Reply) {
        requireIdle(first)
        requireIdle(second)
        val frames = (second.unsigned(24) - first.unsigned(24)) and 0xffffffffL
        if (first.word(10) != second.word(10) || first.word(18) != second.word(18) || frames !in 1L..240L) {
            throw IOException("DETACHED_RECOVERY_FRESH_RENDER_NOT_CONFIRMED")
        }
    }

    private fun query(channel: NoAuthVideoTransport): InstrumentBackgroundPoc.Reply =
        InstrumentCompositionTrial.exchangePacket(channel, 0, InstrumentBackgroundPoc.request(0, 0))

    private fun requireIdle(reply: InstrumentBackgroundPoc.Reply) {
        if (reply.error != 0 || (reply.word(9) != 0 && reply.word(9) != 2) || reply.word(3) != reply.word(8) ||
            reply.word(10) <= 0 || reply.word(18) == 0 ||
            reply.word(31) != ProductCompositionSession.CAPABILITY || reply.word(30) != 0 ||
            reply.word(29) != ProductCompositionSession.NAVIGATION_READY) {
            throw IOException("DETACHED_RECOVERY_PEER_NOT_IDLE error=${reply.error}; no new session or role command sent")
        }
    }
}
