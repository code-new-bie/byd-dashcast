package com.byd.dashcast.phase1;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

/**
 * Explicit stationary-only background experiment, not fullscreen projection.
 * The HMI owns the fixed deadline; APK cancellation/disconnect cannot extend it.
 * @author Codex
 * @date 2026/10/20
 */
final class InstrumentBackgroundPoc {
    static final int QUERY = 0;
    static final int START = 1;
    static final int RESTORE = 2;
    static final int HOLD_MILLIS = 5000;
    static final int REPLY_BYTES = 144;
    private static final int OPERATION = 10;
    private static final int REQUEST_BYTES = 16;
    private static final int SCHEMA = 1;
    private static final int FINGERPRINT = 0x42475031;
    private static final int CONTROL_MAGIC = 0x31474244;
    private static final long POLL_NANOS = 100_000_000L;
    private static final long READY_NANOS = 3_000_000_000L;
    private static final long TOTAL_NANOS = 12_000_000_000L;
    private static final int MAX_REQUESTS = 100;
    private final AtomicBoolean cancelled;
    private final NoAuthVideoClient.Logger logger;
    private final LongSupplier nanoTime;
    private final Waiter waiter;
    private final int nonce;
    private int requests;
    private boolean used;

    /** Bounded injectable pause; production waits on the existing probe worker. */
    interface Waiter {
        /** Wait without creating another thread. */
        void pause(long nanos);
    }

    InstrumentBackgroundPoc(AtomicBoolean cancelled, NoAuthVideoClient.Logger logger, int nonce) {
        this(cancelled, logger, nonce, System::nanoTime, LockSupport::parkNanos);
    }

    InstrumentBackgroundPoc(AtomicBoolean cancelled, NoAuthVideoClient.Logger logger, int nonce,
            LongSupplier nanoTime, Waiter waiter) {
        if (nonce <= 0) {
            throw new IllegalArgumentException("BG_POC_INVALID_NONCE");
        }
        this.cancelled = Objects.requireNonNull(cancelled, "cancelled");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.waiter = Objects.requireNonNull(waiter, "waiter");
        this.nonce = nonce;
    }

    /** Caller has validated the offer and exclusively owns this fresh NCM stream. */
    void run(NoAuthVideoTransport channel) throws IOException {
        if (used) {
            throw new IOException("BG_POC_SINGLE_USE_ONLY");
        }
        used = true;
        long began = nanoTime.getAsLong();
        Reply before = awaitReady(channel, began);
        int pid = before.word(10);
        int epoch = before.word(18);
        boolean sent = false;
        boolean restored = false;
        IOException failure = null;
        try {
            checkTime(began);
            sent = true;
            Reply queued = exchange(channel, START, nonce);
            if (queued.error != 0 && queued.error != 9) {
                throw new IOException("BG_POC_START_REJECTED error=" + queued.error);
            }
            logger.log("BG_POC_REQUEST_SENT hold_ms=5000; queued is NOT HMI active/draw evidence");
            awaitRestored(channel, before, pid, epoch, began);
            restored = true;
        } catch (IOException error) {
            failure = error;
            throw error;
        } finally {
            if (sent && !restored) {
                try {
                    channel.beginCleanup();
                    Reply reply = exchange(channel, RESTORE, nonce);
                    logger.log("BG_POC_EARLY_RESTORE_REQUEST error=" + reply.error
                            + "; request is NOT restoration confirmation; HMI also expires independently");
                } catch (IOException cleanupError) {
                    logger.log("BG_POC_RESTORE_UNCONFIRMED " + cleanupError.getMessage()
                            + "; observe instrument, do not retry; HMI deadline is independent of APK");
                    if (failure != null) {
                        failure.addSuppressed(cleanupError);
                    } else {
                        throw cleanupError;
                    }
                }
            }
        }
    }

    private Reply awaitReady(NoAuthVideoTransport channel, long began) throws IOException {
        while (true) {
            checkTime(began);
            Reply reply = exchange(channel, QUERY, 0);
            if (reply.error == 0) {
                if (reply.word(9) == 1 || reply.word(3) != reply.word(8)) {
                    throw new IOException("BG_POC_ALREADY_ACTIVE_OR_PENDING: no start or renewal sent");
                }
                logger.log("BG_POC_HMI_READY pid=" + reply.word(10) + " epoch=" + reply.word(18)
                        + " native_frames=" + reply.unsigned(24));
                return reply;
            }
            if ((reply.error != 2 && reply.error != 3 && reply.error != 9)
                    || nanoTime.getAsLong() - began >= READY_NANOS) {
                throw new IOException("BG_POC_HMI_NOT_READY error=" + reply.error + "; no start sent");
            }
            waiter.pause(POLL_NANOS);
        }
    }

