package com.byd.dashcast.phase1

import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.view.Surface
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/** Owns the display/waiting page, never terminates or force-stops foreign application tasks. */
internal class NamedDisplaySession(surface: Surface, width: Int, height: Int) : RemoteCaptureSession {
    private var display: VirtualDisplay? = null
    private val context = RemoteDisplaySession.shellContext()
    private val access = AndroidTaskAccess()
    private val user = access.currentUser()
    private val before = access.projectionTasks().map { it.id }.toSet()
    private var displayId = -1
    private var observer: Thread? = null
    private val child = AtomicReference<java.lang.Process?>()
    @Volatile private var closed = false
    @Volatile private var failure: IOException? = null
    @Volatile private var observedAt = 0L
    @Volatile private var owners: Set<Int> = emptySet()
    private var admitted: Int? = null

    init {
        try {
            NamedDisplayContract.requireAvailable(context)
            display = RemoteDisplaySession.createVirtualDisplay(context, surface, width, height,
                160, 1 or 2 or 8, NamedDisplayContract.NAME)
            displayId = display?.display?.displayId ?: -1
            if (displayId <= 0) { throw IOException("NAMED_DISPLAY_MISSING") }
            RemoteDisplaySession.launch(listOf("/system/bin/am", "start-activity", "-W", "--display",
                displayId.toString(), "--user", user.toString(), "-n",
                BuildConfig.APPLICATION_ID + "/" + NamedWaitingActivity::class.java.name))
            val ownUid = context.packageManager.getApplicationInfo(BuildConfig.APPLICATION_ID, 0).uid
            observer = Thread({ observe(ownUid) }, "dashcast-named-windows").apply { isDaemon = true; start() }
            System.err.println("SHELL_NAMED_DISPLAY_READY name=${NamedDisplayContract.NAME} display=$displayId; one_application_at_a_time=true")
        } catch (error: Exception) {
            try { close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    private fun observe(ownUid: Int) {
        try {
            while (!closed) {
                if (access.currentUser() != user) { throw IOException("NAMED_ACTIVE_USER_CHANGED") }
                val process = ProcessBuilder("/system/bin/dumpsys", "-t", "2", "window", "windows").redirectErrorStream(true).start()
                child.set(process)
                val snapshot = try { BoundedProcessOutput.read(process, 2500, NamedWindowPolicy.MAX_BYTES, true) }
                    finally { child.compareAndSet(process, null) }
                if (process.exitValue() != 0) { throw IOException("NAMED_WINDOW_QUERY_REJECTED") }
                owners = NamedWindowPolicy.owners(snapshot, displayId, ownUid, user)
                observedAt = System.nanoTime()
                Thread.sleep(500)
            }
        } catch (error: Exception) {
            if (error is InterruptedException) { Thread.currentThread().interrupt() }
            if (!closed) { failure = IOException("NAMED_WINDOW_OBSERVER_FAILED", error) }
        }
    }

    override fun update(codec: MediaCodec) {
        failure?.let { throw it }
        val snapshot = owners
        if (snapshot.size > 1 || admitted != null && snapshot.any { it != admitted }) {
            throw IOException("NAMED_APPLICATION_CHANGED: 请先停止当前接入，再投下一个应用")
        }
        snapshot.singleOrNull()?.let { if (admitted == null) { admitted = it } }
        if (observedAt != 0L && System.nanoTime() - observedAt > 5_000_000_000L) {
            throw IOException("NAMED_WINDOW_OBSERVATION_STALE")
        }
    }

    override fun renderAllowed(): Boolean = observedAt != 0L && failure == null
    override fun renderSourceEpochNanos(): Long = 0L
    override fun acceptFrame(ptsMicros: Long, key: Boolean): Boolean = renderAllowed()

    override fun close() {
        if (closed) { return }
        closed = true
        child.get()?.destroyForcibly()
        observer?.interrupt()
        var problem: Exception? = null
        fun release(action: () -> Unit) {
            try { action() } catch (error: Exception) {
                if (problem == null) { problem = error } else { problem!!.addSuppressed(error) }
            }
        }
        release { observer?.join(3000); if (observer?.isAlive == true) { throw IOException("NAMED_OBSERVER_STILL_ALIVE") } }
        release {
            for (task in access.projectionTasks().filter { it.id !in before && it.display == displayId &&
                it.user == user && it.root == BuildConfig.APPLICATION_ID + "/" + NamedWaitingActivity::class.java.name }) {
                access.removeProjectionTask(task, displayId, before)
            }
        }
        // No DESTROY_CONTENT_ON_REMOVAL: application-owned activities may return to main.
        release { display?.release() }; display = null
        if (problem != null) { throw IOException("CLEANUP_UNCONFIRMED named display", problem) }
    }
}
