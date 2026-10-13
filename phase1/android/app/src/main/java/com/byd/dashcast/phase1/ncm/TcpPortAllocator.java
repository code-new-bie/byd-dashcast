package com.byd.dashcast.phase1.ncm;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.function.LongSupplier;

/**
 * Process-wide source-port leases survive reconstruction of the USB IP stack.
 * A closed tuple is quarantined for two maximum segment lifetimes (240 seconds).
 * @author Codex
 * @date 2026/10/12
 */
final class TcpPortAllocator {
    static final int FIRST_PORT = 49152;
    static final int PORT_COUNT = 16384;
    static final long QUIET_MILLIS = 240_000L;
    static final TcpPortAllocator PROCESS = new TcpPortAllocator(Net::now,
            new SecureRandom().nextInt(PORT_COUNT));
    private final long[] reusableAt = new long[PORT_COUNT];
    private final LongSupplier clock;
    private int cursor;

    TcpPortAllocator(LongSupplier clock, int initialOffset) {
        if (clock == null || initialOffset < 0 || initialOffset >= PORT_COUNT) {
            throw new IllegalArgumentException("Invalid TCP port allocator configuration");
        }
        this.clock = clock;
        cursor = initialOffset;
        Arrays.fill(reusableAt, Long.MIN_VALUE);
    }

    synchronized int reserve() throws IOException {
        long now = clock.getAsLong();
        for (int attempt = 0; attempt < PORT_COUNT; attempt++) {
            int slot = cursor;
            cursor = (cursor + 1) % PORT_COUNT;
            if (reusableAt[slot] <= now) {
                reusableAt[slot] = Long.MAX_VALUE;
                return FIRST_PORT + slot;
            }
        }
        throw new IOException("TCP source ports busy or quarantined; no tuple reused");
    }

    synchronized void release(int port) {
        int slot = port - FIRST_PORT;
        if (slot < 0 || slot >= PORT_COUNT || reusableAt[slot] != Long.MAX_VALUE) {
            return;
        }
        reusableAt[slot] = clock.getAsLong() + QUIET_MILLIS;
    }
}
