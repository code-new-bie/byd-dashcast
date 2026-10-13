package com.byd.dashcast.phase1.ncm;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** Pure-JVM TCP regression tests; no device, thread scheduler or Android APIs required. */
public final class TcpConnTest {
    private static final class Fixture {
        final List<byte[]> sent = new ArrayList<>();
        final Net network = new Net((bytes, length) -> sent.add(bytes.clone()));
        final TcpConn connection = new TcpConn(network, network.peerIp(), 47782, 40000);
        long nextSend;
        Fixture() {
            network.configure(Prefs.DEF_MAC, Prefs.DEF_LOCAL_IP, Prefs.DEF_PEER_MAC, Prefs.DEF_PEER_IP, 30, 1500, true);
            network.running = true;
            connection.startConnect(100);
            long initial = Wire.be32(sent.get(0), 38);
            nextSend = (initial + 1) & 0xffffffffL;
            connection.onPacket(TcpConn.F_SYN | TcpConn.F_ACK, 1000, nextSend, 65535,
                    new byte[0], 0, 0, 9000, 101);
        }
    }

    @Test
    public void peerMssCannotExceedOurEthernetMtu() {
        Fixture test = new Fixture();
        assertEquals(1460, test.connection.peerMssValue());
    }

    @Test
    public void acceptsPartialAckButNotFutureAck() {
        Fixture test = new Fixture();
        assertTrue(test.connection.enqueue(new byte[100], 100));
        test.connection.pump(102);
        assertEquals(100, test.connection.unackedBytes());
        test.connection.onPacket(TcpConn.F_ACK, 1001, test.nextSend + 50, 65535,
                new byte[0], 0, 0, 0, 103);
        assertEquals(50, test.connection.unackedBytes());
        test.connection.onPacket(TcpConn.F_ACK, 1001, test.nextSend + 101, 65535,
                new byte[0], 0, 0, 0, 104);
        assertEquals(50, test.connection.unackedBytes());
        test.connection.onPacket(TcpConn.F_ACK, 1001, test.nextSend + 100, 65535,
                new byte[0], 0, 0, 0, 105);
        assertEquals(0, test.connection.unackedBytes());
    }

    @Test
    public void duplicateFinCannotAdvanceReceiveSequenceTwice() {
        Fixture test = new Fixture();
        test.connection.onPacket(TcpConn.F_ACK | TcpConn.F_FIN, 1001, test.nextSend, 65535,
                new byte[0], 0, 0, 0, 103);
        long ack = Wire.be32(test.sent.get(test.sent.size() - 1), 42);
        assertEquals(1002, ack);
        test.connection.onPacket(TcpConn.F_ACK | TcpConn.F_FIN, 1001, test.nextSend, 65535,
                new byte[0], 0, 0, 0, 104);
        assertTrue(test.connection.statusLine().contains("rcvNxt=1002"));
    }

    @Test
    public void inputBufferAndSendQueueAreBounded() {
        Fixture test = new Fixture();
        assertFalse(test.connection.enqueue(new byte[0x200019], 0x200019));
        byte[] payload = new byte[32768];
        test.connection.onPacket(TcpConn.F_ACK, 1001, test.nextSend, 65535,
                payload, 0, payload.length, 0, 103);
        test.connection.onPacket(TcpConn.F_ACK, 33769, test.nextSend, 65535,
                payload, 0, payload.length, 0, 104);
        test.connection.onPacket(TcpConn.F_ACK, 66537, test.nextSend, 65535,
                payload, 0, 1, 0, 105);
        assertTrue(test.connection.isClosed());
        assertEquals("TCP receive buffer limit", test.connection.error());
    }
}
