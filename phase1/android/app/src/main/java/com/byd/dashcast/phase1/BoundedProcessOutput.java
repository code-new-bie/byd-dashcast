package com.byd.dashcast.phase1;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Drains one owned child concurrently so the timeout also covers a blocked stdout read.
 * @author Codex
 * @date 2026/10/14
 */
final class BoundedProcessOutput {
    private static final int MAX_OUTPUT_BYTES = 16 * 1024;
    private static final int CLEANUP_TIMEOUT_MILLIS = 2000;

    private BoundedProcessOutput() {
    }

    static String read(java.lang.Process process, long timeoutMillis) throws IOException {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), task -> {
                    Thread thread = new Thread(task, "dashcast-owned-am-output");
                    thread.setDaemon(true);
                    return thread;
                });
        Future<String> output = executor.submit(() -> drain(process.getInputStream()));
        IOException failure = null;
        try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                throw new IOException("SHELL_CAPTURE_REMOTE_APP_START_TIMEOUT");
            }
            return output.get(CLEANUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            failure = new IOException("SHELL_CAPTURE_REMOTE_APP_START_INTERRUPTED", error);
            throw failure;
        } catch (ExecutionException | TimeoutException error) {
            failure = new IOException("SHELL_CAPTURE_REMOTE_APP_OUTPUT_FAILED", error);
            throw failure;
        } catch (IOException error) {
            failure = error;
            throw error;
        } finally {
            IOException cleanup = close(process);
            output.cancel(true);
            executor.shutdownNow();
            if (cleanup != null) {
                if (failure != null) {
                    failure.addSuppressed(cleanup);
                } else {
                    throw cleanup;
                }
            }
        }
    }

    private static String drain(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = input.read(buffer)) != -1) {
            if (output.size() < MAX_OUTPUT_BYTES) {
                output.write(buffer, 0, Math.min(length, MAX_OUTPUT_BYTES - output.size()));
            }
        }
        return output.toString(java.nio.charset.StandardCharsets.UTF_8.name());
    }

    private static IOException close(java.lang.Process process) {
        IOException failure = null;
        try {
            if (process.isAlive()) {
                process.destroyForcibly();
                if (!process.waitFor(CLEANUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    failure = new IOException("CLEANUP_UNCONFIRMED owned am process still alive");
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            failure = new IOException("CLEANUP_UNCONFIRMED owned am wait interrupted", error);
        }
        for (AutoCloseable stream : new AutoCloseable[] {process.getInputStream(), process.getErrorStream(),
                process.getOutputStream()}) {
            try {
                stream.close();
            } catch (Exception error) {
                if (failure == null) {
                    failure = new IOException("Owned am stream cleanup failed", error);
                } else {
                    failure.addSuppressed(error);
                }
            }
        }
        return failure;
    }
}
