package com.byd.dashcast.phase1

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Cold/hot original-icon launches and failure cleanup use the real ownership/cleanup classes.
 * @author Codex
 * @date 2026/10/23
 */
class IndependentTaskOwnershipTest {
    private val root = "example.app/.Main"
    private fun task(id: Int, display: Int = 9, user: Int = 0, component: String = root) =
        AppTaskPolicy.Task(id, 20 + id, display, user, component, component, display == 0)
    private fun policy(before: Set<Int> = emptySet()) = IndependentTaskPolicy(before, root, 0, 9)

    @Test fun newPrimaryFromOriginalIconDoesNotInvalidateTheBoundTask() {
        val policy = policy()
        val secondary = task(2)
        assertEquals(secondary, policy.discover(listOf(secondary)))
        repeat(1000) { index ->
            val primary = task(3 + index, 0)
            val tasks = if (index % 2 == 0) listOf(primary, secondary) else listOf(secondary, primary)
            assertEquals(secondary, policy.find(tasks))
            assertEquals(listOf(secondary), policy.cleanupCandidates(tasks))
        }
    }

    @Test fun concurrentPrimaryLaunchDuringDiscoveryIsNotAdoptedOrTreatedAsOwnedMigration() {
        val policy = policy()
        val primary = task(3, 0)
        assertNull(policy.discover(listOf(primary)))
        assertEquals(emptyList<AppTaskPolicy.Task>(), policy.cleanupCandidates(listOf(primary)))
        assertEquals(task(2), policy.discover(listOf(primary, task(2))))
    }

    @Test fun knownTaskMovingToPrimaryRemainsAnErrorAndCannotBeRemoved() {
        val policy = policy()
        policy.discover(listOf(task(2)))
        val moved = listOf(task(2, 0), task(3, 0))
        assertThrows(IOException::class.java) { policy.find(moved) }
        assertThrows(IOException::class.java) { policy.cleanupCandidates(moved) }
    }

    @Test fun vanishedOwnedTaskCannotBeReplacedByAnotherTaskWithTheSameRoot() {
        val policy = policy()
        policy.discover(listOf(task(2)))
        assertNull(policy.find(listOf(task(3))))
        assertEquals(emptyList<AppTaskPolicy.Task>(), policy.cleanupCandidates(listOf(task(3))))
    }

    @Test fun unrelatedTasksAndNewPrimaryAreNeverCleanupCandidatesAfterBind() {
        val policy = policy(setOf(1))
        policy.discover(listOf(task(1, 0), task(2)))
        val observed = listOf(task(1, 0), task(2), task(3, 0), task(4, user = 10),
            task(5, component = "other.app/.Main"), task(6, display = 11))
        assertEquals(listOf(task(2)), policy.cleanupCandidates(observed))
    }

    @Test fun failedStartupMayReclaimNewTasksOnlyOnItsOwnDisplay() {
        val policy = policy(setOf(1))
        val observed = listOf(task(1), task(2), task(3, 0), task(4, user = 10), task(5))
        assertEquals(listOf(task(2), task(5)), policy.cleanupCandidates(observed))
    }

    @Test fun ambiguousOwnedDisplayAtStartupStillFailsClosed() {
        assertThrows(IOException::class.java) { policy().discover(listOf(task(2), task(3))) }
    }

    @Test fun identityUserRootAndTopChangesCannotPassTheBoundCheck() {
        for (invalid in listOf(task(2, user = 10), task(2, component = "other.app/.Main"),
            task(2).copy(top = "other.app/.Prompt"), task(2, 11))) {
            val policy = policy()
            policy.discover(listOf(task(2)))
            assertThrows(IOException::class.java) { policy.find(listOf(invalid)) }
        }
    }

    @Test fun originalTaskSnapshotCannotBeChangedThroughTheCallersMutableSet() {
        val before = mutableSetOf(1)
        val policy = policy(before)
        before.clear()
        assertNull(policy.discover(listOf(task(1))))
        assertThrows(IOException::class.java) { policy.verify(task(1)) }
    }

    @Test fun cleanupPreservesLegitimateNewPrimaryAndReleasesOnlyTheOwnedDisplay() {
        val policy = policy()
        policy.discover(listOf(task(2)))
        val backend = FakeBackend(mutableListOf(task(3, 0), task(2)))
        IndependentTaskCleanup.close(policy, backend)
        assertEquals(listOf(2), backend.removed)
        assertEquals(listOf(task(3, 0)), backend.observed)
        assertEquals(1, backend.releases)
    }

