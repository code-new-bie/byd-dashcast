package com.byd.dashcast.phase1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Deterministic worker/lifecycle tests, not an Android Service or vehicle simulation. */
class ProjectionSessionControllerTest {
    private class Worker : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(task: Runnable) { tasks.add(task) }
        fun run() { tasks.removeFirst().run() }
    }

    @Test
    fun uiRecreationDoesNotStopSessionAndDuplicateStartCannotOverlap() {
        val worker = Worker()
        val events = ArrayList<ProjectionSessionController.State>()
        val owner = ProjectionSessionController(worker, ProjectionSessionController.Observer { state, _ -> events.add(state) })
        val task = ProjectionSessionController.Task { stop, abort, streaming, cleaning ->
            assertFalse(stop.get())
            assertFalse(abort.get())
            streaming.run()
            assertTrue(owner.active())
            assertFalse(owner.start(ProjectionSessionController.Task { _, _, _, _ -> fail("overlap") }))
            cleaning.run()
            assertEquals(ProjectionSessionController.State.STOPPING, owner.state())
        }
        assertTrue(owner.start(task))
        assertFalse(owner.start(task))
        // There is intentionally no Activity onStop/onDestroy hook on the owner.
        assertTrue(owner.active())
        worker.run()
        assertFalse(owner.active())
        assertEquals(listOf(ProjectionSessionController.State.STARTING, ProjectionSessionController.State.STREAMING,
            ProjectionSessionController.State.STOPPING, ProjectionSessionController.State.STOPPED), events)
        assertTrue(owner.start(task))
        worker.run()
    }

    @Test
    fun stopBeforeFirstFrameDoesNotBecomeStreamingOrAbort() {
        val worker = Worker()
        val owner = ProjectionSessionController(worker, ProjectionSessionController.Observer { _, _ -> })
        assertTrue(owner.start(ProjectionSessionController.Task { stop, abort, streaming, cleaning ->
            assertTrue(stop.get())
            assertFalse(abort.get())
            streaming.run()
            assertEquals(ProjectionSessionController.State.STOPPING, owner.state())
            cleaning.run()
        }))
        owner.stop()
        worker.run()
        assertEquals(ProjectionSessionController.State.STOPPED, owner.state())
    }

    @Test
    fun disconnectRequiresCleanupAndUnknownCleanupPermanentlyBlocksThisOwner() {
        val worker = Worker()
        val owner = ProjectionSessionController(worker, ProjectionSessionController.Observer { _, _ -> })
        owner.start(ProjectionSessionController.Task { stop, abort, _, cleaning ->
            assertTrue(stop.get() && abort.get())
            cleaning.run()
            throw ProjectionSessionController.CleanupUnconfirmedException("STOP reply lost", null)
        })
        owner.abort("USB detach")
        worker.run()
        assertEquals(ProjectionSessionController.State.QUARANTINED, owner.state())
        assertFalse(owner.start(ProjectionSessionController.Task { _, _, _, _ -> fail("unsafe retry") }))
    }

    @Test
    fun confirmedCleanupAllowsRetryButUnexpectedRuntimeFailureDoesNot() {
        val owner = ProjectionSessionController(Executor { it.run() }, ProjectionSessionController.Observer { _, _ -> })
        assertTrue(owner.start(ProjectionSessionController.Task { _, _, _, c ->
            c.run(); throw IOException("encoder rejected, cleanup complete")
        }))
        assertEquals(ProjectionSessionController.State.FAILED, owner.state())
        assertTrue(owner.start(ProjectionSessionController.Task { _, _, _, _ -> throw IllegalStateException("unknown state") }))
        assertEquals(ProjectionSessionController.State.QUARANTINED, owner.state())
    }

    @Test
    fun rejectedWorkerReleasesActiveStateBeforeAnyResourceWasOpened() {
        val owner = ProjectionSessionController(Executor {
            throw RejectedExecutionException("test bounded queue")
        }, ProjectionSessionController.Observer { _, _ -> })
        assertFalse(owner.start(ProjectionSessionController.Task { _, _, _, _ -> fail("rejected worker must not run") }))
        assertFalse(owner.active())
        assertEquals(ProjectionSessionController.State.FAILED, owner.state())
    }

    @Test
    fun fatalWorkerErrorCannotBecomeFalseStoppedSuccess() {
        val worker = Worker()
        val owner = ProjectionSessionController(worker, ProjectionSessionController.Observer { _, _ -> })
        owner.start(ProjectionSessionController.Task { _, _, _, _ -> throw AssertionError("synthetic fatal failure") })
        assertThrows(AssertionError::class.java) { worker.run() }
        assertEquals(ProjectionSessionController.State.QUARANTINED, owner.state())
        assertFalse(owner.active())
    }

    @Test
    fun provenDetachWithLocalTeardownIsRecoverableButCannotStartWithoutPeerProof() {
        val worker = Worker()
        val owner = ProjectionSessionController(worker, ProjectionSessionController.Observer { _, _ -> })
        assertTrue(owner.start(ProjectionSessionController.Task { _, _, _, c ->
            c.run()
            throw ProjectionSessionController.DetachedSessionException(null)
        }))
        worker.run()
        assertFalse(owner.active())
        assertEquals(ProjectionSessionController.State.DISCONNECTED, owner.state())
        assertFalse(owner.start(ProjectionSessionController.Task { _, _, _, _ -> fail("read-only recovery must happen first") }))
    }
}
