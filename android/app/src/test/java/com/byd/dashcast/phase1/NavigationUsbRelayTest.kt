package com.byd.dashcast.phase1

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/**
 * One-shot OEM input, strict runtime profile and cancellation checks with no real car/ADB access.
 * @author Codex
 * @date 2026/10/10
 */
class NavigationUsbRelayTest {
    @Test
    fun sendsOneDirectedBroadcastAfterProfileIdentityAndReceiverChecks() {
        val backend = FakeBackend()
        val logs = ArrayList<String>()
        NavigationUsbRelay(backend, AtomicBoolean(), Consumer { logs.add(it) }).request(InstrumentUsbCommand.NCM)
        assertEquals(listOf("profile", "id -u", "am get-current-user", NavigationUsbProtocol.START_SERVICE,
            "receiver", NavigationUsbProtocol.broadcastCommand(InstrumentUsbCommand.NCM)), backend.calls)
        assertTrue(logs.any { it.startsWith("NAV_BROADCAST_SUBMITTED") })
        assertFalse(logs.any { it.contains("REQUEST_ACCEPTED") })
    }

    @Test
    fun protocolMatchesVerifiedOriginalBroadcastAndUtf16Command() {
        assertEquals("am broadcast --user current -p com.example.amapservice" +
            " -a AUTONAVI_STANDARD_BROADCAST_SEND --ei KEY_TYPE 10001 --ei TYPE 0" +
            " --es NEXT_ROAD_NAME SCMD_USBMODE2",
            NavigationUsbProtocol.broadcastCommand(InstrumentUsbCommand.NCM))
        assertTrue(NavigationUsbProtocol.broadcastCommand(InstrumentUsbCommand.HOST).endsWith("SCMD_USBMODE1"))
        assertEquals(0x43fa1008, NavigationUsbProtocol.NEXT_PATH_FEATURE)
        assertArrayEquals("SCMD_USBMODE2".toByteArray(Charsets.UTF_16LE), InstrumentUsbCommand.NCM.payload())
        NavigationUsbProtocol.verify(NavigationUsbProtocol.DEX_SHA256, 1140461576)
        assertThrows(IOException::class.java) { NavigationUsbProtocol.verify("unknown", 1140461576) }
        assertThrows(IOException::class.java) { NavigationUsbProtocol.verify(NavigationUsbProtocol.DEX_SHA256, 1) }
        assertThrows(IllegalArgumentException::class.java) { NavigationUsbProtocol.broadcastCommand(null) }
    }

    @Test
    fun unsupportedProfileStopsBeforeAnyAdbCommand() {
        val backend = FakeBackend()
        backend.profileError = true
        assertThrows(IOException::class.java) { relay(backend).request(InstrumentUsbCommand.NCM) }
        assertEquals(listOf("profile"), backend.calls)
    }

    @Test
    fun ordinaryAppUidNeverStartsTheServiceOrBroadcasts() {
        val backend = FakeBackend()
        backend.uid = "10123"
        assertThrows(IOException::class.java) { relay(backend).request(InstrumentUsbCommand.NCM) }
        assertEquals(listOf("profile", "id -u"), backend.calls)
    }

    @Test
    fun differentOrUnknownAndroidUserNeverSendsToTheWrongUser() {
        for (user in listOf("10", "unknown", "")) {
            val backend = FakeBackend()
            backend.user = user
            assertThrows(IOException::class.java) { relay(backend).request(InstrumentUsbCommand.NCM) }
            assertEquals(0, backend.broadcasts())
            assertFalse(backend.calls.contains(NavigationUsbProtocol.START_SERVICE))
        }
    }

    @Test
    fun serviceStartFailureNeverBroadcasts() {
        val backend = FakeBackend()
        backend.failedCommand = NavigationUsbProtocol.START_SERVICE
        assertThrows(IOException::class.java) { relay(backend).request(InstrumentUsbCommand.NCM) }
        assertFalse(backend.calls.contains("receiver"))
        assertEquals(0, backend.broadcasts())
    }

    @Test
    fun pollsOnlyReadinessAndDoesNotRetryTheWrite() {
        val backend = FakeBackend()
        backend.readyOn = 3
        relay(backend).request(InstrumentUsbCommand.HOST)
        assertEquals(3, backend.receiverChecks)
        assertEquals(1, backend.broadcasts())
    }

    @Test
    fun missingReceiverFailsClosedAfterBoundedReadOnlyPolling() {
        val backend = FakeBackend()
        backend.readyOn = 99
        assertThrows(IOException::class.java) { relay(backend).request(InstrumentUsbCommand.NCM) }
        assertEquals(6, backend.receiverChecks)
        assertEquals(0, backend.broadcasts())
    }

    @Test
    fun ambiguousBroadcastFailureIsNeverResent() {
        val backend = FakeBackend()
        backend.failedCommand = NavigationUsbProtocol.broadcastCommand(InstrumentUsbCommand.NCM)
        assertThrows(IOException::class.java) { relay(backend).request(InstrumentUsbCommand.NCM) }
        assertEquals(1, backend.broadcasts())
    }

    @Test
    fun cancellationBeforeStartOrAfterReadinessNeverSends() {
        val backend = FakeBackend()
        val cancelled = AtomicBoolean(true)
        val relay = NavigationUsbRelay(backend, cancelled, Consumer {})
        assertThrows(IOException::class.java) { relay.request(InstrumentUsbCommand.NCM) }
        assertTrue(backend.calls.isEmpty())
        cancelled.set(false)
        backend.cancelAfterReceiver = cancelled
        assertThrows(IOException::class.java) { relay.request(InstrumentUsbCommand.NCM) }
        assertEquals(0, backend.broadcasts())
    }

    private class FakeBackend : NavigationUsbRelay.Backend {
        val calls = ArrayList<String>()
        var uid = "2000"
        var user = "0"
        var failedCommand: String? = null
        var profileError = false
        var readyOn = 1
        var receiverChecks = 0
        var cancelAfterReceiver: AtomicBoolean? = null

        override fun verifyProfile() {
            calls.add("profile")
            if (profileError) {
                throw IOException("unverified profile")
            }
        }

        override fun executeChecked(command: String): String {
            calls.add(command)
            if (command == failedCommand) {
                throw IOException("command completion unknown")
            }
            if ("id -u" == command) {
                return uid
            }
            return if ("am get-current-user" == command) user else "Broadcast completed: result=0"
        }

        override fun receiverRegistered(): Boolean {
            calls.add("receiver")
            receiverChecks++
            cancelAfterReceiver?.set(true)
            return receiverChecks >= readyOn
        }

        override fun pause() {}

        fun broadcasts(): Int = calls.count { it.startsWith("am broadcast") }
    }

    private companion object {
        fun relay(backend: FakeBackend): NavigationUsbRelay = NavigationUsbRelay(backend, AtomicBoolean(), Consumer {})
    }
}
