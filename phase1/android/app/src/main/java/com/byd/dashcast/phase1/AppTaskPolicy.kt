package com.byd.dashcast.phase1

import java.io.IOException

/** Identity and routing policy for ONE task, never a package-wide kill/relaunch.
 * @author Codex
 * @date 2026/10/22
 */
internal object AppTaskPolicy {
    data class Task(
        @JvmField val id: Int,
        @JvmField val stack: Int,
        @JvmField val display: Int,
        @JvmField val user: Int,
        @JvmField val root: String,
        @JvmField val top: String,
        @JvmField val visible: Boolean
    )

    @JvmStatic
    fun requireOwned(task: Task?, id: Int, user: Int, root: String): Task {
        if (task == null || task.id != id || task.user != user || task.root != root) {
            throw IOException("APP_TASK_IDENTITY_CHANGED; no unrelated task will be moved")
        }
        return task
    }

    @JvmStatic
    fun destination(task: Task, remoteDisplay: Int, nativeIconRequested: Boolean): Int {
        require(remoteDisplay > 0 && task.id >= 0)
        if (task.display != 0 && task.display != remoteDisplay) {
            throw IOException("APP_TASK_MOVED_TO_UNOWNED_DISPLAY")
        }
        return if (nativeIconRequested || task.display == 0 && task.visible) 0 else remoteDisplay
    }

    @JvmStatic
    fun requireIsolated(task: Task, stackTasks: IntArray) {
        if (stackTasks.size != 1 || stackTasks[0] != task.id) {
            throw IOException("APP_TASK_STACK_NOT_ISOLATED; no other application's stack will be moved")
        }
    }
}
