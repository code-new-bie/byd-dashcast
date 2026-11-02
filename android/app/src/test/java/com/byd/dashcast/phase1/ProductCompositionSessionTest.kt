package com.byd.dashcast.phase1

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.util.function.LongSupplier

class ProductCompositionSessionTest {
    private class Peer : NoAuthVideoTransport {
        var now = 100_000_000_000L
        var state = 0
        var nonce = 0
        var pid = 123
        var epoch = 1
        var capability = ProductCompositionSession.CAPABILITY
        var error = 0
        var skip = 1
        var ownerOverride: Int? = null
        var navigationOverride: Int? = null
        var pending = false
        var nativeMode = 0
        var nativeModeOverride: Int? = null
        var deferStart = false
        var requestSequence = 2
        var appliedSequence = 2
        var requestedOwner = 0
        var requestedOwnerOverride: Int? = null
        var nativeReason = 0
        val operations = mutableListOf<Int>()
        lateinit var bytes: ByteArray
        var offset = 0
        fun session(center: Boolean = false) = ProductCompositionSession({ }, LongSupplier { now }, 77, center)
        override fun write(request: ByteArray) {
            val packet = ByteBuffer.wrap(request)
            assertEquals(10, packet.getInt(4))
            val op = packet.getInt(28)
            operations += op
            if (op == 1) {
                requestedOwner = packet.getInt(32)
                if (deferStart) { requestSequence = 4 }
                else { nonce = requestedOwner; state = 1; nativeMode = if (packet.getInt(36) == 5001) 1 else 0 }
            }
            if (op == 2 && error == 0) { assertEquals(nonce, packet.getInt(32)); state = 2; nativeMode = 0 }
            if (op == 3) { assertEquals(nonce, packet.getInt(32)); assertEquals(5000, packet.getInt(36)) }
            val words = IntArray(32)
            words[0] = 0x31474244; words[1] = 1; words[2] = 128
            words[3] = if (pending) 4 else requestSequence; words[8] = appliedSequence
            words[9] = state; words[10] = pid; words[11] = nativeReason
            words[12] = (now / 1_000_000_000L).toInt(); words[13] = (now % 1_000_000_000L).toInt()
            words[14] = words[12] + 5; words[15] = words[13]; words[18] = epoch
            words[21] = ownerOverride ?: nonce; words[30] = nativeModeOverride ?: nativeMode; words[31] = capability
            words[20] = requestedOwnerOverride ?: requestedOwner
            words[29] = navigationOverride ?: if (state == 1) ProductCompositionSession.NAVIGATION_ACTIVE
                else ProductCompositionSession.NAVIGATION_READY
            words[16] = if (nonce == 0) 0 else skip; words[17] = words[16]
            val out = ByteBuffer.allocate(168)
            out.putInt(0x44435631).putInt(0x8a).putInt(144).putInt(0).putInt(0).putInt(0)
            out.putInt(1).putInt(0x42475031).putInt(op).putInt(error)
            words.forEach { out.putInt(it) }
            bytes = out.array(); offset = 0
        }
        override fun readExact(size: Int): ByteArray = bytes.copyOfRange(offset, offset + size).also { offset += size }
        override fun beginCleanup() = Unit
        override fun close() = Unit
    }

    @Test fun oneConnectionCrossesTwoHoursWithoutStartReplayAndRestoresBeforeMediaStop() {
        val p = Peer(); val session = p.session()
        session.prepare(p); session.activate(p)
        repeat(43200) { p.now += 1_000_000_000L; session.tick(p) }
        session.restore(p); session.restore(p)
        assertEquals(1, p.operations.count { it == 1 })
        assertEquals(43200, p.operations.count { it == 3 })
        assertEquals(1, p.operations.count { it == 2 })
        assertEquals(2, p.state)
    }
    @Test fun oldFirmwareFailsBeforeStartingAnyLease() {
        val p = Peer().apply { capability = 0 }
        assertThrows(IOException::class.java) { p.session().prepare(p) }
        assertEquals(listOf(0), p.operations)
    }
    @Test fun frozenRwv3AndRwv4AreNotAcceptedWithWiderRwv5Content() {
        for (old in listOf(0x52575633, 0x52575634)) {
            val p = Peer().apply { capability = old }
            assertThrows(IOException::class.java) { p.session().prepare(p) }
            assertEquals(listOf(0), p.operations)
        }
    }
    @Test fun oldUnlimitedFirmwareWithoutLayoutFailsBeforeStartingLease() {
        val p = Peer().apply { navigationOverride = 0 }
        assertThrows(IOException::class.java) { p.session().prepare(p) }
        assertEquals(listOf(0), p.operations)
    }
    @Test fun oldSetterOnlyNAV1CannotImpersonateNativeEffectReadback() {
        val p = Peer().apply { navigationOverride = 0x4e415600 }
        assertThrows(IOException::class.java) { p.session().prepare(p) }
        assertEquals(listOf(0), p.operations)
    }

