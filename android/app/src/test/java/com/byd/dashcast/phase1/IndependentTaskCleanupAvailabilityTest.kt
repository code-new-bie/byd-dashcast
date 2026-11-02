package com.byd.dashcast.phase1

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure policy/cleanup tests using synthetic snapshots; no Android Binder or vehicle execution.
 * @author Codex
 * @date 2026/10/25
 */
class IndependentTaskCleanupAvailabilityTest {
    private val root = "example.app/.Main"

    private fun task(id: Int = 2, display: Int = 9, user: Int = 0, component: String = root,
        top: String = component) = AppTaskPolicy.Task(id, 20 + id, display, user, component, top, display == 0)

    private fun policy() = IndependentTaskPolicy(setOf(1), root, 0, 9)

    private fun boundPolicy() = policy().also {
        assertEquals(task(), it.discover(listOf(task(1, 0), task())))
    }

    @Test fun unboundFindOwnedDoesNotDiscoverOrAdoptAnEligibleTask() {
        val owner = policy()
        val snapshot = listOf(task(1, 0), task())

        assertNull(owner.findOwned(snapshot))
        assertEquals(listOf(task()), owner.cleanupCandidates(snapshot))
        assertNull(owner.findOwned(snapshot))
    }

    @Test fun emptyTopStillRejectsCaptureButAllowsOwnedCleanup() {
        assertCaptureRejectedAndCleanupSucceeds("")
    }

    @Test fun foreignTopStillRejectsCaptureButAllowsOwnedCleanup() {
        assertCaptureRejectedAndCleanupSucceeds("other.app/.Prompt")
    }

    private fun assertCaptureRejectedAndCleanupSucceeds(top: String) {
        val owner = boundPolicy()
        val original = task(1, 0)
        val unavailable = task(top = top)
        val backend = SnapshotBackend(owner, mutableListOf(original, unavailable))
        assertThrows(IOException::class.java) { owner.find(backend.tasks()) }
        assertThrows(IOException::class.java) { owner.verify(unavailable) }
        owner.verifyOwnership(unavailable)
        assertEquals(unavailable, owner.findOwned(backend.tasks()))
        assertEquals(listOf(unavailable), owner.cleanupCandidates(backend.tasks()))

        IndependentTaskCleanup.close(owner, backend)

        assertEquals(listOf(2), backend.removed)
        assertEquals(listOf(original), backend.observed)
        assertEquals(1, backend.releases)
    }

    @Test fun freshTopLossBeforeRemovalDoesNotRevokeOwnership() {
        for (top in listOf("", "other.app/.Prompt")) {
            val owner = boundPolicy()
            val original = task(1, 0)
            val backend = SnapshotBackend(owner, mutableListOf(original, task()))
            backend.beforeRemoval = { backend.observed[1] = task(top = top) }

            IndependentTaskCleanup.close(owner, backend)

            assertEquals(listOf(2), backend.removed)
            assertEquals(listOf(original), backend.observed)
            assertEquals(1, backend.releases)
        }
    }

    @Test fun freshMoveToPrimaryBeforeRemovalIsRejectedWithoutDeletingEitherTask() {
        val owner = boundPolicy()
        val original = task(1, 0)
        val moved = task(display = 0)
        val backend = SnapshotBackend(owner, mutableListOf(original, task()))
        backend.beforeRemoval = { backend.observed[1] = moved }

        val failure = assertThrows(IOException::class.java) { IndependentTaskCleanup.close(owner, backend) }

        assertTrue(failure.message.orEmpty().contains("CLEANUP_UNCONFIRMED"))
        assertEquals(listOf(2), backend.attempted)
        assertTrue(backend.removed.isEmpty())
        assertEquals(listOf(original, moved), backend.observed)
        assertEquals(1, backend.releases)
        assertThrows(IOException::class.java) { owner.find(backend.tasks()) }
    }

    @Test fun boundDisplayUserAndExactRootChangesStillRejectOwnershipAndCleanup() {
        val invalidTasks = listOf(task(display = 0), task(display = 11), task(user = 10),
            task(component = "example.app/.OtherRoot"), task(component = "other.app/.Main"))
        for (invalid in invalidTasks) {
            val owner = boundPolicy()
            val original = task(1, 0)
            val backend = SnapshotBackend(owner, mutableListOf(original, invalid))

            assertThrows(IOException::class.java) { owner.find(backend.tasks()) }
            assertThrows(IOException::class.java) { owner.findOwned(backend.tasks()) }
            assertThrows(IOException::class.java) { owner.verifyOwnership(invalid) }
            assertThrows(IOException::class.java) { IndependentTaskCleanup.close(owner, backend) }

            assertTrue(backend.attempted.isEmpty())
            assertTrue(backend.removed.isEmpty())
            assertEquals(listOf(original, invalid), backend.observed)
            assertEquals(1, backend.releases)
        }
    }

    @Test fun vanishedBoundTaskDoesNotAdoptOrRemoveANewTaskWithTheSameRoot() {
        val owner = boundPolicy()
        val original = task(1, 0)
        val replacement = task(3)
        val backend = SnapshotBackend(owner, mutableListOf(original, replacement))

        assertNull(owner.find(backend.tasks()))
        assertNull(owner.findOwned(backend.tasks()))
        assertNull(owner.cleanupCurrent(backend.tasks(), 2))
        assertTrue(owner.cleanupCandidates(backend.tasks()).isEmpty())
        IndependentTaskCleanup.close(owner, backend)

        assertTrue(backend.attempted.isEmpty())
        assertEquals(listOf(original, replacement), backend.observed)
        assertEquals(1, backend.releases)
    }

