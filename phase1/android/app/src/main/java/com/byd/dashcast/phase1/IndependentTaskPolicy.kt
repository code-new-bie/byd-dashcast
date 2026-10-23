package com.byd.dashcast.phase1

import java.io.IOException

/** Binds once to a newly created task on the owned display, never to a package-wide snapshot.
 * @author Codex
 * @date 2026/10/23
 */
internal class IndependentTaskPolicy(
    before: Set<Int>,
    private val component: String,
    private val user: Int,
    private val display: Int
) {
    private val existing = before.toSet()
    private var ownedId: Int? = null
    init { require(display > 0 && user >= 0) }

    /** Startup discovery ignores concurrent, legitimate primary-screen launches. */
    @Throws(IOException::class)
    fun discover(tasks: List<AppTaskPolicy.Task>): AppTaskPolicy.Task? {
        if (ownedId != null) { return current(tasks) }
        val candidates = tasks.filter { isNewMatching(it) && it.display == display }
        if (candidates.size > 1) { throw IOException("APP_INDEPENDENT_TASK_AMBIGUOUS") }
        return candidates.singleOrNull()?.also { verify(it); ownedId = it.id }
    }

    /** Once bound, unrelated new tasks cannot invalidate or replace the owned identity. */
    @Throws(IOException::class)
    fun find(tasks: List<AppTaskPolicy.Task>): AppTaskPolicy.Task? {
        if (ownedId != null) { return current(tasks) }
        val found = discover(tasks)
        if (found == null && tasks.any { isNewMatching(it) && it.display != display }) {
            throw IOException("APP_INDEPENDENT_TASK_REUSED; no existing task is adopted or moved")
        }
        return found
    }

    /** Unbound startup failures may reclaim exact new tasks on our exclusive display only. */
    @Throws(IOException::class)
    fun cleanupCandidates(tasks: List<AppTaskPolicy.Task>): List<AppTaskPolicy.Task> {
        if (ownedId != null) { return listOfNotNull(current(tasks)) }
        return tasks.filter { isNewMatching(it) && it.display == display }.onEach { verify(it) }
    }

    private fun current(tasks: List<AppTaskPolicy.Task>): AppTaskPolicy.Task? {
        val matches = tasks.filter { it.id == ownedId }
        if (matches.size > 1) { throw IOException("APP_INDEPENDENT_TASK_AMBIGUOUS") }
        return matches.singleOrNull()?.also { verify(it) }
    }

    @Throws(IOException::class)
    fun verify(task: AppTaskPolicy.Task) {
        if (task.id < 0 || task.id in existing || task.user != user || task.root != component || task.display != display ||
            task.top.substringBefore('/') != component.substringBefore('/')) {
            throw IOException("APP_INDEPENDENT_TASK_CHANGED; never capture display zero")
        }
    }

    private fun isNewMatching(task: AppTaskPolicy.Task): Boolean =
        task.user == user && task.root == component && task.id !in existing
}
