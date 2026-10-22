package com.byd.dashcast.phase1

import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Reads only OEM launcher-intent events for the one adopted task. No persistent log capture.
 * Does not hook the launcher, replace the native icon, inject an intent, or run a shell string.
 * @author Codex
 * @date 2026/10/22
 */
internal class NativeAppLaunchWatcher(private val launcherComponent: String) : AutoCloseable {
    private val packageName = launcherComponent.substringBefore('/')
    private data class Identity(val task: Int, val user: Int, val began: Long)
    private val identity = AtomicReference<Identity?>()
    private val requested = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val failure = AtomicReference<IOException?>()
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1), { work ->
        Thread(work, "dashcast-native-launch-watch").also { it.isDaemon = true }
    })
    private val process: Process

    init {
        var child: Process? = null
        try {
            process = ProcessBuilder("/system/bin/logcat", "-b", "events", "-v", "epoch", "-T", "1",
                "am_new_intent:I", "am_create_activity:I", "*:S").redirectErrorStream(true).start()
            child = process
            executor.execute { readEvents() }
        } catch (error: Exception) {
            executor.shutdownNow()
            child?.let { owned ->
                try {
                    owned.destroyForcibly()
                    if (!owned.waitFor(1500, TimeUnit.MILLISECONDS)) {
                        error.addSuppressed(IOException("CLEANUP_UNCONFIRMED native-icon child start failed"))
                    }
                } catch (cleanup: Exception) {
                    if (cleanup is InterruptedException) { Thread.currentThread().interrupt() }
                    error.addSuppressed(cleanup)
                }
            }
            throw IOException("APP_NATIVE_ICON_WATCH_UNAVAILABLE", error)
        }
    }

    fun arm(task: Int, user: Int) {
        requested.set(false)
        identity.set(Identity(task, user, System.currentTimeMillis()))
    }

    fun takeRequest(): Boolean {
        failure.get()?.let { throw it }
        return requested.getAndSet(false)
    }

    private fun readEvents() {
        try {
            InputStreamReader(process.inputStream, Charsets.UTF_8).use { input ->
                val line = StringBuilder()
                var overflow = false
                while (!closed.get()) {
                    val value = input.read()
                    if (value == -1) { break }
                    if (value == '\n'.code) {
                        val target = identity.get()
                        if (!overflow && target != null && NativeAppLaunchEvent.matches(line.toString(),
                                packageName, target.task, target.user, target.began, launcherComponent)) {
                            requested.set(true)
                        }
                        line.setLength(0)
                        overflow = false
                    } else if (line.length < 4096) {
                        line.append(value.toChar())
                    } else { overflow = true }
                }
            }
            if (!closed.get()) { failure.set(IOException("APP_NATIVE_ICON_WATCH_EXITED")) }
        } catch (error: IOException) {
            if (!closed.get()) { failure.set(IOException("APP_NATIVE_ICON_WATCH_FAILED", error)) }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) { return }
        identity.set(null)
        process.destroyForcibly() // ONLY this owned logcat child; never a navigation/app process.
        executor.shutdownNow()
        try {
            if (!process.waitFor(1500, TimeUnit.MILLISECONDS) || !executor.awaitTermination(1500, TimeUnit.MILLISECONDS)) {
                throw IOException("CLEANUP_UNCONFIRMED owned native-icon watcher still alive")
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("CLEANUP_UNCONFIRMED native-icon watcher interrupted", error)
        } finally {
            process.inputStream.close()
            process.errorStream.close()
            process.outputStream.close()
        }
    }
}