    @Test fun pendingEffectsHaveShortGraceButCannotRenewWithoutApplication() {
        val p = Peer(); val s = p.session(); s.prepare(p); s.activate(p)
        p.navigationOverride = ProductCompositionSession.NAVIGATION_PENDING
        p.now += 1_000_000_000L; s.tick(p)
        assertFalse(p.operations.contains(3))
        p.now += 1_000_000_000L
        assertThrows(IOException::class.java) { s.tick(p) }
    }
    @Test fun activeLayoutLossNeverRenewsAndCanStillRestore() {
        val p = Peer(); val s = p.session(); s.prepare(p); s.activate(p)
        p.navigationOverride = ProductCompositionSession.NAVIGATION_READY
        p.now += 1_000_000_000L
        assertThrows(IOException::class.java) { s.tick(p) }
        assertFalse(p.operations.contains(3))
        s.restore(p)
        assertEquals(2, p.state)
    }
    @Test fun processOrEpochReplacementNeverRenews() {
        for (process in listOf(true, false)) {
            val p = Peer(); val s = p.session(); s.prepare(p); s.activate(p)
            if (process) { p.pid++ } else { p.epoch++ }
            p.now += 1_000_000_000L
            assertThrows(IOException::class.java) { s.tick(p) }
            assertFalse(p.operations.contains(3))
        }
    }
    @Test fun staleBusySnapshotsCannotExtendLease() {
        val p = Peer(); val s = p.session(); s.prepare(p); s.activate(p)
        p.error = 9
        repeat(3) { p.now += 1_000_000_000L; s.tick(p) }
        p.now += 1_000_000_000L
        assertThrows(IOException::class.java) { s.tick(p) }
        assertFalse(p.operations.contains(3))
    }
    @Test fun expiredOrForeignLeaseIsNotRestarted() {
        val p = Peer(); val s = p.session(); s.prepare(p); s.activate(p)
        p.state = 2; p.now += 1_000_000_000L
        assertThrows(IOException::class.java) { s.tick(p) }
        assertEquals(1, p.operations.count { it == 1 })
        assertFalse(p.operations.contains(3))
        val q = Peer(); val t = q.session(); t.prepare(q); t.activate(q); q.ownerOverride = 88
        q.now += 4_000_000_000L
        assertThrows(IOException::class.java) { t.tick(q) }
        assertFalse(q.operations.contains(3))
    }
    @Test fun noBackgroundDrawMeansNoRenewal() {
        val p = Peer().apply { skip = 0 }; val s = p.session(); s.prepare(p); s.activate(p)
        p.now += 1_000_000_000L
        assertThrows(IOException::class.java) { s.tick(p) }
        assertFalse(p.operations.contains(3))
    }
    @Test fun boundedWireRenewalCannotAlterLegacyOneShotProtocol() {
        val renew = ByteBuffer.wrap(ProductCompositionSession.request(3, 77))
        assertEquals(40, renew.capacity()); assertEquals(3, renew.getInt(28)); assertEquals(5000, renew.getInt(36))
        assertThrows(IOException::class.java) { InstrumentBackgroundPoc.request(3, 77) }
        assertThrows(IllegalArgumentException::class.java) { ProductCompositionSession.request(4, 77) }
        assertThrows(IllegalArgumentException::class.java) { ProductCompositionSession.request(3, 0) }
    }
    @Test fun stopRejectIsNotReportedAsClean() {
        val p = Peer(); val s = p.session(); s.prepare(p); s.activate(p); p.error = 5
        assertThrows(IOException::class.java) { s.restore(p) }
        assertEquals(1, p.operations.count { it == 2 })
    }

    @Test fun previouslyConfirmedDrawCounterWrapDoesNotExpireHealthyOwnership() {
        val p = Peer(); val s = p.session(); s.prepare(p); s.activate(p)
        p.now += 1_000_000_000L; s.tick(p)
        p.skip = 0 // uint32 native skip counter returns to its initial bits after a full cycle
        p.now += 1_000_000_000L; s.tick(p)
        assertEquals(2, p.operations.count { it == 3 })
    }

