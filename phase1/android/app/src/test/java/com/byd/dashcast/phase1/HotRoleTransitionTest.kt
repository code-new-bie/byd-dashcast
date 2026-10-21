package com.byd.dashcast.phase1

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class HotRoleTransitionTest {
    private class Model : HotRoleTransition.Backend {
        var ncm = 1
        var all = 1
        var clean = true
        var release = true
        var observed = true
        val calls = mutableListOf<String>()
        override fun observe() = HotRoleTransition.Observation(all, ncm)
        override fun stopAndAwaitClean() { calls += "stop"; requireClean() }
        override fun acquire(): AutoCloseable { calls += "gate"; return AutoCloseable { calls += "gate-close" } }
        override fun requireClean() { if (!clean) { throw IOException("CLEANUP_UNCONFIRMED") } }
        override fun releaseTargetData() { calls += "ALT0+release"; if (!release) { throw IOException("ALT0 refused") } }
        override fun send(command: InstrumentUsbCommand) { calls += "send-${command.name}" }
        override fun awaitObservation(command: InstrumentUsbCommand): Boolean { calls += "observe-result"; return observed }
    }

    @Test fun connectedHostStopsReleasesThenSendsOnceAndDoesNotInventAck() {
        val m = Model()
        assertEquals(HotRoleTransition.Result.HOST_DETACHED_NOT_ACKNOWLEDGED,
            HotRoleTransition(m).execute(InstrumentUsbCommand.HOST))
        assertEquals(listOf("stop", "gate", "ALT0+release", "send-HOST", "observe-result", "gate-close"), m.calls)
    }
    @Test fun duplicateNcmDoesNotStopClaimResetOrSend() {
        val m = Model()
        assertEquals(HotRoleTransition.Result.EXISTING_NCM, HotRoleTransition(m).execute(InstrumentUsbCommand.NCM))
        assertTrue(m.calls.isEmpty())
    }
    @Test fun unknownSessionOrAltZeroFailureNeverSends() {
        for (sessionFailure in listOf(true, false)) {
            val m = Model().apply { clean = !sessionFailure; release = sessionFailure }
            assertThrows(IOException::class.java) { HotRoleTransition(m).execute(InstrumentUsbCommand.HOST) }
            assertFalse(m.calls.any { it.startsWith("send-") })
            if (!sessionFailure) { assertEquals("gate-close", m.calls.last()) }
        }
    }
    @Test fun foreignAndMultipleDevicesNeverGetTouched() {
        for ((all, target) in listOf(1 to 0, 2 to 1, 2 to 2)) {
            val m = Model().apply { this.all = all; ncm = target }
            assertThrows(IOException::class.java) { HotRoleTransition(m).execute(InstrumentUsbCommand.HOST) }
            assertTrue(m.calls.isEmpty())
        }
    }
    @Test fun hostWithoutNcmSendsNoUsbClaimAndUnknownFeedbackStaysUnknown() {
        val m = Model().apply { ncm = 0; all = 0; observed = false }
        assertEquals(HotRoleTransition.Result.REQUEST_SENT_PEER_UNKNOWN, HotRoleTransition(m).execute(InstrumentUsbCommand.HOST))
        assertFalse(m.calls.contains("ALT0+release"))
        assertEquals(1, m.calls.count { it == "send-HOST" })
    }
    @Test fun ncmFromHostWaitsForEnumerationNotJustRelaySuccess() {
        val m = Model().apply { ncm = 0; all = 0 }
        assertEquals(HotRoleTransition.Result.NCM_ENUMERATED, HotRoleTransition(m).execute(InstrumentUsbCommand.NCM))
        assertEquals(listOf("stop", "gate", "send-NCM", "observe-result", "gate-close"), m.calls)
    }
    @Test fun staleObservationIsCheckedAgainBeforeAnyCommand() {
        val m = object : HotRoleTransition.Backend {
            var reads = 0
            var sent = false
            var closed = false
            override fun observe() = if (reads++ == 0) HotRoleTransition.Observation(1, 1) else HotRoleTransition.Observation(2, 1)
            override fun stopAndAwaitClean() = Unit
            override fun acquire() = AutoCloseable { closed = true }
            override fun requireClean() = Unit
            override fun releaseTargetData() { fail("Ambiguous device was claimed") }
            override fun send(command: InstrumentUsbCommand) { sent = true }
            override fun awaitObservation(command: InstrumentUsbCommand) = true
        }
        assertThrows(IOException::class.java) { HotRoleTransition(m).execute(InstrumentUsbCommand.HOST) }
        assertFalse(m.sent); assertTrue(m.closed)
    }
}
