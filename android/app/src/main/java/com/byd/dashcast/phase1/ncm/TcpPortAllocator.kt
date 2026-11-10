package com.byd.dashcast.phase1.ncm

import com.byd.dashcast.phase1.DashcastPorts
import java.io.IOException
import java.security.SecureRandom
import java.util.function.LongSupplier

/**
 * Process-wide source-port leases survive reconstruction of the USB IP stack.
 * A closed tuple is quarantined for two maximum segment lifetimes (240 seconds).
 * @author Codex
 * @date 2026/10/12
 */
internal class TcpPortAllocator(clock: LongSupplier?, initialOffset: Int) {
    private val clock: LongSupplier
    private val reusableAt = LongArray(PORT_COUNT) { Long.MIN_VALUE }
    private var cursor: Int

    init {
        if (clock == null || initialOffset < 0 || initialOffset >= PORT_COUNT) {
            throw IllegalArgumentException("Invalid TCP port allocator configuration")
        }
        this.clock = clock
        cursor = initialOffset
    }

    @Synchronized
    @Throws(IOException::class)
    fun reserve(): Int {
        val now = clock.asLong
        repeat(PORT_COUNT) {
            val slot = cursor
            cursor = (cursor + 1) % PORT_COUNT
            if (reusableAt[slot] <= now) {
                reusableAt[slot] = Long.MAX_VALUE
                return FIRST_PORT + slot
            }
        }
        throw IOException("TCP source ports busy or quarantined; no tuple reused")
    }

    @Synchronized
    fun release(port: Int) {
        val slot = port - FIRST_PORT
        if (slot < 0 || slot >= PORT_COUNT || reusableAt[slot] != Long.MAX_VALUE) {
            return
        }
        reusableAt[slot] = clock.asLong + QUIET_MILLIS
    }

    companion object {
        const val FIRST_PORT = DashcastPorts.SOURCE_FIRST
        const val PORT_COUNT = DashcastPorts.SOURCE_COUNT
        const val QUIET_MILLIS = 240_000L

        @JvmField
        val PROCESS = TcpPortAllocator(LongSupplier { Net.now() }, SecureRandom().nextInt(PORT_COUNT))
    }
}
