package com.byd.dashcast.phase1;

/** Bounded process-owned history; Activity recreation does not lose long-session evidence.
 * @author Codex
 * @date 2026/10/13
 */
final class ProjectionJournal {
    private static final int MAX_CHARS = 128 * 1024;
    private final StringBuilder log = new StringBuilder(4096);
    private ProjectionSessionController.State state = ProjectionSessionController.State.IDLE;
    private String detail = "No continuous projection started in this process";
    private ProjectionSessionController.CleanupScope scope;

    synchronized void append(String message) {
        log.append(System.currentTimeMillis()).append(' ').append(message).append('\n');
        if (log.length() > MAX_CHARS) {
            int cut = log.indexOf("\n", log.length() - MAX_CHARS);
            log.delete(0, cut < 0 ? log.length() - MAX_CHARS : cut + 1);
        }
    }

    synchronized void state(ProjectionSessionController.State next, String message) {
        // A quarantine without a known scope is treated as the widest one.
        state(next, message, ProjectionSessionController.CleanupScope.LOCAL);
    }

    synchronized void state(ProjectionSessionController.State next, String message,
            ProjectionSessionController.CleanupScope cleanup) {
        state = next;
        detail = message;
        scope = next == ProjectionSessionController.State.QUARANTINED ? cleanup : null;
        append("PROJECTION_STATE=" + next + " " + message
                + (scope == null ? "" : " cleanup_scope=" + scope));
    }

    synchronized boolean quarantined() {
        return state == ProjectionSessionController.State.QUARANTINED;
    }

    /** Null unless QUARANTINED. */
    synchronized ProjectionSessionController.CleanupScope cleanupScope() {
        return scope;
    }

    /**
     * The leftover capture helper of a capture-scoped quarantine was ended and confirmed gone. Everything else
     * was already confirmed, so the session is STOPPED, or RECOVERY_REQUIRED when the instrument receiver's
     * reply is still missing. Returns false, changing nothing, for any other state or scope.
     */
    synchronized boolean leftoverCaptureCleaned(String evidence) {
        if (state != ProjectionSessionController.State.QUARANTINED
                || scope == ProjectionSessionController.CleanupScope.LOCAL || scope == null) {
            return false;
        }
        boolean peer = scope == ProjectionSessionController.CleanupScope.CAPTURE_AND_PEER;
        // The quarantine detail stays in the log above; it is not carried over, so the new status reads as
        // the state it now is.
        state(peer ? ProjectionSessionController.State.RECOVERY_REQUIRED : ProjectionSessionController.State.STOPPED,
                (peer ? "PEER_RECOVERY_REQUIRED: " : "") + "LEFTOVER_CAPTURE_CLEANED " + evidence);
        return true;
    }

    /** A fresh process after an automatic restart: the instrument side of the old session is unconfirmed. */
    synchronized boolean restartedAfterLocalCleanup(String reason) {
        if (state != ProjectionSessionController.State.IDLE) {
            return false;
        }
        state(ProjectionSessionController.State.RECOVERY_REQUIRED, "PEER_RECOVERY_REQUIRED: " + reason);
        return true;
    }

    synchronized ProjectionSessionController.State currentState() {
        return state;
    }

    synchronized String status() {
        return state + ": " + detail;
    }

    synchronized String snapshot() {
        return "CONTINUOUS_SESSION " + status() + "\n" + log;
    }

    /** Coordinator history is not the Service state; never prepend a fictitious IDLE session. */
    synchronized String coordinatorSnapshot() {
        return "PRODUCT_COORDINATOR_EVENTS\n" + log;
    }
}
