package com.byd.dashcast.phase1;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Only this capture's stdin can stop it. EOF also stops an orphaned owner session.
 * @author Codex
 * @date 2026/10/13
 */
final class CaptureStopControl implements AutoCloseable {
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final InputStream input;
    private final ThreadPoolExecutor executor;
    private volatile IOException failure;

    CaptureStopControl(InputStream input) {
        this.input = Objects.requireNonNull(input);
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), task -> {
            Thread thread = new Thread(task, "dashcast-owned-capture-control");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    void start() {
        executor.execute(() -> {
            try {
                readControl(input);
                stopped.set(true);
            } catch (IOException error) {
                failure = error;
                stopped.set(true);
            }
        });
    }

    static void readControl(InputStream input) throws IOException {
        byte[] expected = {'S', 'T', 'O', 'P', '\n'};
        for (byte value : expected) {
            int next = input.read();
            if (next == -1) {
                return;
            }
            if (next != value) {
                throw new IOException("CAPTURE_INVALID_STDIN_CONTROL");
            }
        }
    }

    boolean stopped() throws IOException {
        if (failure != null) {
            throw failure;
        }
        return stopped.get();
    }

    @Override
    public void close() throws IOException {
        stopped.set(true);
        executor.shutdownNow();
        input.close();
    }
}
