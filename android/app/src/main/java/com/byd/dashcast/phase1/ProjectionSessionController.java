package com.byd.dashcast.phase1;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single owned session, independent of Activity lifetime. Cleanup uncertainty blocks retries.
 * @author Codex
 * @date 2026/10/13
 */
final class ProjectionSessionController {
    enum State {
        IDLE, STARTING, STREAMING, STOPPING, STOPPED, FAILED, DISCONNECTED, RECOVERY_REQUIRED, QUARANTINED
    }

    interface Task {
        /** Must return only after owned capture, media and USB cleanup; throws on uncertainty. */
        void run(AtomicBoolean stop, AtomicBoolean abort, Runnable streaming, Runnable cleaning) throws IOException;
    }

    interface Observer {
        void state(State state, String detail);
    }

    static final class CleanupUnconfirmedException extends IOException {
        private static final long serialVersionUID = 1L;

        CleanupUnconfirmedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Physical link loss with owned Android capture/USB resources already closed. */
    static final class DetachedSessionException extends IOException {
        private static final long serialVersionUID = 1L;

        DetachedSessionException(Throwable cause) {
            super("DETACHED_RECOVERY_REQUIRED: local resources closed; peer recovery not yet confirmed", cause);
        }
    }

    /** Only for a completed local teardown; a missing peer reply is not a local resource leak. */
    static final class PeerCleanupPendingException extends IOException {
        private static final long serialVersionUID = 1L;

        PeerCleanupPendingException(String detail, Throwable cause) {
            super("PEER_RECOVERY_REQUIRED: " + detail, cause);
        }
    }

    private final Executor executor;
    private final Observer observer;
    private State state = State.IDLE;
    private AtomicBoolean stop;
    private AtomicBoolean abort;
    private boolean active;

    ProjectionSessionController(Executor executor, Observer observer) {
        this.executor = Objects.requireNonNull(executor);
        this.observer = Objects.requireNonNull(observer);
    }

    synchronized boolean start(Task task) {
        Objects.requireNonNull(task);
        if (active || state == State.QUARANTINED || state == State.DISCONNECTED || state == State.RECOVERY_REQUIRED) {
            return false;
        }
        active = true;
        stop = new AtomicBoolean();
        abort = new AtomicBoolean();
        change(State.STARTING, "starting one bounded session");
        AtomicBoolean ownedStop = stop;
        AtomicBoolean ownedAbort = abort;
        try {
            executor.execute(() -> execute(task, ownedStop, ownedAbort));
        } catch (RuntimeException error) {
            active = false;
            change(State.FAILED, "Worker rejected before session start: " + error);
            return false;
        }
        return true;
    }

    synchronized void stop() {
        if (active) {
            stop.set(true);
            change(State.STOPPING, "graceful stop requested; waiting for owned cleanup");
        }
    }

    synchronized void abort(String reason) {
        if (active) {
            stop.set(true);
            abort.set(true);
            change(State.STOPPING, reason + "; cleanup required, no auto Host/reconnect");
        }
    }

    synchronized boolean active() {
        return active;
    }

    synchronized State state() {
        return state;
    }

    private void execute(Task task, AtomicBoolean ownedStop, AtomicBoolean ownedAbort) {
        State outcome = State.STOPPED;
        Error fatal = null;
        String detail = "owned capture/receiver/USB cleanup confirmed; check instrument window manually";
        try {
            task.run(ownedStop, ownedAbort, this::streaming, this::cleaning);
        } catch (DetachedSessionException error) {
            outcome = State.DISCONNECTED;
            detail = error.getMessage();
        } catch (PeerCleanupPendingException error) {
            outcome = State.RECOVERY_REQUIRED;
            detail = error.getMessage();
        } catch (CleanupUnconfirmedException error) {
            outcome = State.QUARANTINED;
            detail = error.getMessage() + "; stop testing, normal restart required; no guard deletion";
        } catch (IOException error) {
            outcome = State.FAILED;
            detail = "Session failed with owned cleanup confirmed: " + error;
        } catch (RuntimeException error) {
            outcome = State.QUARANTINED;
            detail = "Unexpected worker failure; owned cleanup not confirmed: " + error;
        } catch (Error error) {
            outcome = State.QUARANTINED;
            detail = "Fatal worker failure; cleanup not confirmed: " + error.getClass().getSimpleName();
            fatal = error;
        } finally {
            synchronized (this) {
                active = false;
                change(outcome, detail);
            }
        }
        if (fatal != null) {
            throw fatal;
        }
    }

    private synchronized void streaming() {
        if (active && state == State.STARTING) {
            change(State.STREAMING, "first FRAME ACK; actual instrument picture requires observation");
        }
    }

    private synchronized void cleaning() {
        if (active && state != State.STOPPING) {
            change(State.STOPPING, "owned resource cleanup in progress; role changes remain blocked");
        }
    }

    private void change(State next, String detail) {
        state = next;
        observer.state(next, detail);
    }
}
