package com.byd.dashcast.phase1;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.*;

/** Independently specified DCV1 receiver contract and malformed-input cases. */
public final class NoAuthVideoProtocolTest {
    static final byte[] IDR = {0, 0, 1, 0x67, 1, 0, 0, 1, 0x68, 1, 0, 0, 1, 0x65, 1};
    static final byte[] P = {0, 0, 0, 1, 0x41, 1};

    static byte[] header(int type, int length, int sequence, long pts) {
        return ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
                .put("DCV1".getBytes(StandardCharsets.US_ASCII)).putInt(type).putInt(length)
                .putInt(sequence).putInt((int) pts).putInt(0).array();
    }

    static byte[] state(int flags, int submissions, long pts) {
        return ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                .putInt(0).putInt(flags).putInt(submissions).putInt((int) pts).array();
    }

    @Test
    public void helloIsVersionNegotiationWithoutCredentials() {
        byte[] request = NoAuthVideoProtocol.hello();
        assertArrayEquals(header(7, 16, 0, 0), Arrays.copyOf(request, 24));
        assertArrayEquals(new byte[8], Arrays.copyOfRange(request, 24, 32));
        assertEquals("DCV1", new String(request, 32, 4, StandardCharsets.US_ASCII));
        assertArrayEquals(new byte[] {0, 0, 0, 1}, Arrays.copyOfRange(request, 36, 40));
    }

    @Test
    public void startAndStopAreFixedSmallWindowAndEmptyCleanupRequest() {
        byte[] request = NoAuthVideoProtocol.start();
        assertArrayEquals(header(1, 24, 0, 0), Arrays.copyOf(request, 24));
        assertArrayEquals(ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
                .putInt(1920).putInt(720).putInt(1920).putInt(720).putInt(30).putInt(0).array(),
                Arrays.copyOfRange(request, 24, 48));
        assertArrayEquals(header(3, 0, 0, 0), NoAuthVideoProtocol.stop());
    }

    @Test
    public void frameCarriesBigEndianSequenceRelativePtsAndIdrFlag() throws Exception {
        byte[] packet = NoAuthVideoProtocol.frame(IDR, 1, 0);
        byte[] expected = header(2, IDR.length, 1, 0);
        expected[23] = 1;
        assertArrayEquals(expected, Arrays.copyOf(packet, 24));
        assertArrayEquals(IDR, Arrays.copyOfRange(packet, 24, packet.length));
        assertArrayEquals(header(2, P.length, 1800, 60000),
                Arrays.copyOf(NoAuthVideoProtocol.frame(P, 1800, 60000), 24));
    }

    @Test
    public void frameRejectsLengthsSequenceAndPtsBoundaries() {
        for (byte[] frame : new byte[][] {null, new byte[0], new byte[0x200001]}) {
            assertThrows(IOException.class, () -> NoAuthVideoProtocol.frame(frame, 1, 0));
        }
        for (int sequence : new int[] {-1, 0, 1801, Integer.MAX_VALUE}) {
            assertThrows(IOException.class, () -> NoAuthVideoProtocol.frame(IDR, sequence, 0));
        }
        for (long pts : new long[] {-1, 0x100000000L, Long.MAX_VALUE}) {
            assertThrows(IOException.class, () -> NoAuthVideoProtocol.frame(IDR, 1, pts));
        }
    }

    @Test
    public void frameRejectsMissingConfigurationAndMalformedNalUnits() {
        byte[][] invalid = {P, {0, 0, 1}, {0, 0, 1, (byte) 0xe5, 0}, {0, 0, 1, 0x78, 0},
                {0, 0, 1, 0x67, 1, 0, 0, 1, 0x65, 1},
                {0, 0, 1, 0x67, 1, 0, 0, 1, 0x68, 1, 0, 0, 1, 0x65, 1, 0, 0, 1, 0x41, 1}};
        for (byte[] frame : invalid) {
            assertThrows(IOException.class, () -> NoAuthVideoProtocol.frame(frame, 1, 0));
        }
        assertThrows(IOException.class, () -> NoAuthVideoProtocol.frame(new byte[] {0, 0, 1, 0x09, 1}, 2, 33));
    }

