package com.byd.dashcast.phase1;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Paced surface input with bounded EOS drain; independent of Android for regression tests.
 * Output/frame accounting must come from the codec, never from the submitted input count.
 *
 * @author Codex
 * @date 2026/10/10
 */
final class SurfaceEncoderPump {
    private static final long SECOND_NANOS = 1_000_000_000L;
    private static final long EOS_TIMEOUT_NANOS = 5 * SECOND_NANOS;
    private static final long POLL_NANOS = 1_000_000L;

    private SurfaceEncoderPump() {
    }

    /** Returns input frames only after output EOS and exact output accounting. */
    static int run(int seconds, int fps, AtomicBoolean cancelled, Backend backend) throws IOException {
        if (seconds < 1 || seconds > 60 || fps < 1 || fps > 60) {
            throw new IllegalArgumentException("Duration/fps outside bounded validation range");
        }
        Objects.requireNonNull(cancelled);
        Objects.requireNonNull(backend);
        long start = backend.nanoTime();
        int queued = 0;
        boolean inputEos = false;
        long eosStarted = 0;
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                cancelled.set(true);
            }
            // Drain BEFORE swapBuffers: a full codec output queue must not block surface input.
            if (backend.drain()) {
                if (!inputEos || backend.outputFrames() != queued) {
                    throw new IOException("Incomplete encoder output: queued=" + queued
                            + " received=" + backend.outputFrames() + " inputEOS=" + inputEos);
                }
                return queued;
            }
            long now = backend.nanoTime();
            if (!inputEos) {
                if (cancelled.get() || queued >= seconds * fps) {
                    backend.signalEndOfInput();
                    inputEos = true;
                    eosStarted = backend.nanoTime();
                } else if (now - start >= queued * SECOND_NANOS / fps) {
                    backend.render(queued, 1_000_000L + queued * 1_000_000L / fps);
                    queued++;
                }
            }
            now = backend.nanoTime();
            if (inputEos && now - eosStarted > EOS_TIMEOUT_NANOS) {
                throw new IOException("Encoder EOS timeout; incomplete output");
            }
            if (!inputEos && now - start > (seconds + 10L) * SECOND_NANOS) {
                throw new IOException("Encoder input stalled; output incomplete");
            }
            backend.pause(POLL_NANOS);
        }
    }

    /** Only the production adapter touches MediaCodec/EGL. */
    interface Backend {
        long nanoTime();
        boolean drain() throws IOException;
        int outputFrames();
        void render(int frame, long ptsMicros) throws IOException;
        void signalEndOfInput() throws IOException;
        void pause(long nanos);
    }
}
