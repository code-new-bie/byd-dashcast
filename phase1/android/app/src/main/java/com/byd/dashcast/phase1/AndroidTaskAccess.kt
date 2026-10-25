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

    /** OEM's visible field is preferred. AOSP29 lacks it: use one bounded structured stack snapshot. */
    fun projectionTasks(): List<AppTaskPolicy.Task> {
        val values = invoke(tasks, 256) as? List<*> ?: throw IOException("APP_TASK_LIST_UNAVAILABLE")
        if (values.size > 256 || values.any { it == null }) { throw IOException("APP_TASK_LIST_LIMIT") }
        var fallbackVisible: Set<Int>? = null
        fun visible(raw: Any): Boolean {
            val field = try { raw.javaClass.getField("isTopVisible") } catch (missing: NoSuchFieldException) { null }
            if (field != null) { return field.getBoolean(raw) }
            val snapshot = fallbackVisible ?: visibleTasksFromStacks().also { fallbackVisible = it }
            return integer(raw, "taskId") in snapshot
        }
        return values.filterNotNull().mapNotNull { raw ->
            val root = component(raw, "realActivity") ?: component(raw, "baseActivity") ?: return@mapNotNull null
            AppTaskPolicy.Task(integer(raw, "taskId"), integer(raw, "stackId"), integer(raw, "displayId"),
                integer(raw, "userId"), root.flattenToString(),
                component(raw, "topActivity")?.flattenToString() ?: "",
                visible(raw),
                (raw.javaClass.getField("baseIntent").get(raw) as? android.content.Intent)?.let {
                    it.action == android.content.Intent.ACTION_MAIN &&
                        it.hasCategory(android.content.Intent.CATEGORY_LAUNCHER)
                } == true)
        }
    }

    private fun visibleTasksFromStacks(): Set<Int> {
        val stacks = invoke(type.getMethod("getAllStackInfos")) as? List<*>
            ?: throw IOException("APP_STACK_VISIBILITY_UNAVAILABLE")
        if (stacks.size > 256 || stacks.any { it == null }) { throw IOException("APP_STACK_VISIBILITY_LIMIT") }
        return stacks.filterNotNull().filter { it.javaClass.getField("visible").getBoolean(it) }.mapNotNull {
            (it.javaClass.getField("taskIds").get(it) as? IntArray)?.lastOrNull()
        }.toSet()
    }

    fun removeProjectionTask(task: AppTaskPolicy.Task, display: Int, before: Set<Int>) {
        require(display > 0 && task.id !in before)
        val matches = projectionTasks().filter { it.id == task.id }
        if (matches.size > 1) { throw IOException("APP_INDEPENDENT_TASK_AMBIGUOUS") }
        val current = matches.singleOrNull() ?: return
        IndependentTaskPolicy(before, task.root, task.user, display).verifyOwnership(current)
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

    /** A current-session, new MAIN/LAUNCHER duplicate only; never an existing primary task. */
    fun removeMirrorIconTask(task: AppTaskPolicy.Task, policy: MirrorTaskPolicy) {
        val matches = projectionTasks().filter { it.id == task.id }
        if (matches.size != 1) { return }
        val current = matches.single()
        if (current.root != task.root || !policy.disposableIconTask(current)) { return }
        if (invoke(remove, current.id) != true) { throw IOException("MIRROR_ICON_TASK_REMOVE_REJECTED") }
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