    @Test fun existingAndNegativeTaskIdsNeverPassOwnership() {
        val owner = policy()
        val existing = task(1, top = "")
        assertThrows(IOException::class.java) { owner.verifyOwnership(existing) }
        assertThrows(IOException::class.java) { owner.verifyOwnership(task(-1)) }
        assertThrows(IOException::class.java) { owner.cleanupCurrent(listOf(existing), 1) }
        assertTrue(owner.cleanupCandidates(listOf(existing)).isEmpty())
        assertNull(owner.findOwned(listOf(existing)))
    }

    @Test fun duplicateBoundIdentityRejectsCaptureAndCleanupWhileStillReleasingDisplay() {
        val owner = boundPolicy()
        val original = task(1, 0)
        val backend = SnapshotBackend(owner, mutableListOf(original, task(), task(top = "")))
        assertThrows(IOException::class.java) { owner.find(backend.tasks()) }
        assertThrows(IOException::class.java) { owner.findOwned(backend.tasks()) }
        assertThrows(IOException::class.java) { owner.cleanupCurrent(backend.tasks(), 2) }

        assertThrows(IOException::class.java) { IndependentTaskCleanup.close(owner, backend) }

        assertTrue(backend.attempted.isEmpty())
        assertTrue(backend.removed.isEmpty())
        assertEquals(listOf(original, task(), task(top = "")), backend.observed)
        assertEquals(1, backend.releases)
    }

    @Test fun freshDuplicateIdentityBeforeRemovalIsRejectedInsteadOfTreatedAsMissing() {
        val owner = boundPolicy()
        val original = task(1, 0)
        val backend = SnapshotBackend(owner, mutableListOf(original, task()))
        backend.beforeRemoval = { backend.observed.add(task(top = "")) }

        assertThrows(IOException::class.java) { IndependentTaskCleanup.close(owner, backend) }

        assertEquals(listOf(2), backend.attempted)
        assertTrue(backend.removed.isEmpty())
        assertEquals(listOf(original, task(), task(top = "")), backend.observed)
        assertEquals(1, backend.releases)
    }

    @Test fun unboundCleanupRejectsDuplicateIdEvenWhenOnlyOneRootMatches() {
        val owner = policy()
        val backend = SnapshotBackend(owner, mutableListOf(task(), task(component = "other.app/.Main")))

        assertThrows(IOException::class.java) { IndependentTaskCleanup.close(owner, backend) }

        assertTrue(backend.attempted.isEmpty())
        assertTrue(backend.removed.isEmpty())
        assertEquals(2, backend.observed.size)
        assertEquals(1, backend.releases)
    }

    @Test fun failedStartupWithEmptyTopReclaimsOnlyExactNewOwnedDisplayTask() {
        val owner = policy()
        val original = task(1, 0)
        val unavailable = task(top = "")
        val others = listOf(original, task(3, 0), task(4, user = 10),
            task(5, component = "other.app/.Main"), task(6, 11))
        val backend = SnapshotBackend(owner, (others + unavailable).toMutableList())

        assertThrows(IOException::class.java) { owner.find(backend.tasks()) }
        assertNull(owner.findOwned(backend.tasks()))
        assertEquals(listOf(unavailable), owner.cleanupCandidates(backend.tasks()))
        IndependentTaskCleanup.close(owner, backend)

        assertEquals(listOf(2), backend.removed)
        assertEquals(others, backend.observed)
        assertEquals(1, backend.releases)
    }

    @Test fun displayReleaseFailureStillReportsUnknownAfterTopUnavailableTaskWasRemoved() {
        val owner = boundPolicy()
        val original = task(1, 0)
        val backend = SnapshotBackend(owner, mutableListOf(original, task(top = "")))
        backend.failRelease = true

        val failure = assertThrows(IOException::class.java) { IndependentTaskCleanup.close(owner, backend) }

        assertTrue(failure.message.orEmpty().contains("CLEANUP_UNCONFIRMED"))
        assertEquals("TEST_RELEASE_FAILURE", failure.cause?.message)
        assertEquals(listOf(2), backend.removed)
        assertEquals(listOf(original), backend.observed)
        assertEquals(1, backend.releases)
    }

    /** Supplies two snapshots to the shared pure check; does not exercise AndroidTaskAccess reflection. */
    private class SnapshotBackend(private val policy: IndependentTaskPolicy,
        val observed: MutableList<AppTaskPolicy.Task>) : IndependentTaskCleanup.Backend {
        val attempted = mutableListOf<Int>()
        val removed = mutableListOf<Int>()
        var releases = 0
        var failRelease = false
        var beforeRemoval: (() -> Unit)? = null

        override fun tasks(): List<AppTaskPolicy.Task> = observed.toList()

        override fun remove(task: AppTaskPolicy.Task) {
            attempted.add(task.id)
            beforeRemoval?.invoke()
            val current = policy.cleanupCurrent(observed.toList(), task.id) ?: return
            observed.removeAll { it.id == current.id }
            removed.add(current.id)
        }

        override fun releaseDisplay() {
            releases++
            if (failRelease) { throw IOException("TEST_RELEASE_FAILURE") }
        }
    }
}
