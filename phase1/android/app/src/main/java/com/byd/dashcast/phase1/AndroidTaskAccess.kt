package com.byd.dashcast.phase1

import android.content.ComponentName
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** Authorized Android-10 task identity and owned-task reclamation only. No task/stack movement API. */
internal class AndroidTaskAccess {
    private val type = Class.forName("android.app.IActivityTaskManager")
    private val service = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
    private val tasks = type.getMethod("getTasks", Int::class.javaPrimitiveType)
    private val remove = type.getMethod("removeTask", Int::class.javaPrimitiveType)

    fun currentUser(): Int = Class.forName("android.app.ActivityManager").getMethod("getCurrentUser")
        .invoke(null) as Int

    /** One Binder read; no window-stack enumeration on the frame path. */
    fun projectionTasks(): List<AppTaskPolicy.Task> {
        val values = invoke(tasks, 256) as? List<*> ?: throw IOException("APP_TASK_LIST_UNAVAILABLE")
        if (values.size > 256 || values.any { it == null }) { throw IOException("APP_TASK_LIST_LIMIT") }
        return values.filterNotNull().mapNotNull { raw ->
            val root = component(raw, "realActivity") ?: component(raw, "baseActivity") ?: return@mapNotNull null
            AppTaskPolicy.Task(integer(raw, "taskId"), integer(raw, "stackId"), integer(raw, "displayId"),
                integer(raw, "userId"), root.flattenToString(),
                component(raw, "topActivity")?.flattenToString() ?: "",
                raw.javaClass.getField("isTopVisible").getBoolean(raw))
        }
    }

    fun removeProjectionTask(task: AppTaskPolicy.Task, display: Int, before: Set<Int>) {
        require(display > 0 && task.id !in before)
        val current = projectionTasks().singleOrNull { it.id == task.id } ?: return
        IndependentTaskPolicy(before, task.root, task.user, display).verify(current)
        if (invoke(remove, task.id) != true) {
            throw IOException("CLEANUP_UNCONFIRMED independent application task removal rejected")
        }
        val deadline = System.nanoTime() + 1_000_000_000L
        while (projectionTasks().any { it.id == task.id }) {
            if (System.nanoTime() >= deadline) {
                throw IOException("CLEANUP_UNCONFIRMED independent application task still present")
            }
            Thread.sleep(25)
        }
    }

    private fun integer(value: Any, name: String) = value.javaClass.getField(name).getInt(value)
    private fun component(value: Any, name: String) = value.javaClass.getField(name).get(value) as? ComponentName

    private fun invoke(method: Method, vararg args: Any): Any? {
        try {
            return method.invoke(service, *args)
        } catch (error: InvocationTargetException) {
            throw IOException("APP_TASK_ATM_REJECTED method=${method.name}", error.cause ?: error)
        }
    }
}
