package com.byd.dashcast.phase1

import java.io.IOException

/** One same-user business task, with explicitly observed in-package forwarding successors.
 * Never authorizes package-wide removal, unrelated displays or existing primary task deletion.
 * @author Codex
 * @date 2026/10/25
 */
internal class MirrorTaskPolicy(val packageName: String, val user: Int, val display: Int,
    before: Set<Int>) {
    private val before = before.toSet()
    private val owned = linkedMapOf<Int, String>()
    var taskId = -1
        private set

    init { require(display > 0 && user >= 0 && packageName.isNotBlank()) }

    fun adopt(task: AppTaskPolicy.Task, initial: Boolean = false) {
        requireIdentity(task)
        if (!initial && task.id in before && task.id !in owned) {
            throw IOException("MIRROR_PREEXISTING_SUCCESSOR_REJECTED")
        }
        owned[task.id]?.let { if (it != task.root) { throw IOException("MIRROR_TASK_ROOT_CHANGED") } }
        owned[task.id] = task.root
        taskId = task.id
    }

    fun find(tasks: List<AppTaskPolicy.Task>): AppTaskPolicy.Task? {
        val current = tasks.filter { it.id == taskId }
        if (current.size > 1) { throw IOException("MIRROR_DUPLICATE_TASK_ID") }
        current.singleOrNull()?.let { requireIdentity(it); return it }
        val candidates = tasks.filter { it.user == user && it.display == display &&
            it.root.substringBefore('/') == packageName && (it.id !in before || it.id in owned) }
        if (candidates.size > 1) { throw IOException("MIRROR_TASK_AMBIGUOUS") }
        return candidates.singleOrNull()?.also { adopt(it) }
    }

    fun returnCandidates(tasks: List<AppTaskPolicy.Task>): List<AppTaskPolicy.Task> {
        val tracked = tasks.filter { it.id in owned }
        if (tracked.map { it.id }.distinct().size != tracked.size) { throw IOException("MIRROR_DUPLICATE_TASK_ID") }
        tracked.forEach { requireIdentity(it) }
        return tasks.filter {
            it.user == user && it.display == display && it.root.substringBefore('/') == packageName &&
                (owned[it.id] == it.root || it.id !in before)
        }
    }

    fun disposableIconTask(task: AppTaskPolicy.Task): Boolean = task.id >= 0 && task.id != taskId &&
        task.id !in before && task.id !in owned && task.user == user && task.display == 0 &&
        task.root.substringBefore('/') == packageName && task.top.substringBefore('/') == packageName && task.launcher

    fun requireIdentity(task: AppTaskPolicy.Task) {
        if (task.id < 0 || task.user != user || task.root.substringBefore('/') != packageName ||
            task.display !in setOf(0, display) || owned[task.id]?.let { it != task.root } == true) {
            throw IOException("MIRROR_TASK_IDENTITY_CHANGED ${SingleAppTaskPolicy.describe(task)}")
        }
    }
}
