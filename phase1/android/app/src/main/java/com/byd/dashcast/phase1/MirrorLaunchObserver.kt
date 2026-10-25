package com.byd.dashcast.phase1

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.io.IOException

/** Read-only selected-package launch signal. Does not register/replace a global activity controller.
 * logcat filters package/tag before the pipe; raw lines never enter product logs or exported diagnostics.
 * @author Codex
 * @date 2026/10/25
 */
internal class MirrorLaunchObserver(packageName: String, user: Int, appUid: Int, targetUid: Int) : AutoCloseable {
    private val policy = MirrorLaunchPolicy(packageName, user, appUid, targetUid, System.nanoTime())
    private val requested = AtomicBoolean()
    private val closing = AtomicBoolean()
    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { work -> Thread(work, "dashcast-selected-launch").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())
    private val process = ProcessBuilder("/system/bin/logcat", "--pid", systemServerPid(),
        "-b", "system", "-v", "monotonic", "-T", "1",
        "-e", "cmp=" + packageName.replace(".", "\\.") + "/", "-s", "ActivityTaskManager:I", "*:S")
        .redirectErrorStream(true).start()

    init {
        executor.execute {
            try {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8), 8192).use { reader ->
                    while (!closing.get()) {
                        val line = reader.readLine() ?: break
                        if (policy.accept(line, System.nanoTime())) { requested.set(true) }
                    }
                }
            } catch (error: IOException) {
                if (!closing.get()) { System.err.println("SHELL_MIRROR_LAUNCH_SIGNAL_UNAVAILABLE") }
            }
        }
    }

    fun take(): Boolean = requested.getAndSet(false)

    private fun systemServerPid(): String {
        val lookup = ProcessBuilder("/system/bin/pidof", "system_server").redirectErrorStream(true).start()
        val result = BoundedProcessOutput.read(lookup, 1000).trim()
        if (!Regex("[1-9][0-9]{0,9}").matches(result) || lookup.exitValue() != 0) {
            throw IOException("MIRROR_SYSTEM_LAUNCH_SOURCE_UNAVAILABLE")
        }
        return result
    }

    override fun close() {
        if (!closing.compareAndSet(false, true)) { return }
        var failure: Exception? = null
        try {
            process.destroy()
            if (!process.waitFor(800, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                if (!process.waitFor(800, TimeUnit.MILLISECONDS)) { throw IOException("MIRROR_LAUNCH_PROCESS_CLEANUP") }
            }
        } catch (error: Exception) { failure = error }
        fun release(action: () -> Unit) {
            try { action() } catch (error: Exception) {
                if (failure == null) { failure = error } else { failure!!.addSuppressed(error) }
            }
        }
        release { process.inputStream.close() }
        release { process.errorStream.close() }
        release { process.outputStream.close() }
        executor.shutdownNow()
        release { if (!executor.awaitTermination(1200, TimeUnit.MILLISECONDS)) {
            throw IOException("MIRROR_LAUNCH_READER_CLEANUP")
        } }
        failure?.let { throw it }
    }
}
