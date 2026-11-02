package com.byd.dashcast.phase1

/** Distinguishes unconfirmed local ownership from missing remote acknowledgements.
 * @author Codex
 * @date 2026/10/24
 */
internal object CleanupRecoveryPolicy {
    enum class Outcome { COMPLETE, PEER_RECOVERY_REQUIRED, LOCAL_UNCONFIRMED }

    @JvmStatic
    fun classify(captureClosed: Boolean, usbClosed: Boolean, partialOpenUnknown: Boolean,
        peerConfirmed: Boolean): Outcome = when {
        !captureClosed || !usbClosed || partialOpenUnknown -> Outcome.LOCAL_UNCONFIRMED
        !peerConfirmed -> Outcome.PEER_RECOVERY_REQUIRED
        else -> Outcome.COMPLETE
    }

    /** Bounded exception tree, not message-text authorization or a replacement for ownership proof. */
    @JvmStatic
    fun describe(error: Throwable?): String {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        val queue = java.util.ArrayDeque<Throwable>()
        if (error != null) { queue.add(error) }
        val lines = mutableListOf<String>()
        while (queue.isNotEmpty() && lines.size < 8) {
            val next = queue.removeFirst()
            if (!seen.add(next)) { continue }
            lines += "${next.javaClass.simpleName}: ${next.message.orEmpty().take(200)}"
            next.cause?.let { queue.add(it) }
            next.suppressed.take(8).forEach { queue.add(it) }
        }
        return lines.joinToString(" | ").take(1800)
    }
}
