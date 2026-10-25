package com.byd.dashcast.phase1

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SingleAppTaskPolicyTest {
    private fun task(id: Int = 10, display: Int = 2, user: Int = 0, root: String = "app/Main",
        top: String = root): AppTaskPolicy.Task = AppTaskPolicy.Task(id, 5, display, user, root, top, true)
    private fun policy(before: Set<Int> = setOf(10, 20)): SingleAppTaskPolicy =
        SingleAppTaskPolicy("app", 0, 2, before)

    @Test fun originalTaskMayBeAdoptedWithoutCloning() {
        val source = task(display = 0)
        val owner = policy().apply { adopt(source) }
        assertEquals(10, owner.taskId)
        assertFalse(owner.framesAllowed(source))
        assertTrue(owner.framesAllowed(task()))
    }
    @Test fun samePackageForwardingCanChangeRootAndTop() {
        val owner = policy().apply { adopt(task(root = "app/Launcher")) }
        assertTrue(owner.framesAllowed(owner.find(listOf(task(root = "app/Main", top = "app/Details")))!!))
    }
    @Test fun primaryOtherAppDoesNotBecomeSource() {
        val owner = policy().apply { adopt(task()) }
        assertEquals(10, owner.find(listOf(task(), task(30, 0, root = "other/Screen")))!!.id)
    }
    @Test fun openingAnotherPrimaryTaskOfSameAppDoesNotBecomeSource() {
        val owner = policy().apply { adopt(task()) }
        assertEquals(10, owner.find(listOf(task(), task(30, 0)))!!.id)
    }
    @Test fun coldForwarderCanFinishAndYieldOneNewSourceTask() {
        val owner = policy().apply { adopt(task(30, root = "app/Forwarder")) }
        assertEquals(31, owner.find(listOf(task(31)))!!.id)
        assertEquals(31, owner.taskId)
    }
    @Test fun existingOtherTaskIsNotAdoptedAsSuccessor() {
        val owner = policy().apply { adopt(task()) }
        assertNull(owner.find(listOf(task(20))))
        assertThrows(IOException::class.java) { owner.adopt(task(20)) }
    }
    @Test fun multipleNewSuccessorsFailClosed() {
        val owner = policy().apply { adopt(task()) }
        assertThrows(IOException::class.java) { owner.find(listOf(task(30), task(31))) }
    }
    @Test fun lostTaskDoesNotCaptureDisplayZero() {
        val owner = policy().apply { adopt(task()) }
        assertNull(owner.find(listOf(task(30, 0))))
        assertFalse(owner.framesAllowed(task(display = 0)))
    }
    @Test fun nullTopIsTemporarilyNotCaptureableButDoesNotChangeOwnership() {
        val owner = policy().apply { adopt(task()) }
        assertEquals(10, owner.find(listOf(task(top = "")))!!.id)
        assertFalse(owner.framesAllowed(task(top = "")))
    }
    @Test fun foreignTopNeverPassesFrameGuard() {
        val owner = policy().apply { adopt(task()) }
        assertFalse(owner.framesAllowed(task(top = "permission/Dialog")))
    }
    @Test fun otherDisplayNeverPassesFrameGuard() {
        val owner = policy().apply { adopt(task()) }
        assertFalse(owner.framesAllowed(task(display = 3)))
    }
    @Test fun userChangeIsNotHiddenInGenericMessage() {
        val owner = policy().apply { adopt(task()) }
        val error = assertThrows(IOException::class.java) { owner.find(listOf(task(user = 1))) }
        assertTrue(error.message!!.contains("user=1"))
    }
    @Test fun foreignRootIsNotHiddenInGenericMessage() {
        val owner = policy().apply { adopt(task()) }
        val error = assertThrows(IOException::class.java) { owner.find(listOf(task(root = "other/Main"))) }
        assertTrue(error.message!!.contains("root=other/Main"))
    }
    @Test fun cleanupCandidatesExcludeEveryUnadoptedPreexistingTask() {
        val owner = policy().apply { adopt(task()) }
        assertEquals(listOf(10, 30), owner.returnCandidates(listOf(task(), task(20), task(30))).map { it.id })
    }
    @Test fun cleanupCanReturnOwnNewExternalPickerWithoutDeletingIt() {
        val owner = policy().apply { adopt(task()) }
        assertEquals(30, owner.returnCandidates(listOf(task(30, root = "picker/Main"))).single().id)
    }
    @Test fun cleanupDoesNotMovePrimaryOrOtherUserTasks() {
        val owner = policy().apply { adopt(task()) }
        assertTrue(owner.returnCandidates(listOf(task(display = 0), task(30, user = 1))).isEmpty())
    }
    @Test fun duplicateIdentityFailsClosed() {
        val owner = policy().apply { adopt(task()) }
        assertThrows(IOException::class.java) { owner.find(listOf(task(), task())) }
    }
    @Test fun frameGuardAlsoRequiresBoundId() {
        val owner = policy().apply { adopt(task()) }
        assertFalse(owner.framesAllowed(task(31)))
    }
    @Test fun beforeSnapshotCannotBeChangedByCaller() {
        val before = mutableSetOf(10, 20)
        val owner = policy(before).apply { adopt(task()) }
        before.clear()
        assertNull(owner.find(listOf(task(20))))
    }
    @Test fun repeatCyclesPreserveOriginalIdsAndDoNotAuthorizeDeletion() {
        repeat(1000) {
            val owner = policy().apply { adopt(task(display = 0)) }
            assertTrue(owner.framesAllowed(task()))
            assertEquals(10, owner.returnCandidates(listOf(task())).single().id)
            assertFalse(owner.framesAllowed(task(display = 0)))
        }
    }
}