    @Test fun allCleanupStepsAreAttemptedEvenWhenFirstRemovalAndDisplayReleaseFail() {
        val backend = FakeBackend(mutableListOf(task(2), task(5), task(3, 0)))
        backend.failRemoval = 2
        backend.failRelease = true
        val failure = assertThrows(IOException::class.java) { IndependentTaskCleanup.close(policy(), backend) }
        assertEquals(listOf(2, 5), backend.attempted)
        assertEquals(listOf(5), backend.removed)
        assertEquals(listOf(task(2), task(3, 0)), backend.observed)
        assertEquals(1, backend.releases)
        assertEquals(1, failure.cause!!.suppressed.size)
    }

    @Test fun movedOwnedTaskIsNotDeletedAndDisplayReleaseStillRuns() {
        val policy = policy()
        policy.discover(listOf(task(2)))
        val backend = FakeBackend(mutableListOf(task(2, 0), task(3, 0)))
        assertThrows(IOException::class.java) { IndependentTaskCleanup.close(policy, backend) }
        assertTrue(backend.attempted.isEmpty())
        assertEquals(2, backend.observed.size)
        assertEquals(1, backend.releases)
    }

    @Test fun failedTaskQueryStillReleasesTheDisplayAndReportsUnknownCleanup() {
        val backend = FakeBackend(mutableListOf())
        backend.failQuery = true
        assertThrows(IOException::class.java) { IndependentTaskCleanup.close(policy(), backend) }
        assertTrue(backend.attempted.isEmpty())
        assertEquals(1, backend.releases)
    }

    @Test fun displayCreatedBeforePolicyFailureIsReleasedWithoutAnyTaskQuery() {
        val backend = FakeBackend(mutableListOf(task(3, 0)))
        backend.failQuery = true
        IndependentTaskCleanup.close(null, backend)
        assertEquals(listOf(task(3, 0)), backend.observed)
        assertEquals(1, backend.releases)
    }

    @Test fun fatalOemLinkageFailureStillReleasesDisplayAndIsNotDowngradedToSuccess() {
        var releases = 0
        val backend = object : IndependentTaskCleanup.Backend {
            override fun tasks(): List<AppTaskPolicy.Task> = throw NoSuchMethodError("TEST_OEM_LINKAGE")
            override fun remove(task: AppTaskPolicy.Task) { fail("No task identity exists") }
            override fun releaseDisplay() { releases++ }
        }
        assertThrows(NoSuchMethodError::class.java) { IndependentTaskCleanup.close(policy(), backend) }
        assertEquals(1, releases)
    }

    @Test fun fatalReleaseFailureRetainsEarlierCleanupErrorWithoutBeingSwallowed() {
        val backend = object : IndependentTaskCleanup.Backend {
            override fun tasks(): List<AppTaskPolicy.Task> = throw IOException("TEST_QUERY_FAILURE")
            override fun remove(task: AppTaskPolicy.Task) { fail("No task identity exists") }
            override fun releaseDisplay() { throw LinkageError("TEST_RELEASE_LINKAGE") }
        }
        val failure = assertThrows(LinkageError::class.java) { IndependentTaskCleanup.close(policy(), backend) }
        assertEquals(1, failure.suppressed.size)
        assertEquals("TEST_QUERY_FAILURE", failure.suppressed[0].message)
    }

    private class FakeBackend(val observed: MutableList<AppTaskPolicy.Task>) : IndependentTaskCleanup.Backend {
        val attempted = mutableListOf<Int>()
        val removed = mutableListOf<Int>()
        var releases = 0
        var failRemoval = -1
        var failRelease = false
        var failQuery = false

        override fun tasks(): List<AppTaskPolicy.Task> {
            if (failQuery) { throw IOException("TEST_TASK_QUERY_FAILURE") }
            return observed.toList()
        }

        override fun remove(task: AppTaskPolicy.Task) {
            attempted.add(task.id)
            if (task.id == failRemoval) { throw IOException("TEST_REMOVE_FAILURE") }
            observed.removeAll { it.id == task.id }
            removed.add(task.id)
        }

        override fun releaseDisplay() {
            releases++
            if (failRelease) { throw IOException("TEST_RELEASE_FAILURE") }
        }
    }
}
