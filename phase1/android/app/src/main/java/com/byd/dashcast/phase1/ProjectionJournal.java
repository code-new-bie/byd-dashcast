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

    synchronized void append(String message) {
        log.append(System.currentTimeMillis()).append(' ').append(message).append('\n');
        if (log.length() > MAX_CHARS) {
            int cut = log.indexOf("\n", log.length() - MAX_CHARS);
            log.delete(0, cut < 0 ? log.length() - MAX_CHARS : cut + 1);
        }
    }

    synchronized void state(ProjectionSessionController.State next, String message) {
        state = next;
        detail = message;
        append("PROJECTION_STATE=" + next + " " + message);
    }

    synchronized boolean quarantined() {
        return state == ProjectionSessionController.State.QUARANTINED;
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
}
