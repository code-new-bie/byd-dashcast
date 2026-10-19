package com.byd.dashcast.phase1;

import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.concurrent.locks.LockSupport;

/**
 * Single-use DCV1 client. STOP stays on the same ordered USB connection.
 * No credentials and no Wi-Fi/mobile/legacy-protocol fallback.
 * @author Codex
 * @date 2026/10/11
 */
final class NoAuthVideoClient implements Closeable {
    interface Connector {
        NoAuthVideoTransport connect(int port, AtomicBoolean cancelled) throws IOException;
    }

    interface Logger {
        void log(String message);
    }

    private final Connector connector;
    private final AtomicBoolean cancelled;
    private final Logger logger;
    private final LongSupplier nanoTime;
    private final boolean continuous;
    private NoAuthVideoTransport video;
    private NoAuthVideoProtocol.Reply evidence;
    private boolean negotiated;
    private boolean started;
    private boolean closed;
    private boolean terminalCleanup;
    private boolean rejected;
    private int acknowledged;
    private int heartbeats;
    private long lastHeartbeat;
    private long firstPts = -1;
    private long previousPtsMillis = -1;
    private long payloadBytes;
    private boolean cleanupConfirmed = true;
    private long maxAckNanos;
    private long totalAckNanos;
    private InstrumentDisplayProbe displayProbe;

    NoAuthVideoClient(Connector connector, AtomicBoolean cancelled, Logger logger) {
        this(connector, cancelled, logger, System::nanoTime);
    }

    NoAuthVideoClient(Connector connector, AtomicBoolean cancelled, Logger logger, LongSupplier nanoTime) {
        this(connector, cancelled, logger, nanoTime, false);
    }

    NoAuthVideoClient(Connector connector, AtomicBoolean cancelled, Logger logger, boolean continuous) {
        this(connector, cancelled, logger, System::nanoTime, continuous);
    }

    NoAuthVideoClient(Connector connector, AtomicBoolean cancelled, Logger logger, LongSupplier nanoTime,
            boolean continuous) {
        this.connector = Objects.requireNonNull(connector, "connector");
        this.cancelled = Objects.requireNonNull(cancelled, "cancelled");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.continuous = continuous;
    }

    /** Explicit F1 diagnostic only; normal projection never sends the new operation. */
    void enableDisplayReadback() throws IOException {
        if (video != null || closed || continuous) {
            throw new IOException("F1_REQUIRES_FRESH_SHORT_DIAGNOSTIC");
        }
        displayProbe = new InstrumentDisplayProbe(cancelled, logger, nanoTime);
    }

