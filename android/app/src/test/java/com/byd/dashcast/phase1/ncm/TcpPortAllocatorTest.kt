package com.byd.dashcast.phase1.ncm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import java.util.function.LongSupplier

/** Deterministic port lifetime tests; no Android, vehicle, or wall-clock sleeps. */
class TcpPortAllocatorTest {
    @Test fun releasedPortRemainsQuarantinedUntilFullQuietPeriod() {
        val now = AtomicLong(100)
        val pool = TcpPortAllocator(LongSupplier { now.get() }, 0)
        val first = pool.reserve()
        pool.release(first)
        for (index in 1 until TcpPortAllocator.PORT_COUNT) {
            assertNotEquals(first.toLong(), pool.reserve().toLong())
        }
        assertThrows(IOException::class.java) { pool.reserve() }
        now.addAndGet(TcpPortAllocator.QUIET_MILLIS - 1)
        assertThrows(IOException::class.java) { pool.reserve() }
        now.incrementAndGet()
        assertEquals(first.toLong(), pool.reserve().toLong())
    }

    @Test fun activePortsNeverReusedEvenAfterQuietPeriodAndWrap() {
        val now = AtomicLong()
        val pool = TcpPortAllocator(LongSupplier { now.get() }, TcpPortAllocator.PORT_COUNT - 1)
        val ports = HashSet<Int>()
        for (index in 0 until TcpPortAllocator.PORT_COUNT) {
            val port = pool.reserve()
            assertTrue(port in 49152..65535)
            assertTrue(ports.add(port))
        }
        now.set(1_000_000)
        assertThrows(IOException::class.java) { pool.reserve() }
    }

    @Test fun duplicateOrForeignReleaseCannotShortenLease() {
        val now = AtomicLong()
        val pool = TcpPortAllocator(LongSupplier { now.get() }, 0)
        val port = pool.reserve()
        pool.release(port)
        now.set(TcpPortAllocator.QUIET_MILLIS - 1)
        pool.release(port)
        pool.release(40000)
        for (index in 1 until TcpPortAllocator.PORT_COUNT) { pool.reserve() }
        assertThrows(IOException::class.java) { pool.reserve() }
        now.incrementAndGet()
        assertEquals(port.toLong(), pool.reserve().toLong())
    }

    @Test fun invalidConfigurationRejected() {
        assertThrows(IllegalArgumentException::class.java) { TcpPortAllocator(null, 0) }
        assertThrows(IllegalArgumentException::class.java) { TcpPortAllocator(LongSupplier { 0 }, -1) }
        assertThrows(IllegalArgumentException::class.java) { TcpPortAllocator(LongSupplier { 0 }, 16384) }
    }
}
