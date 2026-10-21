package com.byd.dashcast.phase1

import java.io.IOException

/** Read-only recovery after proven physical detach and completed local teardown.
 * Never sends HELLO, media START, background START/RENEW, STOP or a CAN command.
 * @author Codex
 * @date 2026/10/21
 */
internal object DetachedPeerRecovery {
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