    private void awaitRestored(NoAuthVideoTransport channel, Reply before, int pid, int epoch, long began)
            throws IOException {
        boolean active = false;
        while (true) {
            checkTime(began);
            waiter.pause(POLL_NANOS);
            checkTime(began);
            Reply reply = exchange(channel, QUERY, 0);
            if (reply.error == 9) {
                continue;
            }
            if (reply.error != 0 || reply.word(10) != pid || reply.word(18) != epoch) {
                throw new IOException("BG_POC_HMI_CHANGED_OR_UNAVAILABLE error=" + reply.error);
            }
            boolean ours = reply.word(21) == nonce && reply.word(3) == reply.word(8);
            if (!ours) {
                continue;
            }
            if (reply.word(11) != 0) {
                throw new IOException("BG_POC_HMI_REJECTED reason=" + reply.word(11));
            }
            if (reply.word(9) == 1 && !active) {
                active = true;
                logger.log("BG_POC_HMI_ACTIVE deadline=" + reply.unsigned(14) + ":" + reply.unsigned(15)
                        + "; native foreground path unchanged, NOT a visual/safety certification");
            }
            if (reply.word(9) == 2 && active) {
                long bgDraws = (reply.unsigned(16) - before.unsigned(16)) & 0xffffffffL;
                long rootDraws = (reply.unsigned(17) - before.unsigned(17)) & 0xffffffffL;
                logger.log("BG_POC_NATIVE_RESTORED bg_skips=" + bgDraws + " root_skips=" + rootDraws
                        + " restore_frame=" + reply.unsigned(25) + "; still requires visual confirmation");
                if (bgDraws == 0 || rootDraws == 0 || bgDraws > 100000 || rootDraws > 100000) {
                    throw new IOException("BG_POC_DRAW_NOT_OBSERVED: restored, but background hooks not both observed");
                }
                logger.log("背景控制往返完成：请人工确认背景确实消失约5秒后恢复、车速/挡位/告警全程保留。"
                        + "这不是全屏或道路安全验收。USB模式未改变。");
                return;
            }
        }
    }

    private Reply exchange(NoAuthVideoTransport channel, int operation, int requestNonce) throws IOException {
        if (++requests > MAX_REQUESTS + 1) {
            throw new IOException("BG_POC_REQUEST_LIMIT");
        }
        channel.write(request(operation, requestNonce));
        byte[] header = channel.readExact(NoAuthVideoProtocol.HEADER_BYTES);
        validateHeader(header);
        byte[] body = new byte[REPLY_BYTES];
        for (int offset = 0; offset < body.length;) {
            int count = Math.min(NoAuthVideoProtocol.OFFER_BYTES, body.length - offset);
            byte[] part = channel.readExact(count);
            if (part.length != count) {
                throw new IOException("BG_POC_SHORT_REPLY");
            }
            System.arraycopy(part, 0, body, offset, count);
            offset += count;
        }
        return parseReply(body, operation);
    }

    private void checkTime(long began) throws IOException {
        long elapsed = nanoTime.getAsLong() - began;
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new IOException("BG_POC_CANCELLED");
        }
        if (elapsed < 0 || elapsed >= TOTAL_NANOS || requests >= MAX_REQUESTS) {
            throw new IOException("BG_POC_DEADLINE_OR_LIMIT: restoration unconfirmed; do not retry");
        }
    }

    static byte[] request(int operation, int nonce) throws IOException {
        if (operation < QUERY || operation > RESTORE || (operation == QUERY ? nonce != 0 : nonce <= 0)) {
            throw new IOException("BG_POC_INVALID_REQUEST");
        }
        return ByteBuffer.allocate(NoAuthVideoProtocol.HEADER_BYTES + REQUEST_BYTES).order(ByteOrder.BIG_ENDIAN)
                .putInt(0x44435631).putInt(OPERATION).putInt(REQUEST_BYTES).putInt(0).putInt(0).putInt(0)
                .putInt(SCHEMA).putInt(operation).putInt(nonce).putInt(operation == START ? HOLD_MILLIS : 0).array();
    }

    static void validateHeader(byte[] header) throws IOException {
        if (header == null || header.length != NoAuthVideoProtocol.HEADER_BYTES) {
            throw new IOException("BG_POC_INVALID_HEADER");
        }
        ByteBuffer values = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        if (values.getInt() != 0x44435631 || values.getInt() != (OPERATION | 0x80)
                || values.getInt() != REPLY_BYTES || values.getInt() != 0 || values.getInt() != 0
                || values.getInt() != 0) {
            throw new IOException("BG_POC_MATCHING_FIRMWARE_REQUIRED: reply contract mismatch");
        }
    }

    static Reply parseReply(byte[] body, int operation) throws IOException {
        if (body == null || body.length != REPLY_BYTES) {
            throw new IOException("BG_POC_INVALID_REPLY_SIZE");
        }
        ByteBuffer buffer = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
        if (buffer.getInt() != SCHEMA || buffer.getInt() != FINGERPRINT || buffer.getInt() != operation) {
            throw new IOException("BG_POC_INVALID_REPLY_SCHEMA");
        }
        int error = buffer.getInt();
        if (error < 0 || error > 9) {
            throw new IOException("BG_POC_INVALID_ERROR");
        }
        int[] words = new int[32];
        for (int i = 0; i < words.length; i++) {
            words[i] = buffer.getInt();
        }
        Reply result = new Reply(error, words);
        if (error == 0 && (words[0] != CONTROL_MAGIC || words[1] != SCHEMA || words[2] != 128
                || words[10] <= 0 || words[18] == 0 || words[9] < 0 || words[9] > 2
                || (words[26] & 1) != 0 || (words[3] & 1) != 0 || result.unsigned(13) >= 1_000_000_000L
                || result.unsigned(15) >= 1_000_000_000L)) {
            throw new IOException("BG_POC_INVALID_HMI_SNAPSHOT");
        }
        if (error == 0 && words[9] == 1) {
            long remaining = (result.unsigned(14) - result.unsigned(12)) * 1_000_000_000L
                    + result.unsigned(15) - result.unsigned(13);
            if (remaining < 0 || remaining > HOLD_MILLIS * 1_000_000L) {
                throw new IOException("BG_POC_INVALID_HMI_DEADLINE");
            }
        }
        return result;
    }

    /** Immutable internal wire snapshot; never exposes HMI pointers. */
    static final class Reply {
        final int error;
        private final int[] words;

        Reply(int error, int[] words) {
            this.error = error;
            this.words = words.clone();
        }

        int word(int index) {
            return words[index];
        }

        long unsigned(int index) {
            return Integer.toUnsignedLong(words[index]);
        }
    }
}
