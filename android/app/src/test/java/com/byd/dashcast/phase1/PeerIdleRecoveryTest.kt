package com.byd.dashcast.phase1

import java.io.IOException
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class PeerIdleRecoveryTest {
    private fun snapshot(frame: Int, idle: Boolean = true, pid: Int = 123, capability: Int = ProductCompositionSession.CAPABILITY): IntArray =
        IntArray(32).also {
            it[0] = 0x31474244; it[1] = 1; it[2] = 128
            it[3] = 2; it[8] = 2; it[9] = if (idle) 2 else 1; it[10] = pid; it[18] = 7
            it[24] = frame; it[29] = if (idle) ProductCompositionSession.NAVIGATION_READY else ProductCompositionSession.NAVIGATION_ACTIVE
            it[30] = if (idle) 0 else 1; it[31] = capability
        }

    private class Peer(private val samples: List<IntArray>, private val error: Int = 0) : NoAuthVideoTransport {
        val writes = mutableListOf<ByteArray>()
        private val reads = ArrayDeque<ByteArray>()
        init { reads.add(NoAuthVideoProtocolTest.header(0x85, 80, 0, 0)); reads.add(ByteArray(80)) }
        override fun write(data: ByteArray) {
            writes += data.clone()
            val sample = samples[minOf(writes.size-1, samples.lastIndex)]
            val body = ByteBuffer.allocate(144).putInt(1).putInt(0x42475031).putInt(0).putInt(error)
            sample.forEach { body.putInt(it) }
            reads.add(NoAuthVideoProtocolTest.header(0x8a, 144, 0, 0))
            reads.add(body.array().copyOfRange(0, 80)); reads.add(body.array().copyOfRange(80, 144))
        }
        override fun readExact(size: Int): ByteArray = reads.removeFirst().also { assertEquals(size, it.size) }
        override fun beginCleanup() { fail("Recovery must not mutate receiver media") }
        override fun close() = Unit
    }

    private fun verify(peer: Peer, step: Long = 100_000_000L) {
        val clock = AtomicLong(5_000_000_000L)
        DetachedPeerRecovery.verifyAwaitIdle(peer, { clock.addAndGet(step) }, clock::get)
    }

    @Test fun ordinaryStopWithTwoFreshIdleSnapshotsRecoversWithoutHelloStartStopOrCan() {
        val peer = Peer(listOf(snapshot(100), snapshot(106)))
        verify(peer)
        assertEquals(2, peer.writes.size)
        peer.writes.forEach {
            assertEquals(10, ByteBuffer.wrap(it).getInt(4))
            assertEquals(0, ByteBuffer.wrap(it).getInt(28))
            assertEquals(0, ByteBuffer.wrap(it).getInt(32))
            assertEquals(1, ByteBuffer.wrap(it).getInt(36))
        }
    }

    @Test fun inFlightExpiryThenIdleNeedsTwoNewStableSnapshots() {
        val peer = Peer(listOf(snapshot(98, false), snapshot(99, false), snapshot(100), snapshot(106)))
        verify(peer)
        assertEquals(4, peer.writes.size)
    }

    @Test fun activePeerDoesNotGetForceStoppedAndFiniteWaitLeavesRecoveryPending() {
        val peer = Peer(listOf(snapshot(100, false)))
        assertThrows(IOException::class.java) { verify(peer) }
        assertTrue(peer.writes.size <= 60)
        assertTrue(peer.writes.all { ByteBuffer.wrap(it).getInt(28) == 0 })
    }

    @Test fun frozenOrBackwardsRendererNeverProvidesRecoveryProof() {
        for (samples in listOf(listOf(snapshot(100)), listOf(snapshot(100), snapshot(99)))) {
            assertThrows(IOException::class.java) { verify(Peer(samples)) }
        }
    }

    @Test fun hmiRestartRequiresTwoStableSamplesAndCounterWrapIsAllowed() {
        val restart = Peer(listOf(snapshot(100), snapshot(101, pid=124), snapshot(107, pid=124)))
        verify(restart)
        assertEquals(3, restart.writes.size)
        verify(Peer(listOf(snapshot(-3), snapshot(3))))
    }

    @Test fun mismatchedFirmwareOrUnframedOfferCannotClearRecovery() {
        assertThrows(IOException::class.java) { verify(Peer(listOf(snapshot(100, capability=0)))) }
        val peer = object : NoAuthVideoTransport {
            override fun readExact(size: Int) = ByteArray(size)
            override fun write(data: ByteArray) { fail("No query after malformed offer") }
            override fun beginCleanup() { fail("No cleanup") }
            override fun close() = Unit
        }
        assertThrows(IOException::class.java) { DetachedPeerRecovery.verifyAwaitIdle(peer, {}, { 0L }) }
    }

    @Test fun pausedOrNonAdvancingClockStillHasFiniteQueryCount() {
        val peer = Peer(listOf(snapshot(100, false)))
        assertThrows(IOException::class.java) { verify(peer, 0) }
        assertEquals(64, peer.writes.size)
    }

    @Test fun stickyReceiverFailureOrOldQueryImplementationNeverProvidesRecoveryProof() {
        for (error in listOf(1, 8)) {
            val peer = Peer(listOf(snapshot(100), snapshot(106)), error)
            val failure = assertThrows(IOException::class.java) { verify(peer) }
            assertTrue(failure.message.orEmpty().contains(if (error == 8) "NATIVE_CLEANUP_FAULT" else "INCOMPATIBLE"))
            assertEquals(1, peer.writes.size)
        }
    }
}
