package com.byd.dashcast.phase1

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class AppTaskHandoffTest {
    private val root = "com.autonavi.amapauto/com.autonavi.amapauto.MainMapActivity"
    private fun task(display: Int = 0, visible: Boolean = true) =
        AppTaskPolicy.Task(42, 7, display, 0, root, root, visible)

    private inner class Model(initial: AppTaskPolicy.Task = task()) : AppTaskHandoff.TaskPort,
        AppTaskHandoff.SourcePort {
        var current: AppTaskPolicy.Task? = initial
        var failedMove = false
        var wrongDisplay = false
        var selectFailure = false
        val actions = mutableListOf<String>()
        val handoff = AppTaskHandoff(initial, 9, this, this)
        override fun read(id: Int, user: Int, root: String) = current
        override fun transfer(task: AppTaskPolicy.Task, display: Int, show: Boolean): AppTaskPolicy.Task {
            actions += "move:${task.id}:$display:$show"
            if (failedMove) { throw IOException("ATM rejected") }
            current = task.copy(display = if (wrongDisplay) 8 else display, visible = display == 0 && show)
            return requireNotNull(current)
        }
        override fun invalidate() { actions += "fence" }
        override fun select(display: Int) {
            if (selectFailure) { throw IOException("SF rejected") }
            actions += "capture:$display"
        }
    }

    @Test fun alreadyNavigatingPrimaryTaskIsNeverLaunchedMovedOrRecreatedAtStart() {
        val m = Model(); assertTrue(m.handoff.route(false))
        assertEquals(listOf("fence", "capture:0"), m.actions)
        repeat(60) { assertFalse(m.handoff.route(false)) }
        assertEquals(42, m.current!!.id)
        m.handoff.close()
        assertFalse(m.actions.any { it.startsWith("move:") })
    }

    @Test fun backgroundUsesOwnedDisplayAndNativeIconReturnsTheSameRunningTask() {
        val m = Model(); m.handoff.route(false); m.actions.clear()
        m.current = m.current!!.copy(visible = false)
        assertTrue(m.handoff.route(false))
        assertEquals(listOf("fence", "move:42:9:false", "capture:9"), m.actions)
        m.actions.clear()
        assertTrue(m.handoff.route(true))
        assertEquals(listOf("fence", "move:42:0:true", "capture:0"), m.actions)
        assertEquals(root, m.current!!.root)
        assertEquals(42, m.current!!.id)
    }

    @Test fun frameworkAlreadyMovedTaskToPrimaryNeedsOnlyCaptureHandoffNotAnotherIntent() {
        val m = Model(task(9, false)); m.handoff.route(false); m.actions.clear()
        m.current = m.current!!.copy(display = 0, visible = true)
        m.handoff.route(false)
        assertEquals(listOf("fence", "capture:0"), m.actions)
    }

    @Test fun nativeIconOnPrimaryBackgroundBringsThatTaskForwardBeforeAnyCapture() {
        val m = Model(task(0, false)); m.handoff.route(true)
        assertEquals(listOf("fence", "move:42:0:true", "capture:0"), m.actions)
    }

    @Test fun stopReturnsOwnedTaskBeforeCallerMayReleaseItsDisplayAndIsIdempotent() {
        val m = Model(task(9, false)); m.handoff.route(false); m.actions.clear()
        m.handoff.close(); m.actions += "release-display"; m.handoff.close()
        assertEquals(listOf("fence", "move:42:0:false", "release-display"), m.actions)
        assertEquals(42, m.current!!.id)
        assertThrows(IllegalStateException::class.java) { m.handoff.route(false) }
    }

    @Test fun stopDoesNotRelaunchOrStealFocusFromAnotherPrimaryApplication() {
        val m = Model(); m.handoff.route(false)
        m.current = m.current!!.copy(visible = false)
        m.actions.clear(); m.handoff.close()
        assertEquals(listOf("fence"), m.actions)
        assertFalse(m.current!!.visible)
    }

    @Test fun movedRemovedForeignUserOrChangedRootTaskNeverCapturesUnrelatedUi() {
        for (change in listOf<(AppTaskPolicy.Task) -> AppTaskPolicy.Task?>(
            { null }, { it.copy(id = 43) }, { it.copy(user = 10) }, { it.copy(root = "other.app/.Main") },
            { it.copy(display = 8) }, { it.copy(top = "other.app/.Permission") })) {
            val m = Model(); m.handoff.route(false); m.actions.clear()
            m.current = change(m.current!!)
            assertThrows(IOException::class.java) { m.handoff.route(false) }
            assertTrue(m.actions.isEmpty())
        }
    }

    @Test fun anAppThatFinishedItselfIsNotColdStartedDuringCleanup() {
        val m = Model(task(9, false)); m.handoff.route(false)
        m.current = null; m.actions.clear(); m.handoff.close()
        assertEquals(listOf("fence"), m.actions)
    }

    @Test fun rejectedOrUnconfirmedMoveCannotChangeSourceOrHideCleanupFailure() {
        for (unconfirmed in listOf(false, true)) {
            val m = Model(task(0, false))
            m.failedMove = !unconfirmed; m.wrongDisplay = unconfirmed
            assertThrows(IOException::class.java) { m.handoff.route(false) }
            assertEquals(-1, m.handoff.sourceDisplay)
            assertFalse(m.actions.any { it.startsWith("capture:") })
        }
        val m = Model(task(9, false)); m.handoff.route(false); m.failedMove = true
        assertThrows(IOException::class.java) { m.handoff.close() }
    }

    @Test fun sfFailureStillAllowsTheSameTaskToReturnDuringCleanup() {
        val m = Model(task(0, false)); m.selectFailure = true
        assertThrows(IOException::class.java) { m.handoff.route(false) }
        m.handoff.close()
        assertEquals(0, m.current!!.display)
        assertEquals(42, m.current!!.id)
    }

    @Test fun primaryVisibilityMustBeConfirmedAndMixedStacksAreNeverMoved() {
        val m = Model(task(0, false))
        val backend = object : AppTaskHandoff.TaskPort {
            override fun read(id: Int, user: Int, root: String) = task(0, false)
            override fun transfer(task: AppTaskPolicy.Task, display: Int, show: Boolean) = task
        }
        val s = AppTaskHandoff(task(0, false), 9, backend, m)
        assertThrows(IOException::class.java) { s.route(true) }
        assertFalse(m.actions.any { it.startsWith("capture:") })
        AppTaskPolicy.requireIsolated(task(), intArrayOf(42))
        for (ids in listOf(intArrayOf(), intArrayOf(43), intArrayOf(42, 43))) {
            assertThrows(IOException::class.java) { AppTaskPolicy.requireIsolated(task(), ids) }
        }
    }

    @Test fun oneThousandMainBackgroundIconHandoffsKeepOneTaskWithoutAnyRelaunchPort() {
        val m = Model(); m.handoff.route(false)
        repeat(1000) {
            m.current = m.current!!.copy(visible = false)
            m.handoff.route(false); assertEquals(9, m.handoff.sourceDisplay)
            m.handoff.route(true); assertEquals(0, m.handoff.sourceDisplay)
            assertEquals(42, m.current!!.id)
        }
        assertEquals(2000, m.actions.count { it.startsWith("move:") })
        m.handoff.close()
    }
}
