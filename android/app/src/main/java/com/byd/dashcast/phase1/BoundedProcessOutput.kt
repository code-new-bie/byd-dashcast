package com.byd.dashcast.phase1

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Drains one owned child concurrently so the timeout also covers a blocked stdout read.
 * @author Codex
 * @date 2026/10/14
 */
internal object BoundedProcessOutput {
    private const val MAX_OUTPUT_BYTES = 16 * 1024
    private const val CLEANUP_TIMEOUT_MILLIS = 2000L

    @JvmStatic
    @Throws(IOException::class)
    fun read(process: Process, timeoutMillis: Long): String = read(process, timeoutMillis, MAX_OUTPUT_BYTES, false)

    @JvmStatic
    @Throws(IOException::class)
    fun read(process: Process, timeoutMillis: Long, maxBytes: Int, strict: Boolean): String {
        if (maxBytes < 1 || maxBytes > 262144) {
            throw IllegalArgumentException("Owned output limit")
        }
        val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
            ThreadFactory { task -> Thread(task, "dashcast-owned-am-output").apply { isDaemon = true } })
        val output = executor.submit(Callable { drain(process.inputStream, maxBytes, strict) })
        var failure: IOException? = null
        try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                throw IOException("SHELL_CAPTURE_REMOTE_APP_START_TIMEOUT")
            }
            return output.get(CLEANUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            failure = IOException("SHELL_CAPTURE_REMOTE_APP_START_INTERRUPTED", error)
            throw failure
        } catch (error: ExecutionException) {
            failure = IOException("SHELL_CAPTURE_REMOTE_APP_OUTPUT_FAILED", error)
            throw failure
        } catch (error: TimeoutException) {
            failure = IOException("SHELL_CAPTURE_REMOTE_APP_OUTPUT_FAILED", error)
            throw failure
        } catch (error: IOException) {
            failure = error
            throw error
        } finally {
            val cleanup = close(process)
            output.cancel(true)
            executor.shutdownNow()
            if (cleanup != null) {
                if (failure != null) {
                    failure.addSuppressed(cleanup)
                } else {
                    throw cleanup
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun drain(input: InputStream, maxBytes: Int, strict: Boolean): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        var overflow = false
        while (true) {
            val length = input.read(buffer)
            if (length == -1) {
                break
            }
            if (length > maxBytes - output.size()) {
                overflow = true
            }
            if (output.size() < maxBytes) {
                output.write(buffer, 0, minOf(length, maxBytes - output.size()))
            }
        }
        if (strict && overflow) {
            throw IOException("OWNED_OUTPUT_LIMIT")
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun close(process: Process): IOException? {
        var failure: IOException? = null
        try {
            if (process.isAlive) {
                process.destroyForcibly()
                if (!process.waitFor(CLEANUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    failure = IOException("CLEANUP_UNCONFIRMED owned am process still alive")
                }
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            failure = IOException("CLEANUP_UNCONFIRMED owned am wait interrupted", error)
        }
        for (stream in arrayOf<AutoCloseable>(process.inputStream, process.errorStream, process.outputStream)) {
            try {
                stream.close()
            } catch (error: Exception) {
                if (failure == null) {
                    failure = IOException("Owned am stream cleanup failed", error)
                } else {
                    failure.addSuppressed(error)
                }
            }
        }
        return failure
    }
}
