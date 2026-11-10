package com.byd.dashcast.phase1.ncm

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Explicitly bounded workers for a single USB test session.
 * @author Codex
 * @date 2026/10/10
 */
internal object PumpExecutor {
    @JvmStatic
    fun create(prefix: String, workers: Int): ThreadPoolExecutor {
        val sequence = AtomicInteger()
        return ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(workers), ThreadFactory { task ->
                Thread(task, prefix + "-" + sequence.incrementAndGet()).apply { isDaemon = true }
            }, ThreadPoolExecutor.AbortPolicy())
    }
}
