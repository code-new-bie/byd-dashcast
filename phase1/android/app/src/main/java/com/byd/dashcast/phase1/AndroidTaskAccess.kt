package com.byd.dashcast.phase1

import android.content.ComponentName
import java.io.IOException
import java.lang.reflect.InvocationTargetException

/** Exact Android-10 ATM API confirmed in the OEM framework, executed only as uid=2000.
 * No task deletion, package stop, new intent, settings writes, or task-id guessing.
 * @author Codex
 * @date 2026/10/22
 */
internal class AndroidTaskAccess {
    private val type = Class.forName("android.app.IActivityTaskManager")
    private val service = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
    private val tasks = type.getMethod("getTasks", Int::class.javaPrimitiveType)
    private val stacks = type.getMethod("getAllStackInfos")
    private val windowing = type.getMethod("setTaskWindowingMode", Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
    private val move = type.getMethod("moveStackToDisplay", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    private val front = type.getMethod("moveTaskToFront", Class.forName("android.app.IApplicationThread"),
        String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, android.os.Bundle::class.java)

    data class Stack(val id: Int, val display: Int, val position: Int, val ids: IntArray,
        val visible: Boolean, val topPackage: String?)

    fun currentUser(): Int = Class.forName("android.app.ActivityManager").getMethod("getCurrentUser")
        .invoke(null) as Int

    fun allTasks(): List<AppTaskPolicy.Task> {
        val stackInfo = allStacks()
        val primary = stackInfo.filter { it.display == 0 && it.visible && it.ids.isNotEmpty() }
            .maxByOrNull { it.position }
        return list(invoke(tasks, 256)).mapNotNull { raw ->
            val root = component(raw, "realActivity") ?: component(raw, "baseActivity")
                ?: return@mapNotNull null
            val top = component(raw, "topActivity")?.flattenToString() ?: ""
            val id = integer(raw, "taskId")
            val display = integer(raw, "displayId")
            AppTaskPolicy.Task(id, integer(raw, "stackId"), display, integer(raw, "userId"),
                root.flattenToString(), top,
                display == 0 && primary?.ids?.lastOrNull() == id &&
                    primary?.topPackage == root.packageName && top.substringBefore('/') == root.packageName)
        }
    }

    fun find(packageName: String, user: Int): AppTaskPolicy.Task? {
        val found = allTasks().filter { it.user == user && it.root.substringBefore('/') == packageName }
        if (found.size > 1) { throw IOException("APP_TASK_AMBIGUOUS; no existing navigation task relaunched") }
        return found.singleOrNull()
    }

    fun owned(id: Int, user: Int, root: String): AppTaskPolicy.Task? {
        val found = allTasks().singleOrNull { it.id == id } ?: return null
        return AppTaskPolicy.requireOwned(found, id, user, root)
    }

    fun transfer(original: AppTaskPolicy.Task, target: Int, show: Boolean): AppTaskPolicy.Task {
        var task = AppTaskPolicy.requireOwned(owned(original.id, original.user, original.root),
            original.id, original.user, original.root)
        if (task.display != target) {
            // A fullscreen primary stack may contain unrelated tasks. Detach only this task
            // into its own freeform stack before moving any stack across displays.
            var stack = allStacks().singleOrNull { it.id == task.stack }
                ?: throw IOException("APP_TASK_STACK_MISSING")
            if (stack.ids.size != 1 || stack.ids[0] != task.id) {
                invoke(windowing, task.id, 5, false)
                task = AppTaskPolicy.requireOwned(owned(task.id, task.user, task.root),
                    task.id, task.user, task.root)
                stack = allStacks().singleOrNull { it.id == task.stack }
                    ?: throw IOException("APP_TASK_DETACHED_STACK_MISSING")
            }
            AppTaskPolicy.requireIsolated(task, stack.ids)
            invoke(move, task.stack, target)
        }
        // Freeform was only a temporary isolation step. Fill the destination display,
        // rather than leaving a small floating map inside the instrument capture.
        invoke(windowing, task.id, 1, target == 0 && show)
        if (target == 0) {
            // No launcher intent / force-stop: keep the same task and running route.
            if (show) {
                try {
                    front.invoke(service, null, "com.android.shell", task.id, 0,
                        android.app.ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
                } catch (error: InvocationTargetException) {
                    throw IOException("APP_TASK_FOREGROUND_REJECTED", error.cause ?: error)
                }
            }
        }
        task = AppTaskPolicy.requireOwned(owned(task.id, task.user, task.root), task.id, task.user, task.root)
        if (task.display != target) { throw IOException("APP_TASK_TRANSFER_NOT_CONFIRMED") }
        return task
    }

    private fun allStacks(): List<Stack> = list(invoke(stacks)).map { raw ->
        val ids = raw.javaClass.getField("taskIds").get(raw) as? IntArray
            ?: throw IOException("APP_TASK_STACK_IDS_MISSING")
        if (ids.size > 256) { throw IOException("APP_TASK_STACK_LIMIT") }
        Stack(integer(raw, "stackId"), integer(raw, "displayId"), integer(raw, "position"), ids.clone(),
            raw.javaClass.getField("visible").getBoolean(raw), component(raw, "topActivity")?.packageName)
    }

    private fun list(value: Any?): List<Any> {
        val values = value as? List<*> ?: throw IOException("APP_TASK_LIST_UNAVAILABLE")
        if (values.size > 256 || values.any { it == null }) { throw IOException("APP_TASK_LIST_LIMIT") }
        return values.filterNotNull()
    }

    private fun integer(value: Any, name: String) = value.javaClass.getField(name).getInt(value)
    private fun component(value: Any, name: String) = value.javaClass.getField(name).get(value) as? ComponentName

    private fun invoke(method: java.lang.reflect.Method, vararg args: Any): Any? {
        try {
            return method.invoke(service, *args)
        } catch (error: InvocationTargetException) {
            throw IOException("APP_TASK_ATM_REJECTED method=${method.name}", error.cause ?: error)
        }
    }
}
