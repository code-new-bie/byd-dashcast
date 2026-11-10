package com.byd.dashcast.phase1

import java.io.IOException
import java.io.InputStream
import java.util.Objects
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Only this capture's stdin can stop it. EOF also stops an orphaned owner session.
 * @author Codex
 * @date 2026/10/13
 */
internal class CaptureStopControl(input: InputStream?) : AutoCloseable {
    private val stopped = AtomicBoolean()
    private val input: InputStream = Objects.requireNonNull(input)!!
    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        ThreadFactory { task -> Thread(task, "dashcast-owned-capture-control").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())

    @Volatile
    private var failure: IOException? = null

    fun start() {
        executor.execute {
            try {
                readControl(input)
                stopped.set(true)
            } catch (error: IOException) {
                failure = error
                stopped.set(true)
            }
        }
    }

    @Throws(IOException::class)
    fun stopped(): Boolean {
        failure?.let { throw it }
        return stopped.get()
    }

    @Throws(IOException::class)
    override fun close() {
        stopped.set(true)
        executor.shutdownNow()
        input.close()
    }

    companion object {
        @JvmStatic
        @Throws(IOException::class)
        fun readControl(input: InputStream) {
            for (value in "STOP\n".toByteArray(Charsets.US_ASCII)) {
                val next = input.read()
                if (next == -1) {
                    return
                }
                if (next != value.toInt()) {
                    throw IOException("CAPTURE_INVALID_STDIN_CONTROL")
                }
            }
        }
    }
}
