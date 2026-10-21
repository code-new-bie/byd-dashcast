package com.byd.dashcast.phase1;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.Assert.*;

/** Deterministic worker/lifecycle tests, not an Android Service or vehicle simulation. */
public final class ProjectionSessionControllerTest {
    private static final class Worker implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        void run() { tasks.removeFirst().run(); }
    }

    @Test
    public void uiRecreationDoesNotStopSessionAndDuplicateStartCannotOverlap() {
        Worker worker = new Worker();
        List<ProjectionSessionController.State> events = new ArrayList<>();
        ProjectionSessionController owner = new ProjectionSessionController(worker, (state, detail) -> events.add(state));
        ProjectionSessionController.Task task = (stop, abort, streaming, cleaning) -> {
            assertFalse(stop.get());
            assertFalse(abort.get());
            streaming.run();
            assertTrue(owner.active());
            assertFalse(owner.start((s, a, f, c) -> fail("overlap")));
            cleaning.run();
            assertEquals(ProjectionSessionController.State.STOPPING, owner.state());
        };
        assertTrue(owner.start(task));
        assertFalse(owner.start(task));
        // There is intentionally no Activity onStop/onDestroy hook on the owner.
        assertTrue(owner.active());
        worker.run();
        assertFalse(owner.active());
        assertEquals(List.of(ProjectionSessionController.State.STARTING, ProjectionSessionController.State.STREAMING,
                ProjectionSessionController.State.STOPPING, ProjectionSessionController.State.STOPPED), events);
        assertTrue(owner.start(task));
        worker.run();
    }

    @Test
    public void stopBeforeFirstFrameDoesNotBecomeStreamingOrAbort() {
        Worker worker = new Worker();
        ProjectionSessionController owner = new ProjectionSessionController(worker, (state, detail) -> {});
        assertTrue(owner.start((stop, abort, streaming, cleaning) -> {
            assertTrue(stop.get());
            assertFalse(abort.get());
            streaming.run();
            assertEquals(ProjectionSessionController.State.STOPPING, owner.state());
            cleaning.run();
        }));
        owner.stop();
        worker.run();
        assertEquals(ProjectionSessionController.State.STOPPED, owner.state());
    }

    @Test
    public void disconnectRequiresCleanupAndUnknownCleanupPermanentlyBlocksThisOwner() {
        Worker worker = new Worker();
        ProjectionSessionController owner = new ProjectionSessionController(worker, (state, detail) -> {});
        owner.start((stop, abort, streaming, cleaning) -> {
            assertTrue(stop.get() && abort.get());
            cleaning.run();
            throw new ProjectionSessionController.CleanupUnconfirmedException("STOP reply lost", null);
        });
        owner.abort("USB detach");
        worker.run();
        assertEquals(ProjectionSessionController.State.QUARANTINED, owner.state());
        assertFalse(owner.start((s, a, f, c) -> fail("unsafe retry")));
    }

    @Test
    public void confirmedCleanupAllowsRetryButUnexpectedRuntimeFailureDoesNot() {
        ProjectionSessionController owner = new ProjectionSessionController(Runnable::run, (state, detail) -> {});
        assertTrue(owner.start((s, a, f, c) -> { c.run(); throw new IOException("encoder rejected, cleanup complete"); }));
        assertEquals(ProjectionSessionController.State.FAILED, owner.state());
        assertTrue(owner.start((s, a, f, c) -> { throw new IllegalStateException("unknown state"); }));
        assertEquals(ProjectionSessionController.State.QUARANTINED, owner.state());
    }

    @Test
    public void rejectedWorkerReleasesActiveStateBeforeAnyResourceWasOpened() {
        ProjectionSessionController owner = new ProjectionSessionController(task -> {
            throw new RejectedExecutionException("test bounded queue");
        }, (state, detail) -> {});
        assertFalse(owner.start((s, a, f, c) -> fail("rejected worker must not run")));
        assertFalse(owner.active());
        assertEquals(ProjectionSessionController.State.FAILED, owner.state());
    }

    @Test
    public void fatalWorkerErrorCannotBecomeFalseStoppedSuccess() {
        Worker worker = new Worker();
        ProjectionSessionController owner = new ProjectionSessionController(worker, (state, detail) -> {});
        owner.start((s, a, f, c) -> { throw new AssertionError("synthetic fatal failure"); });
        assertThrows(AssertionError.class, worker::run);
        assertEquals(ProjectionSessionController.State.QUARANTINED, owner.state());
        assertFalse(owner.active());
    }

    @Test
    public void provenDetachWithLocalTeardownIsRecoverableButCannotStartWithoutPeerProof() {
        Worker worker = new Worker();
        ProjectionSessionController owner = new ProjectionSessionController(worker, (state, detail) -> {});
        assertTrue(owner.start((s, a, f, c) -> {
            c.run();
            throw new ProjectionSessionController.DetachedSessionException(null);
        }));
        worker.run();
        assertFalse(owner.active());
        assertEquals(ProjectionSessionController.State.DISCONNECTED, owner.state());
        assertFalse(owner.start((s, a, f, c) -> fail("read-only recovery must happen first")));
    }
}
