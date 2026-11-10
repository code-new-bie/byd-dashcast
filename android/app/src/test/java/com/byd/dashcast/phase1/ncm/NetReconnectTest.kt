package com.byd.dashcast.phase1.ncm

import com.byd.dashcast.phase1.DashcastPorts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual Ethernet/IP/TCP code against a deterministic old-tuple peer, not QNX. */
class NetReconnectTest {
    private class Peer {
        val priorPorts = HashSet<Int>()
        var client: Net? = null
        val network = Net(Net.Out { frame, length -> client!!.onFrame(frame, length) })

        init {
            network.configure(Prefs.DEF_PEER_MAC, Prefs.DEF_PEER_IP,
                Prefs.DEF_MAC, Prefs.DEF_LOCAL_IP, 30, 1500, true)
            network.running = true
        }

        fun receive(frame: ByteArray, length: Int) {
            val flags = Wire.be16(frame, 46) and 0x1ff
            if ((flags and TcpConn.F_SYN) == 0) { return }
            val source = Wire.be16(frame, 34)
            val reused = !priorPorts.add(source)
            val reply = TcpConn(network, network.peerIp(), source, SERVER_PORT)
            network.sendTcp(reply, if (reused) TcpConn.F_ACK else TcpConn.F_SYN or TcpConn.F_ACK,
                1000, (Wire.be32(frame, 38) + 1) and 0xffffffffL, null, 0, 0)
        }

        fun freshClient(): Net {
            val fresh = Net(Net.Out { frame, length -> receive(frame, length) })
            client = fresh
            fresh.running = true
            return fresh
        }
    }

    @Test fun tenRebuiltUsbStacksConnectWithoutReusingPeerTuple() {
        val peer = Peer()
        for (session in 0 until 10) {
            val client = peer.freshClient()
            val connection = client.connect(client.peerIp(), SERVER_PORT, 100)
            assertTrue(connection.statusLine(), connection.isEstablished())
            connection.close(0)
            assertEquals(0, client.activeTcpCount())
            client.stop()
        }
        assertEquals(10, peer.priorPorts.size)
    }

    @Test fun oldFixedSourcePortWithResidualAckDoesNotEstablishHandshake() {
        val peer = Peer()
        peer.priorPorts.add(40000)
        val client = peer.freshClient()
        val old = TcpConn(client, client.peerIp(), SERVER_PORT, 40000)
        // Execute the old SYN-SENT behaviour directly: an old pure ACK is not a SYN/ACK.
        old.startConnect(100)
        old.onPacket(TcpConn.F_ACK, 1000, 380001, 65535, ByteArray(0), 0, 0, 0, 101)
        assertFalse(old.isEstablished())
        assertEquals(TcpConn.SYN_SENT, old.state())
        assertTrue(LogHub.snapshot().contains("TCP_HANDSHAKE_NON_SYN local_port=40000"))
    }

    @Test fun rebuiltStackHasDistinctPortEvenAfterFailedConnect() {
        val ports = HashSet<Int>()
        for (session in 0 until 3) {
            val client = Net(Net.Out { frame, _ ->
                if ((Wire.be16(frame, 46) and TcpConn.F_SYN) != 0) {
                    assertTrue(ports.add(Wire.be16(frame, 34)))
                }
            })
            client.running = true
            assertFalse(client.connect(client.peerIp(), SERVER_PORT, 1).isEstablished())
            assertEquals(0, client.activeTcpCount())
            client.stop()
        }
        assertEquals(3, ports.size)
    }

    private companion object {
        const val SERVER_PORT = DashcastPorts.PROJECTION
    }
}
