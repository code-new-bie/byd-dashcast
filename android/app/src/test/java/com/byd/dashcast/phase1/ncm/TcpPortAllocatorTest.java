package com.byd.dashcast.phase1.ncm;

import org.junit.Test;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

/** Deterministic port lifetime tests; no Android, vehicle, or wall-clock sleeps. */
public final class TcpPortAllocatorTest {
    @Test public void releasedPortRemainsQuarantinedUntilFullQuietPeriod() throws Exception {
        AtomicLong now = new AtomicLong(100);
        TcpPortAllocator pool = new TcpPortAllocator(now::get, 0);
        int first = pool.reserve();
        pool.release(first);
        for (int index = 1; index < TcpPortAllocator.PORT_COUNT; index++) {
            assertNotEquals(first, pool.reserve());
        }
        assertThrows(IOException.class, pool::reserve);
        now.addAndGet(TcpPortAllocator.QUIET_MILLIS - 1);
        assertThrows(IOException.class, pool::reserve);
        now.incrementAndGet();
        assertEquals(first, pool.reserve());
    }

    @Test public void activePortsNeverReusedEvenAfterQuietPeriodAndWrap() throws Exception {
        AtomicLong now = new AtomicLong();
        TcpPortAllocator pool = new TcpPortAllocator(now::get, TcpPortAllocator.PORT_COUNT - 1);
        Set<Integer> ports = new HashSet<>();
        for (int index = 0; index < TcpPortAllocator.PORT_COUNT; index++) {
            int port = pool.reserve();
            assertTrue(port >= 49152 && port <= 65535);
            assertTrue(ports.add(port));
        }
        now.set(1_000_000);
        assertThrows(IOException.class, pool::reserve);
    }

    @Test public void duplicateOrForeignReleaseCannotShortenLease() throws Exception {
        AtomicLong now = new AtomicLong();
        TcpPortAllocator pool = new TcpPortAllocator(now::get, 0);
        int port = pool.reserve();
        pool.release(port);
        now.set(TcpPortAllocator.QUIET_MILLIS - 1);
        pool.release(port);
        pool.release(40000);
        for (int index = 1; index < TcpPortAllocator.PORT_COUNT; index++) { pool.reserve(); }
        assertThrows(IOException.class, pool::reserve);
        now.incrementAndGet();
        assertEquals(port, pool.reserve());
    }

    @Test public void invalidConfigurationRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TcpPortAllocator(null, 0));
        assertThrows(IllegalArgumentException.class, () -> new TcpPortAllocator(() -> 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new TcpPortAllocator(() -> 0, 16384));
    }
}