    @Test
    public void offerRejectsReferenceAuthIdentityAndWrongEnvelope() throws Exception {
        NoAuthVideoProtocol.validateOffer(header(0x85, 80, 0, 0), new byte[80]);
        byte[] nonzero = new byte[80];
        nonzero[79] = 1;
        assertThrows(IOException.class, () -> NoAuthVideoProtocol.validateOffer(header(0x85, 80, 0, 0), nonzero));
        assertThrows(IOException.class, () -> NoAuthVideoProtocol.validateOffer(header(0x85, 80, 0, 0), null));
        assertThrows(IOException.class, () -> NoAuthVideoProtocol.validateOffer(header(0x85, 80, 0, 0), new byte[79]));
        byte[] old = header(0x85, 80, 0, 0);
        old[1] = 'I'; old[2] = 'C';
        assertThrows(IOException.class, () -> NoAuthVideoProtocol.validateOffer(old, new byte[80]));
    }

    @Test
    public void everyReplyHeaderFieldIsChecked() {
        for (int position : new int[] {0, 7, 11, 15, 19, 23}) {
            byte[] bad = header(0x82, 16, 2, 33);
            bad[position] ^= 1;
            assertThrows(IOException.class, () -> NoAuthVideoProtocol.reply(bad, state(23, 2, 33), 2, 2, 33));
        }
        assertThrows(IOException.class, () -> NoAuthVideoProtocol.reply(null, state(23, 2, 33), 2, 2, 33));
        assertThrows(IOException.class, () -> NoAuthVideoProtocol.reply(new byte[23], state(23, 2, 33), 2, 2, 33));
        assertThrows(IOException.class, () -> NoAuthVideoProtocol.reply(header(0x82, 16, 2, 33), new byte[17], 2, 2, 33));
    }

    @Test
    public void acknowledgementNeedsConsistentCountersAndNoCleanupError() {
        for (int flags : new int[] {8, 31, 32, -1}) {
            assertThrows(IOException.class, () -> NoAuthVideoProtocol.reply(header(0x82, 16, 2, 33),
                    state(flags, 2, 33), 2, 2, 33));
        }
        for (int count : new int[] {-1, 0, 1, 3, 1801}) {
            assertThrows(IOException.class, () -> NoAuthVideoProtocol.reply(header(0x82, 16, 2, 33),
                    state(23, count, 33), 2, 2, 33));
        }
        byte[] rejected = state(23, 2, 33);
        rejected[3] = 1;
        assertThrows(IOException.class, () -> NoAuthVideoProtocol.reply(header(0x82, 16, 2, 33), rejected, 2, 2, 33));
        assertThrows(IOException.class, () -> NoAuthVideoProtocol.reply(header(0x82, 16, 2, 33),
                state(23, 2, 32), 2, 2, 33));
    }

    @Test
    public void windowReportAndCleanupDoNotConflateListenerSubmissionOrActualPicture() throws Exception {
        for (int flags : new int[] {0, 1, 2, 3, 7, 19, 22}) {
            assertFalse(NoAuthVideoProtocol.reply(header(0x88, 16, 0, 0), state(flags, 2, 33), 8, 0, 0).windowReported());
        }
        assertFalse(new NoAuthVideoProtocol.Reply(23, 1, 0).windowReported());
        NoAuthVideoProtocol.Reply good = new NoAuthVideoProtocol.Reply(23, 2, 33);
        assertTrue(good.windowReported());
        assertFalse(good.cleanupReported());
        assertTrue(good.summary().contains("NOT decoded/post-frame counts"));
        assertTrue(new NoAuthVideoProtocol.Reply(0, 2, 0).cleanupReported());
    }

    @Test
    public void heartbeatHasNoCredentialAndCounterIsBounded() throws Exception {
        byte[] request = NoAuthVideoProtocol.heartbeat(1);
        assertArrayEquals(header(8, 20, 0, 0), Arrays.copyOf(request, 24));
        assertArrayEquals(new byte[16], Arrays.copyOfRange(request, 24, 40));
        for (int counter : new int[] {0, -1, 1805, Integer.MAX_VALUE}) {
            assertThrows(IOException.class, () -> NoAuthVideoProtocol.heartbeat(counter));
        }
    }
}