    @Test fun pendingRenewalAfterFortySecondsWaitsForItsOwnAckWithoutNewRenewal() {
        val p = Peer(); val s = p.session(); s.prepare(p); s.activate(p)
        repeat(42) { p.now += 1_000_000_000L; s.tick(p) }
        val renewals = p.operations.count { it == 3 }
        p.pending = true; p.now += 250_000_000L; s.tick(p)
        p.now += 250_000_000L; s.tick(p)
        assertEquals(renewals, p.operations.count { it == 3 })
        p.pending = false; p.now += 500_000_000L; s.tick(p)
        assertEquals(renewals + 1, p.operations.count { it == 3 })
        assertEquals(1, p.operations.count { it == 1 })
        s.restore(p)
        assertEquals(2, p.state)
    }

    @Test fun pendingRenewalStillExpiresAndCannotMaskForeignOwner() {
        for (foreign in listOf(false, true)) {
            val p = Peer(); val s = p.session(); s.prepare(p); s.activate(p)
            repeat(42) { p.now += 1_000_000_000L; s.tick(p) }
            val renewals = p.operations.count { it == 3 }
            p.pending = true
            if (foreign) { p.ownerOverride = 88 }
            p.now += if (foreign) 250_000_000L else 1_500_000_000L
            assertThrows(IOException::class.java) { s.tick(p) }
            assertEquals(renewals, p.operations.count { it == 3 })
            assertEquals(1, p.operations.count { it == 1 })
        }
    }

    @Test fun acknowledgedForeignOwnerOrRevocationDuringPendingNeverRenews() {
        for (revoked in listOf(false, true)) {
            val p = Peer(); val s = p.session(); s.prepare(p); s.activate(p)
            p.now += 1_000_000_000L; s.tick(p)
            if (revoked) { p.pending = true; p.state = 2 } else { p.ownerOverride = 88 }
            val renewals = p.operations.count { it == 3 }
            p.now += 250_000_000L
            assertThrows(IOException::class.java) { s.tick(p) }
            assertEquals(renewals, p.operations.count { it == 3 })
        }
    }

    @Test fun centerModeRenewsOnTheSameConnectionAndRestoresWithoutChangingLeaseBounds() {
        val p = Peer(); val s = p.session(true)
        s.prepare(p); s.activate(p)
        repeat(3600) { p.now += 1_000_000_000L; s.tick(p) }
        assertEquals(1, p.nativeMode)
        assertEquals(1, p.operations.count { it == 1 })
        assertEquals(3600, p.operations.count { it == 3 })
        s.restore(p); assertEquals(0, p.nativeMode)
    }

    @Test fun wrongNativeLayoutProfileCannotRenewEitherFullOrCenterSession() {
        for (center in listOf(false, true)) {
            val p = Peer(); val s = p.session(center); s.prepare(p); s.activate(p)
            p.nativeModeOverride = if (center) 0 else 1
            p.now += 1_000_000_000L
            assertThrows(IOException::class.java) { s.tick(p) }
            assertFalse(p.operations.contains(3))
            p.nativeModeOverride = null
            s.restore(p)
        }
    }

    @Test fun previousRestoredStateWhileOurInitialStartIsPendingIsNotRevocation() {
        val p = Peer().apply { state = 2; nonce = 44; deferStart = true }
        val s = p.session(); s.prepare(p); s.activate(p)
        p.now += 250_000_000L; s.tick(p)
        assertFalse(p.operations.contains(3))
        p.appliedSequence = 4; p.state = 1; p.nonce = 77
        p.now += 250_000_000L; s.tick(p)
        s.restore(p)
        assertEquals(1, p.operations.count { it == 1 })
        assertEquals(2, p.state)
    }

    @Test fun initialStartGraceCannotHideWrongOwnerTimeoutOrNewNativeRejection() {
        for (scenario in 0..2) {
            val p = Peer().apply { state = 2; nonce = 44; deferStart = true }
            val s = p.session(); s.prepare(p); s.activate(p)
            if (scenario == 0) { p.requestedOwnerOverride = 88 }
            if (scenario == 2) { p.appliedSequence = 4; p.nonce = 77; p.state = 1; p.nativeReason = 3 }
            p.now += if (scenario == 1) 1_500_000_000L else 250_000_000L
            assertThrows(IOException::class.java) { s.tick(p) }
            assertFalse(p.operations.contains(3))
            assertEquals(1, p.operations.count { it == 1 })
        }
    }
}
