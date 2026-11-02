package com.byd.dashcast.phase1;

import java.io.IOException;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.concurrent.locks.LockSupport;

/** One non-renewable native-background lease attached to actual full-size video.
 * @author Codex
 * @date 2026/10/20
 */
final class InstrumentCompositionTrial implements CompositionControl {
    private static final long POLL_NANOS = 100_000_000L;
    private static final long MAX_ACTIVE_NANOS = 7_000_000_000L;
    private final NoAuthVideoClient.Logger log;
    private final LongSupplier clock;
    private final int nonce;
    private final Runnable stop;
    private InstrumentBackgroundPoc.Reply before;
    private boolean sent;
    private boolean active;
    private boolean restored;
    private long began;
    private long lastPoll;

    InstrumentCompositionTrial(NoAuthVideoClient.Logger log, LongSupplier clock, int nonce, Runnable stop) {
        if (nonce <= 0) {
            throw new IllegalArgumentException("COMPOSITION_NONCE");
        }
        this.log = Objects.requireNonNull(log);
        this.clock = Objects.requireNonNull(clock);
        this.nonce = nonce;
        this.stop = Objects.requireNonNull(stop);
    }

    @Override
    public void prepare(NoAuthVideoTransport channel) throws IOException {
        before = exchange(channel, InstrumentBackgroundPoc.QUERY, 0);
        requireSame(before);
        if (before.word(9) == 1 || before.word(3) != before.word(8)) {
            throw new IOException("COMPOSITION_BACKGROUND_ALREADY_ACTIVE_OR_PENDING");
        }
        log.log("COMPOSITION_PREPARED native_pid=" + before.word(10) + " epoch=" + before.word(18)
                + "; fixed full-size underlay, not a physical composition certification");
    }

    @Override
    public void activate(NoAuthVideoTransport channel) throws IOException {
        if (sent || before == null) {
            throw new IOException("COMPOSITION_START_REPLAY_OR_NOT_PREPARED");
        }
        sent = true;
        began = clock.getAsLong();
        InstrumentBackgroundPoc.Reply reply = exchange(channel, InstrumentBackgroundPoc.START, nonce);
        if (reply.error != 0 && reply.error != 9) {
            throw new IOException("COMPOSITION_START_REJECTED error=" + reply.error);
        }
        log.log("COMPOSITION_VIDEO_SUBMITTED_BACKGROUND_REQUESTED hold_ms=5000; no renewal");
    }

    @Override
    public void tick(NoAuthVideoTransport channel) throws IOException {
        if (!sent || restored) {
            return;
        }
        long now = clock.getAsLong();
        if (now - began < 0 || now - began > MAX_ACTIVE_NANOS) {
            throw new IOException("COMPOSITION_RESTORE_DEADLINE");
        }
        if (now - lastPoll < POLL_NANOS) {
            return;
        }
        lastPoll = now;
        InstrumentBackgroundPoc.Reply reply = exchange(channel, InstrumentBackgroundPoc.QUERY, 0);
        if (reply.error == 9) {
            return;
        }
        requireSame(reply);
        if (reply.word(3) != reply.word(8) || reply.word(21) != nonce) {
            return;
        }
        if (reply.word(11) != 0) {
            throw new IOException("COMPOSITION_HMI_REJECTED reason=" + reply.word(11));
        }
        if (reply.word(9) == 1 && !active) {
            active = true;
            log.log("COMPOSITION_HMI_ACTIVE; observe video underneath native speed/gear/warnings");
        }
        if (reply.word(9) == 2) {
            if (!active || delta(reply, 16) == 0 || delta(reply, 17) == 0) {
                throw new IOException("COMPOSITION_DRAW_NOT_CONFIRMED");
            }
            restored = true;
            log.log("COMPOSITION_NATIVE_RESTORED bg_skips=" + delta(reply, 16)
                    + " root_skips=" + delta(reply, 17) + "; visual confirmation still required");
            stop.run();
        }
    }

    @Override
    public void restore(NoAuthVideoTransport channel) throws IOException {
        if (!sent || restored) {
            return;
        }
        exchange(channel, InstrumentBackgroundPoc.RESTORE, nonce);
        for (int attempt = 0; attempt < 60; attempt++) {
            InstrumentBackgroundPoc.Reply reply = exchange(channel, InstrumentBackgroundPoc.QUERY, 0);
            if (reply.error == 0) {
                requireSame(reply);
                if (reply.word(9) == 2 && reply.word(3) == reply.word(8) && reply.word(21) == nonce) {
                    restored = true;
                    log.log("COMPOSITION_EARLY_RESTORE_CONFIRMED; then native media STOP");
                    return;
                }
            } else if (reply.error != 9) {
                throw new IOException("COMPOSITION_RESTORE_UNAVAILABLE error=" + reply.error);
            }
            LockSupport.parkNanos(25_000_000L);
        }
        throw new IOException("COMPOSITION_RESTORE_UNCONFIRMED; native five-second expiry remains independent");
    }

    private long delta(InstrumentBackgroundPoc.Reply reply, int index) {
        return (reply.unsigned(index) - before.unsigned(index)) & 0xffffffffL;
    }

    private void requireSame(InstrumentBackgroundPoc.Reply reply) throws IOException {
        if (reply.error != 0 || before != null && (reply.word(10) != before.word(10)
                || reply.word(18) != before.word(18))) {
            throw new IOException("COMPOSITION_HMI_CHANGED_OR_NOT_READY error=" + reply.error);
        }
    }

    private static InstrumentBackgroundPoc.Reply exchange(NoAuthVideoTransport channel, int operation, int nonce)
            throws IOException {
        return exchangePacket(channel, operation, InstrumentBackgroundPoc.request(operation, nonce));
    }

    static InstrumentBackgroundPoc.Reply exchangePacket(NoAuthVideoTransport channel, int operation, byte[] request)
            throws IOException {
        channel.write(request);
        InstrumentBackgroundPoc.validateHeader(channel.readExact(NoAuthVideoProtocol.HEADER_BYTES));
        byte[] bytes = new byte[InstrumentBackgroundPoc.REPLY_BYTES];
        for (int offset = 0; offset < bytes.length;) {
            int count = Math.min(NoAuthVideoProtocol.OFFER_BYTES, bytes.length - offset);
            byte[] part = channel.readExact(count);
            if (part.length != count) {
                throw new IOException("COMPOSITION_SHORT_REPLY");
            }
            System.arraycopy(part, 0, bytes, offset, count);
            offset += count;
        }
        return InstrumentBackgroundPoc.parseReply(bytes, operation);
    }
}
