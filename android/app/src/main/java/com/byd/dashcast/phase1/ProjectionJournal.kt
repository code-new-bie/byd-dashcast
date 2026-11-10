package com.byd.dashcast.phase1

import com.byd.dashcast.phase1.ProjectionSessionController.CleanupScope
import com.byd.dashcast.phase1.ProjectionSessionController.State

/**
 * Bounded process-owned history; Activity recreation does not lose long-session evidence.
 * @author Codex
 * @date 2026/10/13
 */
internal class ProjectionJournal {
    private val log = StringBuilder(4096)
    private var state = State.IDLE
    private var detail = "No continuous projection started in this process"
    private var scope: CleanupScope? = null

    @Synchronized
    fun append(message: String) {
        log.append(System.currentTimeMillis()).append(' ').append(message).append('\n')
        if (log.length > MAX_CHARS) {
            val cut = log.indexOf("\n", log.length - MAX_CHARS)
            log.delete(0, if (cut < 0) log.length - MAX_CHARS else cut + 1)
        }
    }

    @Synchronized
    fun state(next: State, message: String) {
        // A quarantine without a known scope is treated as the widest one.
        state(next, message, CleanupScope.LOCAL)
    }

    @Synchronized
    fun state(next: State, message: String, cleanup: CleanupScope?) {
        state = next
        detail = message
        scope = if (next == State.QUARANTINED) cleanup else null
        append("PROJECTION_STATE=" + next + " " + message + (scope?.let { " cleanup_scope=$it" } ?: ""))
    }

    @Synchronized
    fun quarantined(): Boolean = state == State.QUARANTINED

    /** Null unless QUARANTINED. */
    @Synchronized
    fun cleanupScope(): CleanupScope? = scope

    /**
     * The leftover capture helper of a capture-scoped quarantine was ended and confirmed gone. Everything else
     * was already confirmed, so the session is STOPPED, or RECOVERY_REQUIRED when the instrument receiver's
     * reply is still missing. Returns false, changing nothing, for any other state or scope.
     */
    @Synchronized
    fun leftoverCaptureCleaned(evidence: String): Boolean {
        if (state != State.QUARANTINED || scope == CleanupScope.LOCAL || scope == null) {
            return false
        }
        val peer = scope == CleanupScope.CAPTURE_AND_PEER
        // The quarantine detail stays in the log above; it is not carried over, so the new status reads as
        // the state it now is.
        state(if (peer) State.RECOVERY_REQUIRED else State.STOPPED,
            (if (peer) "PEER_RECOVERY_REQUIRED: " else "") + "LEFTOVER_CAPTURE_CLEANED " + evidence)
        return true
    }

    /** A fresh process after an automatic restart: the instrument side of the old session is unconfirmed. */
    @Synchronized
    fun restartedAfterLocalCleanup(reason: String): Boolean {
        if (state != State.IDLE) {
            return false
        }
        state(State.RECOVERY_REQUIRED, "PEER_RECOVERY_REQUIRED: $reason")
        return true
    }

    @Synchronized
    fun currentState(): State = state

    @Synchronized
    fun status(): String = "$state: $detail"

    @Synchronized
    fun snapshot(): String = "CONTINUOUS_SESSION " + status() + "\n" + log

    /** Coordinator history is not the Service state; never prepend a fictitious IDLE session. */
    @Synchronized
    fun coordinatorSnapshot(): String = "PRODUCT_COORDINATOR_EVENTS\n$log"

    private companion object {
        const val MAX_CHARS = 128 * 1024
    }
}