    void begin() throws IOException {
        checkCancellation();
        if (video != null || closed) {
            throw new IOException("DCV1_SESSION_CANNOT_BE_REUSED");
        }
        video = connector.connect(NoAuthVideoProtocol.PORT, cancelled);
        try {
            byte[] header = video.readExact(NoAuthVideoProtocol.HEADER_BYTES);
            byte[] offer = video.readExact(NoAuthVideoProtocol.OFFER_BYTES);
            NoAuthVideoProtocol.validateOffer(header, offer);
            if (displayProbe != null) {
                displayProbe.negotiate(video);
            }
            exchange(NoAuthVideoProtocol.hello(continuous), NoAuthVideoProtocol.HELLO, 0, 0);
            negotiated = true;
            cleanupConfirmed = false;
            lastHeartbeat = nanoTime.getAsLong();
            NoAuthVideoProtocol.Reply reply = exchange(NoAuthVideoProtocol.start(), NoAuthVideoProtocol.START, 0, 0);
            if ((reply.flags & 1) == 0 || reply.submissions != 0) {
                throw new IOException("DCV1_MEDIA_CONTEXT_NOT_READY");
            }
            started = true;
            logger.log("RECEIVER_READY DCV1/" + (continuous ? 2 : 1)
                    + " no video authorization; " + NoAuthVideoProtocol.WIDTH + "x"
                    + NoAuthVideoProtocol.HEIGHT + "/" + NoAuthVideoProtocol.FPS
                    + "fps; NOT picture confirmation");
        } catch (IOException error) {
            try {
                close();
            } catch (IOException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
    }

    void frame(byte[] annexB, long ptsMicros) throws IOException {
        checkCancellation();
        if (!started || closed || rejected || ptsMicros < 0) {
            throw new IOException("DCV1_STREAM_NOT_READY_OR_INVALID_PTS");
        }
        if (firstPts < 0) {
            firstPts = ptsMicros;
        }
        long relativeMillis = (ptsMicros - firstPts) / 1000;
        if (ptsMicros < firstPts || relativeMillis < previousPtsMillis
                || (previousPtsMillis >= 0 && relativeMillis - previousPtsMillis > 2000)) {
            throw new IOException("DCV1_NON_MONOTONIC_OR_GAPPED_PTS");
        }
        byte[] packet = NoAuthVideoProtocol.frame(annexB, acknowledged + 1, relativeMillis, continuous);
        if (nanoTime.getAsLong() - lastHeartbeat >= 1_000_000_000L) {
            heartbeat();
        }
        long ackStart = nanoTime.getAsLong();
        evidence = exchange(packet, NoAuthVideoProtocol.FRAME, acknowledged + 1, relativeMillis);
        long ackNanos = Math.max(0, nanoTime.getAsLong() - ackStart);
        maxAckNanos = Math.max(maxAckNanos, ackNanos);
        totalAckNanos += ackNanos;
        acknowledged++;
        previousPtsMillis = relativeMillis;
        payloadBytes += annexB.length;
        if (displayProbe != null) {
            displayProbe.step(video);
        }
        if (acknowledged == 1 || acknowledged % (continuous ? 150 : 30) == 0) {
            logger.log(evidence.summary());
            if (continuous) {
                logger.log(statistics());
            }
        }
    }

    private void heartbeat() throws IOException {
        evidence = exchange(NoAuthVideoProtocol.heartbeat(++heartbeats, continuous), NoAuthVideoProtocol.HEARTBEAT, 0, 0);
        if (evidence.submissions != acknowledged) {
            throw new IOException("DCV1_HEARTBEAT_SUBMISSION_MISMATCH");
        }
        lastHeartbeat = nanoTime.getAsLong();
    }

    void requireWindowReport() throws IOException {
        checkCancellation();
        if (!started || closed || rejected || acknowledged < 2) {
            throw new IOException("WINDOW_UNCONFIRMED: fewer than two submitted frames");
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            heartbeat();
            logger.log(evidence.summary());
            if (evidence.windowReported()) {
                logger.log("RECEIVER_WINDOW_REPORTED: Screen visible property, NOT observed motion/decoded-frame count");
                return;
            }
            if (attempt < 2) {
                LockSupport.parkNanos(100_000_000L);
                checkCancellation();
            }
        }
        throw new IOException("WINDOW_UNCONFIRMED: receiver did not report its visible video window");
    }

    private NoAuthVideoProtocol.Reply exchange(byte[] request, int type, int sequence, long pts) throws IOException {
        video.write(request);
        byte[] header = video.readExact(NoAuthVideoProtocol.HEADER_BYTES);
        byte[] payload = video.readExact(NoAuthVideoProtocol.ACK_BYTES);
        try {
            return NoAuthVideoProtocol.reply(header, payload, type, sequence, pts, continuous);
        } catch (NoAuthVideoProtocol.Rejected error) {
            rejected = true;
            terminalCleanup = error.terminalCleanupReported();
            logger.log(error.getMessage());
            throw error;
        }
    }

    private void checkCancellation() throws IOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new IOException("Video verification cancelled");
        }
    }

    String statistics() {
        return "DCV1 acknowledged=" + acknowledged + " H264 bytes=" + payloadBytes
                + " source_duration_ms=" + Math.max(0, previousPtsMillis)
                + " max_ack_ms=" + maxAckNanos / 1_000_000L
                + " mean_ack_ms=" + (acknowledged == 0 ? 0 : totalAckNanos / acknowledged / 1_000_000L)
                + "; ACK timing is NOT end-to-end display latency";
    }

    boolean cleanupConfirmed() {
        return cleanupConfirmed;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        if (displayProbe != null) {
            displayProbe.end();
        }
        if (video == null) {
            return;
        }
        boolean restoreInterrupt = Thread.interrupted();
        try {
            video.beginCleanup();
            if (terminalCleanup) {
                cleanupConfirmed = true;
                logger.log("ERROR_CLEANUP_REPORTED: negative FRAME ACK followed native cleanup; no STOP on closed peer");
            } else if (negotiated) {
                NoAuthVideoProtocol.Reply reply = exchange(NoAuthVideoProtocol.stop(), NoAuthVideoProtocol.STOP, 0, 0);
                if (!reply.cleanupReported()) {
                    throw new IOException("DCV1 receiver did not report media/window cleanup");
                }
                cleanupConfirmed = true;
                logger.log("STOP_CLEANUP_REPORTED: native media/window cleanup returned 0; manually check window exit");
            }
        } catch (IOException error) {
            logger.log("STOP_UNCONFIRMED " + error.getMessage() + "; do not retry before checking instrument/window");
            throw error;
        } finally {
            video.close();
            video = null;
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
            logger.log(statistics() + "; USB role NOT automatically changed");
        }
    }
}
