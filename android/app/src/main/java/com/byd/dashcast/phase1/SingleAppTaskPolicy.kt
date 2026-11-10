package com.byd.dashcast.phase1

import java.io.IOException

/** Same-user task identity survives in-package forwarding. Never authorizes task deletion. */
internal class SingleAppTaskPolicy(val packageName: String, val user: Int, val display: Int,
    before: Set<Int>) {
    private val before = before.toSet()
    private val adopted = mutableSetOf<Int>()
    var taskId: Int = -1
        private set

    init { require(packageName.isNotBlank() && user >= 0 && display > 0) }

    fun adopt(task: AppTaskPolicy.Task) {
        requireIdentity(task)
        if (taskId >= 0 && task.id != taskId && task.id in before && task.id !in adopted) {
            throw IOException("APP_DUPLEX_PREEXISTING_SUCCESSOR_REJECTED")
        }
        adopted.add(task.id)
        taskId = task.id
    }

    /** A redirect can replace a forwarding launcher, but never adopt a different pre-existing task. */
    fun find(tasks: List<AppTaskPolicy.Task>): AppTaskPolicy.Task? {
        if (tasks.count { it.id == taskId } > 1) { throw IOException("APP_DUPLEX_DUPLICATE_TASK_ID") }
        val current = tasks.singleOrNull { it.id == taskId }
        if (current != null) { requireIdentity(current); return current }
        val successors = tasks.filter { it.user == user && it.display == display &&
            it.root.substringBefore('/') == packageName && (it.id !in before || it.id in adopted) }
        if (successors.size > 1) { throw IOException("APP_DUPLEX_AMBIGUOUS_TASK") }
        return successors.singleOrNull()?.also { adopt(it) }
    }

    fun requireIdentity(task: AppTaskPolicy.Task) {
        if (task.id < 0 || task.user != user || task.root.substringBefore('/') != packageName) {
            throw IOException("APP_DUPLEX_IDENTITY_CHANGED ${describe(task)}")
        }
    }

    fun framesAllowed(task: AppTaskPolicy.Task): Boolean {
        requireIdentity(task)
        return task.id == taskId && task.display == display && task.top.substringBefore('/') == packageName
    }

    fun returnCandidates(tasks: List<AppTaskPolicy.Task>): List<AppTaskPolicy.Task> = tasks.filter {
        it.user == user && it.display == display && (it.id in adopted || it.id !in before)
    }

    companion object {
        @JvmStatic fun describe(task: AppTaskPolicy.Task?): String = task?.let {
            "task=${it.id} stack=${it.stack} display=${it.display} user=${it.user} " +
                "root=${IndependentAppPlan.safe(it.root)} top=${IndependentAppPlan.safe(it.top)} visible=${it.visible}"
        } ?: "task=missing"
    }
}
