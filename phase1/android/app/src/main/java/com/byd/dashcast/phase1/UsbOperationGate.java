package com.byd.dashcast.phase1;

import java.util.concurrent.atomic.AtomicBoolean;

/** Process-wide exclusion for UI probes and a Service-owned projection; never a firmware lock.
 * @author Codex
 * @date 2026/10/13
 */
final class UsbOperationGate {
    private static final AtomicBoolean OCCUPIED = new AtomicBoolean();

    private UsbOperationGate() {
    }

    static Lease acquire() {
        return OCCUPIED.compareAndSet(false, true) ? new Lease() : null;
    }

    static final class Lease implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease() {
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                OCCUPIED.set(false);
            }
        }
    }
}
