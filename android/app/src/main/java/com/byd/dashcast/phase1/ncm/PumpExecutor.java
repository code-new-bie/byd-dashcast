package com.byd.dashcast.phase1.ncm;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Explicitly bounded workers for a single USB test session.
 * @author Codex
 * @date 2026/10/10
 */
final class PumpExecutor {
    private PumpExecutor() {
    }

    static ThreadPoolExecutor create(String prefix, int workers) {
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(workers), task -> {
                    Thread thread = new Thread(task, prefix + "-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }
}
