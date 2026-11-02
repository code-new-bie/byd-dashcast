package com.byd.dashcast.phase1.ncm;

import org.junit.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

/** Actual Ethernet/IP/TCP code against a deterministic old-tuple peer, not QNX. */
public final class NetReconnectTest {
    private static final int SERVER_PORT = 31707;

    private static final class Peer {
        final Set<Integer> priorPorts = new HashSet<>();
        Net client;
        final Net network = new Net((frame, length) -> client.onFrame(frame, length));
        Peer() {
            network.configure(Prefs.DEF_PEER_MAC, Prefs.DEF_PEER_IP,
                    Prefs.DEF_MAC, Prefs.DEF_LOCAL_IP, 30, 1500, true);
            network.running = true;
        }
        void receive(byte[] frame, int length) {
            int flags = Wire.be16(frame, 46) & 0x1ff;
            if ((flags & TcpConn.F_SYN) == 0) { return; }
            int source = Wire.be16(frame, 34);
            boolean reused = !priorPorts.add(source);
            TcpConn reply = new TcpConn(network, network.peerIp(), source, SERVER_PORT);
            network.sendTcp(reply, reused ? TcpConn.F_ACK : TcpConn.F_SYN | TcpConn.F_ACK,
                    1000, (Wire.be32(frame, 38) + 1) & 0xffffffffL, null, 0, 0);
        }
        Net freshClient() {
            client = new Net(this::receive);
            client.running = true;
            return client;
        }
    }

    @Test public void tenRebuiltUsbStacksConnectWithoutReusingPeerTuple() throws Exception {
        Peer peer = new Peer();
        for (int session = 0; session < 10; session++) {
            Net client = peer.freshClient();
            TcpConn connection = client.connect(client.peerIp(), SERVER_PORT, 100);
            assertTrue(connection.statusLine(), connection.isEstablished());
            connection.close(0);
            assertEquals(0, client.activeTcpCount());
            client.stop();
        }
        assertEquals(10, peer.priorPorts.size());
    }

    @Test public void oldFixedSourcePortWithResidualAckDoesNotEstablishHandshake() {
        Peer peer = new Peer();
        peer.priorPorts.add(40000);
        Net client = peer.freshClient();
        TcpConn old = new TcpConn(client, client.peerIp(), SERVER_PORT, 40000);
        // Execute the old SYN-SENT behaviour directly: an old pure ACK is not a SYN/ACK.
        old.startConnect(100);
        old.onPacket(TcpConn.F_ACK, 1000, 380001, 65535, new byte[0], 0, 0, 0, 101);
        assertFalse(old.isEstablished());
        assertEquals(TcpConn.SYN_SENT, old.state());
        assertTrue(LogHub.snapshot().contains("TCP_HANDSHAKE_NON_SYN local_port=40000"));
    }

    @Test public void rebuiltStackHasDistinctPortEvenAfterFailedConnect() throws Exception {
        Set<Integer> ports = new HashSet<>();
        for (int session = 0; session < 3; session++) {
            Net client = new Net((frame, length) -> {
                if ((Wire.be16(frame, 46) & TcpConn.F_SYN) != 0) {
                    assertTrue(ports.add(Wire.be16(frame, 34)));
                }
            });
            client.running = true;
            assertFalse(client.connect(client.peerIp(), SERVER_PORT, 1).isEstablished());
            assertEquals(0, client.activeTcpCount());
            client.stop();
        }
        assertEquals(3, ports.size());
    }
}
