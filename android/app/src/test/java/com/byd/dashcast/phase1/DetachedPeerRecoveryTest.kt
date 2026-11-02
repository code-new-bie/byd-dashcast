package com.byd.dashcast.phase1

import java.io.IOException
import java.nio.ByteBuffer
import java.util.ArrayDeque
import org.junit.Assert.*
import org.junit.Test

class DetachedPeerRecoveryTest {
    private fun snapshot(frames: Int, change: (IntArray) -> Unit = {}): InstrumentBackgroundPoc.Reply {
        val words = IntArray(32)
        words[0] = 0x31474244; words[1] = 1; words[2] = 128
        words[3] = 2; words[8] = 2; words[9] = 2; words[10] = 123; words[18] = 7
        words[24] = frames; words[29] = ProductCompositionSession.NAVIGATION_READY
        words[31] = ProductCompositionSession.CAPABILITY
        change(words)
        return InstrumentBackgroundPoc.Reply(0, words)
    }

    @Test fun restoredPeerWithAdvancingNativeRendererCanRecover() {
        DetachedPeerRecovery.validate(snapshot(100), snapshot(106))
        DetachedPeerRecovery.validate(snapshot(-3), snapshot(3))
    }

    @Test fun activePendingStaleOrDifferentOwnerNeverClearsRecovery() {
        val mutations: List<(IntArray) -> Unit> = listOf(
            { it[9] = 1 }, { it[3] = 4 }, { it[10] = 124 }, { it[18] = 8 },
            { it[29] = ProductCompositionSession.NAVIGATION_ACTIVE }, { it[31] = 0 }, { it[30] = 1 })
        for (mutate in mutations) {
            assertThrows(IOException::class.java) { DetachedPeerRecovery.validate(snapshot(100), snapshot(106, mutate)) }
        }
        assertThrows(IOException::class.java) { DetachedPeerRecovery.validate(snapshot(100), snapshot(100)) }
        assertThrows(IOException::class.java) { DetachedPeerRecovery.validate(snapshot(100), snapshot(99)) }
        assertThrows(IOException::class.java) {
            DetachedPeerRecovery.validate(snapshot(100), InstrumentBackgroundPoc.Reply(9, IntArray(32)))
        }
    }

    @Test fun onlyKnownAmapGatewayIsMappedToAnExplicitMapCandidate() {
        assertEquals("com.autonavi.amapauto/com.autonavi.amapauto.MainMapActivity",
            RemoteLaunchPlan.mapActivityCandidate("com.autonavi.amapauto/com.autonavi.auto.remote.fill.UsbFillActivity"))
        assertEquals("com.autonavi.amapautoclone/com.autonavi.amapauto.MainMapActivity",
            RemoteLaunchPlan.mapActivityCandidate("com.autonavi.amapautoclone/com.autonavi.auto.remote.fill.UsbFillActivity"))
        assertNull(RemoteLaunchPlan.mapActivityCandidate("other.app/com.autonavi.auto.remote.fill.UsbFillActivity"))
        assertNull(RemoteLaunchPlan.mapActivityCandidate("com.autonavi.amapauto/other.Activity"))
    }

    @Test fun bothObservedColdLauncherTracesCannotActivateBackgroundSuppression() {
        assertFalse(ProductVideoReadiness.canActivate(1, 0))
        assertFalse(ProductVideoReadiness.canActivate(19, 599))
        assertFalse(ProductVideoReadiness.canActivate(22, 699))
        assertFalse(ProductVideoReadiness.canActivate(30, 966))
        assertFalse(ProductVideoReadiness.canActivate(29, 2000))
        assertTrue(ProductVideoReadiness.canActivate(31, 1000))
    }

    @Test fun recoveryWireContainsOnlyTwoQueriesAndNeverMediaOrRoleMutation() {
        val reads = ArrayDeque<ByteArray>()
        reads.add(NoAuthVideoProtocolTest.header(0x85, 80, 0, 0)); reads.add(ByteArray(80))
        for (frames in listOf(100, 106)) {
            val record = snapshot(frames)
            val body = ByteBuffer.allocate(144).putInt(1).putInt(0x42475031).putInt(0).putInt(0)
            repeat(32) { body.putInt(record.word(it)) }
            reads.add(NoAuthVideoProtocolTest.header(0x8a, 144, 0, 0))
            reads.add(body.array().copyOfRange(0, 80)); reads.add(body.array().copyOfRange(80, 144))
        }
        val writes = mutableListOf<ByteArray>()
        val peer = object : NoAuthVideoTransport {
            override fun write(packet: ByteArray) { writes += packet.clone() }
            override fun readExact(size: Int): ByteArray = reads.removeFirst().also { assertEquals(size, it.size) }
            override fun beginCleanup() { fail("Read-only recovery has no native media cleanup RPC") }
            override fun close() = Unit
        }
        DetachedPeerRecovery.verify(peer) { assertEquals(1, writes.size) }
        assertEquals(2, writes.size)
        for (packet in writes) {
            assertEquals(10, ByteBuffer.wrap(packet).getInt(4))
            assertEquals(0, ByteBuffer.wrap(packet).getInt(28))
            assertEquals(0, ByteBuffer.wrap(packet).getInt(32))
        }
        assertTrue(reads.isEmpty())
    }

    @Test fun coordinatorExportDoesNotInventAnIdleServiceState() {
        val history = ProjectionJournal()
        history.append("PRODUCT_TEST")
        assertTrue(history.coordinatorSnapshot().startsWith("PRODUCT_COORDINATOR_EVENTS\n"))
        assertFalse(history.coordinatorSnapshot().contains("CONTINUOUS_SESSION IDLE"))
    }

    @Test fun hiddenPartialOpenCleanupFailureStillRequiresQuarantine() {
        val error = IOException("open failed", IOException("kernel failure"))
        error.addSuppressed(IOException("USB_DATA_ALT0_CLEANUP_UNCONFIRMED"))
        assertTrue(DetachedPeerRecovery.localCleanupUnconfirmed(error))
        assertFalse(DetachedPeerRecovery.localCleanupUnconfirmed(IOException("peer still restoring")))
    }
}
