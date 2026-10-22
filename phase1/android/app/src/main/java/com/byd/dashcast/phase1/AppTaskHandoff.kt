package com.byd.dashcast.phase1

import java.io.IOException

/** Testable same-task routing. Physical ATM/SF work belongs to the two owned ports.
 * @author Codex
 * @date 2026/10/22
 */
internal class AppTaskHandoff(
    private val identity: AppTaskPolicy.Task,
    private val remoteDisplay: Int,
    private val tasks: TaskPort,
    private val source: SourcePort
) : AutoCloseable {
    interface TaskPort {
        @Throws(Exception::class)
        fun read(id: Int, user: Int, root: String): AppTaskPolicy.Task?
        @Throws(Exception::class)
        fun transfer(task: AppTaskPolicy.Task, display: Int, show: Boolean): AppTaskPolicy.Task
    }

    interface SourcePort {
        fun invalidate()
        @Throws(Exception::class)
        fun select(display: Int)
    }

    var sourceDisplay: Int = -1
        private set
    private var closed = false

    /** True means an output epoch changed; queued encoder pictures must not be forwarded. */
    @Throws(Exception::class)
    fun route(nativeIconRequested: Boolean): Boolean {
        check(!closed) { "APP_TASK_ROUTER_CLOSED" }
        var task = AppTaskPolicy.requireOwned(tasks.read(identity.id, identity.user, identity.root),
            identity.id, identity.user, identity.root)
        if (task.top.substringBefore('/') != task.root.substringBefore('/')) {
            throw IOException("APP_TASK_FOREIGN_TOP_ACTIVITY; no unrelated UI is captured")
        }
        val wanted = AppTaskPolicy.destination(task, remoteDisplay, nativeIconRequested)
        val foregroundRequest = nativeIconRequested && wanted == 0 && !task.visible
        val changed = task.display != sourceDisplay || wanted != task.display || foregroundRequest
        if (changed) { source.invalidate() }
        if (task.display != wanted || foregroundRequest) {
            task = AppTaskPolicy.requireOwned(tasks.transfer(task, wanted, nativeIconRequested),
                identity.id, identity.user, identity.root)
            if (task.display != wanted) { throw IOException("APP_TASK_TRANSFER_NOT_CONFIRMED") }
        }
        if (wanted == 0 && !task.visible) { throw IOException("APP_TASK_PRIMARY_VISIBILITY_UNCONFIRMED") }
        if (changed) {
            source.select(task.display)
            sourceDisplay = task.display
        }
        return changed
    }

    @Throws(Exception::class)
    override fun close() {
        if (closed) { return }
        closed = true
        source.invalidate()
        val found = tasks.read(identity.id, identity.user, identity.root) ?: return
        val task = AppTaskPolicy.requireOwned(found, identity.id, identity.user, identity.root)
        if (task.display == remoteDisplay) {
            val returned = AppTaskPolicy.requireOwned(tasks.transfer(task, 0, false),
                identity.id, identity.user, identity.root)
            if (returned.display != 0) { throw IOException("CLEANUP_UNCONFIRMED app task not returned to primary") }
        }
    }
}
