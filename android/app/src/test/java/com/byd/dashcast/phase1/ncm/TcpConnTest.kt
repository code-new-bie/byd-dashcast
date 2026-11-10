package com.byd.dashcast.phase1.ncm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM TCP regression tests; no device, thread scheduler or Android APIs required. */
class TcpConnTest {
    @Test
    fun finTimeoutIsMeasuredFromFinNotEarlierIdleTraffic() {
        val test = Fixture()
        // Model an already-idle connection immediately after its FIN. No real scheduler or sleep.
        val state = TcpConn::class.java.getDeclaredField("state")
        val began = TcpConn::class.java.getDeclaredField("closeStartedAt")
        state.isAccessible = true
        began.isAccessible = true
        state.setInt(test.connection, TcpConn.FIN_WAIT_1)
        began.setLong(test.connection, 20000)
        test.connection.onTick(20001)
        assertFalse(test.connection.isClosed())
        test.connection.onTick(28000)
        assertFalse(test.connection.isClosed())
        test.connection.onTick(28001)
        assertTrue(test.connection.isClosed())
    }

    private class Fixture {
        val sent = ArrayList<ByteArray>()
        val network = Net(Net.Out { bytes, _ -> sent.add(bytes.clone()) })
        val connection = TcpConn(network, network.peerIp(), 47782, 40000)
        val nextSend: Long

        init {
            network.configure(Prefs.DEF_MAC, Prefs.DEF_LOCAL_IP, Prefs.DEF_PEER_MAC, Prefs.DEF_PEER_IP, 30, 1500, true)
            network.running = true
            connection.startConnect(100)
            val initial = Wire.be32(sent[0], 38)
            nextSend = (initial + 1) and 0xffffffffL
            connection.onPacket(TcpConn.F_SYN or TcpConn.F_ACK, 1000, nextSend, 65535,
                ByteArray(0), 0, 0, 9000, 101)
        }
    }

    @Test
    fun peerMssCannotExceedOurEthernetMtu() {
        val test = Fixture()
        assertEquals(1460, test.connection.peerMssValue())
    }

    @Test
    fun acceptsPartialAckButNotFutureAck() {
        val test = Fixture()
        assertTrue(test.connection.enqueue(ByteArray(100), 100))
        test.connection.pump(102)
        assertEquals(100, test.connection.unackedBytes())
        test.connection.onPacket(TcpConn.F_ACK, 1001, test.nextSend + 50, 65535,
            ByteArray(0), 0, 0, 0, 103)
        assertEquals(50, test.connection.unackedBytes())
        test.connection.onPacket(TcpConn.F_ACK, 1001, test.nextSend + 101, 65535,
            ByteArray(0), 0, 0, 0, 104)
        assertEquals(50, test.connection.unackedBytes())
        test.connection.onPacket(TcpConn.F_ACK, 1001, test.nextSend + 100, 65535,
            ByteArray(0), 0, 0, 0, 105)
        assertEquals(0, test.connection.unackedBytes())
    }

    @Test
    fun duplicateFinCannotAdvanceReceiveSequenceTwice() {
        val test = Fixture()
        test.connection.onPacket(TcpConn.F_ACK or TcpConn.F_FIN, 1001, test.nextSend, 65535,
            ByteArray(0), 0, 0, 0, 103)
        val ack = Wire.be32(test.sent[test.sent.size - 1], 42)
        assertEquals(1002L, ack)
        test.connection.onPacket(TcpConn.F_ACK or TcpConn.F_FIN, 1001, test.nextSend, 65535,
            ByteArray(0), 0, 0, 0, 104)
        assertTrue(test.connection.statusLine().contains("rcvNxt=1002"))
    }

    @Test
    fun inputBufferAndSendQueueAreBounded() {
        val test = Fixture()
        assertFalse(test.connection.enqueue(ByteArray(0x200019), 0x200019))
        val payload = ByteArray(32768)
        test.connection.onPacket(TcpConn.F_ACK, 1001, test.nextSend, 65535,
            payload, 0, payload.size, 0, 103)
        test.connection.onPacket(TcpConn.F_ACK, 33769, test.nextSend, 65535,
            payload, 0, payload.size, 0, 104)
        test.connection.onPacket(TcpConn.F_ACK, 66537, test.nextSend, 65535,
            payload, 0, 1, 0, 105)
        assertTrue(test.connection.isClosed())
        assertEquals("TCP receive buffer limit", test.connection.error())
    }
}
