package com.byd.dashcast.phase1

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Keeps the owned Android display active while a separate SF token follows its layer stack.
 * Every dummy output buffer is acquired/closed; it is not a second encoder or video recording.
 * @author Codex
 * @date 2026/10/22
 */
internal class RemoteDisplaySink(width: Int, height: Int) : AutoCloseable {
    private val thread = HandlerThread("dashcast-remote-sink")
    private var reader: ImageReader? = null
    private val closing = AtomicBoolean()
    private val failure = AtomicReference<IOException?>()

    init {
        try {
            thread.start()
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also { source ->
                source.setOnImageAvailableListener({ available ->
                    if (!closing.get()) {
                        try { available.acquireLatestImage()?.close() }
                        catch (error: IllegalStateException) {
                            if (!closing.get()) { failure.set(IOException("APP_REMOTE_SINK_FAILED", error)) }
                        }
                    }
                }, Handler(thread.looper))
            }
        } catch (error: Exception) {
            try { close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    fun surface(): Surface = checkNotNull(reader).surface
    fun checkHealthy() { failure.get()?.let { throw it } }

    override fun close() {
        if (!closing.compareAndSet(false, true)) { return }
        val owned = reader
        reader = null
        var error: Exception? = null
        try { owned?.setOnImageAvailableListener(null, null) }
        catch (problem: Exception) { error = problem }
        try { thread.quitSafely() }
        catch (problem: Exception) { error = collect(error, problem) }
        try {
            thread.join(1500)
        } catch (problem: InterruptedException) {
            Thread.currentThread().interrupt()
            error = collect(error, problem)
        } finally {
            try { owned?.close() }
            catch (problem: Exception) { error = collect(error, problem) }
        }
        if (thread.isAlive) { error = collect(error, IOException("remote sink worker still alive")) }
        error?.let { throw IOException("CLEANUP_UNCONFIRMED owned remote sink cleanup", it) }
    }

    private fun collect(current: Exception?, next: Exception): Exception {
        if (current == null) { return next }
        current.addSuppressed(next)
        return current
    }
}
