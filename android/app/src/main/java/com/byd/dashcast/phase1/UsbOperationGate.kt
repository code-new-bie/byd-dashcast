package com.byd.dashcast.phase1

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide exclusion for UI probes and a Service-owned projection; never a firmware lock.
 * @author Codex
 * @date 2026/10/13
 */
internal object UsbOperationGate {
    private val occupied = AtomicBoolean()

    @JvmStatic
    fun acquire(): Lease? = if (occupied.compareAndSet(false, true)) Lease() else null

    class Lease internal constructor() : AutoCloseable {
        private val closed = AtomicBoolean()

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                occupied.set(false)
            }
        }
    }
}
