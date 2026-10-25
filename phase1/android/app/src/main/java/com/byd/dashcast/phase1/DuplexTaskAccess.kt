package com.byd.dashcast.phase1

import android.app.ActivityOptions
import android.os.Bundle
import java.io.IOException
import java.lang.reflect.InvocationTargetException

/** Exact task-id movement only. No package kill, removeTask, resolution override, or user creation. */
internal class DuplexTaskAccess {
    private val access = AndroidTaskAccess()
    private val type = Class.forName("android.app.IActivityTaskManager")
    private val service = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)

    fun user(): Int = access.currentUser()
    fun tasks(): List<AppTaskPolicy.Task> = access.projectionTasks()

    fun host(task: AppTaskPolicy.Task, display: Int, packageName: String, user: Int) {
        val current = requireTask(task.id, packageName, user) ?: throw IOException("APP_DUPLEX_TASK_LOST")
        if (current.display == display) { return }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle()
        val result = call("startActivityFromRecents", arrayOf(Int::class.javaPrimitiveType!!, Bundle::class.java),
            task.id, options) as Int
        if (result < 0) { throw IOException("APP_DUPLEX_HOST_REJECTED status=$result") }
        awaitDisplay(task.id, display, packageName, user, false)
    }

    fun giveBack(task: AppTaskPolicy.Task, display: Int, originalStack: Int, user: Int) {
        val current = tasks().singleOrNull { it.id == task.id } ?: return
        if (current.root != task.root) { throw IOException("CLEANUP_UNCONFIRMED duplex root changed") }
        if (current.user != user || current.display != display) {
            if (current.user == user && current.display == 0) { return }
            throw IOException("CLEANUP_UNCONFIRMED duplex return identity ${SingleAppTaskPolicy.describe(current)}")
        }
        val stacks = call("getAllStackInfos", emptyArray()) as? List<*>
            ?: throw IOException("CLEANUP_UNCONFIRMED stack list")
        val old = stacks.filterNotNull().singleOrNull {
            it.javaClass.getField("stackId").getInt(it) == originalStack &&
                it.javaClass.getField("displayId").getInt(it) == 0
        }
        val verified = tasks().singleOrNull { it.id == task.id } ?: return
        if (verified.user != user || verified.display != display ||
            verified.root != task.root) {
            throw IOException("CLEANUP_UNCONFIRMED duplex return raced ${SingleAppTaskPolicy.describe(verified)}")
        }
        if (old != null) {
            call("moveTaskToStack", arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!), current.id, originalStack, false)
        } else {
            // Only a stack still on THIS owned display is eligible. Never move a primary stack.
            val fresh = tasks().singleOrNull { it.id == current.id } ?: return
            if (fresh.user != user || fresh.display != display || fresh.root != task.root) {
                throw IOException("APP_DUPLEX_RETURN_RACED")
            }
            AppTaskPolicy.requireIsolated(fresh, tasks().filter { it.stack == fresh.stack }.map { it.id }.toIntArray())
            // Return this exact, isolated hosted task behind the user's current main-screen app.
            // moveStackToDisplay would put the returned business stack in front instead.
            call("moveTasksToFullscreenStack", arrayOf(Int::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!), fresh.stack, false)
        }
        awaitDisplay(current.id, 0, current.root.substringBefore('/'), user, true)
    }

    private fun requireTask(id: Int, packageName: String, user: Int): AppTaskPolicy.Task? =
        tasks().singleOrNull { it.id == id }?.also {
            if (it.user != user || it.root.substringBefore('/') != packageName) {
                throw IOException("APP_DUPLEX_IDENTITY_CHANGED ${SingleAppTaskPolicy.describe(it)}")
            }
        }

    private fun awaitDisplay(id: Int, display: Int, packageName: String, user: Int, allowFinished: Boolean) {
        val deadline = System.nanoTime() + 1_500_000_000L
        do {
            val task = requireTask(id, packageName, user)
            if (task == null) {
                if (allowFinished) { return }
                throw IOException("APP_DUPLEX_TASK_LOST task=$id")
            }
            if (task.display == display) { return }
            Thread.sleep(25)
        } while (System.nanoTime() < deadline)
        throw IOException("APP_DUPLEX_MOVE_UNCONFIRMED task=$id wanted_display=$display")
    }

    private fun call(name: String, types: Array<Class<*>>, vararg arguments: Any): Any? = try {
        type.getMethod(name, *types).invoke(service, *arguments)
    } catch (error: InvocationTargetException) {
        throw IOException("APP_DUPLEX_ATM_REJECTED method=$name", error.cause ?: error)
    }
}
