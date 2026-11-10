package com.byd.dashcast.phase1

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Independently specified DCV1 receiver contract and malformed-input cases. */
class NoAuthVideoProtocolTest {
    @Test
    fun helloIsVersionNegotiationWithoutCredentials() {
        val request = NoAuthVideoProtocol.hello()
        assertArrayEquals(header(7, 16, 0, 0), request.copyOf(24))
        assertArrayEquals(ByteArray(8), request.copyOfRange(24, 32))
        assertEquals("DCV1", String(request, 32, 4, Charsets.US_ASCII))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1), request.copyOfRange(36, 40))
    }

    @Test
    fun startAndStopAreFixedSmallWindowAndEmptyCleanupRequest() {
        val request = NoAuthVideoProtocol.start()
        assertArrayEquals(header(1, 24, 0, 0), request.copyOf(24))
        assertArrayEquals(ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
            .putInt(640).putInt(240).putInt(640).putInt(240).putInt(30).putInt(0).array(),
            request.copyOfRange(24, 48))
        assertArrayEquals(header(3, 0, 0, 0), NoAuthVideoProtocol.stop())
    }

    @Test
    fun frameCarriesBigEndianSequenceRelativePtsAndIdrFlag() {
        val packet = NoAuthVideoProtocol.frame(IDR, 1, 0)
        val expected = header(2, IDR.size, 1, 0)
        expected[23] = 1
        assertArrayEquals(expected, packet.copyOf(24))
        assertArrayEquals(IDR, packet.copyOfRange(24, packet.size))
        assertArrayEquals(header(2, P.size, 1800, 60000),
            NoAuthVideoProtocol.frame(P, 1800, 60000).copyOf(24))
    }

    @Test
    fun frameRejectsLengthsSequenceAndPtsBoundaries() {
        for (frame in arrayOf<ByteArray?>(null, ByteArray(0), ByteArray(0x200001))) {
            assertThrows(IOException::class.java) { NoAuthVideoProtocol.frame(frame, 1, 0) }
        }
        for (sequence in intArrayOf(-1, 0, 1801, Int.MAX_VALUE)) {
            assertThrows(IOException::class.java) { NoAuthVideoProtocol.frame(IDR, sequence, 0) }
        }
        for (pts in longArrayOf(-1, 0x100000000L, Long.MAX_VALUE)) {
            assertThrows(IOException::class.java) { NoAuthVideoProtocol.frame(IDR, 1, pts) }
        }
    }

    @Test
    fun frameRejectsMissingConfigurationAndMalformedNalUnits() {
        val invalid = arrayOf(P, byteArrayOf(0, 0, 1), byteArrayOf(0, 0, 1, 0xe5.toByte(), 0),
            byteArrayOf(0, 0, 1, 0x78, 0),
            byteArrayOf(0, 0, 1, 0x67, 1, 0, 0, 1, 0x65, 1),
            byteArrayOf(0, 0, 1, 0x67, 1, 0, 0, 1, 0x68, 1, 0, 0, 1, 0x65, 1, 0, 0, 1, 0x41, 1))
        for (frame in invalid) {
            assertThrows(IOException::class.java) { NoAuthVideoProtocol.frame(frame, 1, 0) }
        }
        assertThrows(IOException::class.java) { NoAuthVideoProtocol.frame(byteArrayOf(0, 0, 1, 0x09, 1), 2, 33) }
    }

    @Test
    fun offerRejectsCredentialsAndWrongEnvelope() {
        NoAuthVideoProtocol.validateOffer(header(0x85, 80, 0, 0), ByteArray(80))
        val nonzero = ByteArray(80)
        nonzero[79] = 1
        assertThrows(IOException::class.java) { NoAuthVideoProtocol.validateOffer(header(0x85, 80, 0, 0), nonzero) }
        assertThrows(IOException::class.java) { NoAuthVideoProtocol.validateOffer(header(0x85, 80, 0, 0), null) }
        assertThrows(IOException::class.java) { NoAuthVideoProtocol.validateOffer(header(0x85, 80, 0, 0), ByteArray(79)) }
        val old = header(0x85, 80, 0, 0)
        old[3] = '0'.code.toByte()
        assertThrows(IOException::class.java) { NoAuthVideoProtocol.validateOffer(old, ByteArray(80)) }
    }

    @Test
    fun everyReplyHeaderFieldIsChecked() {
        for (position in intArrayOf(0, 7, 11, 15, 19, 23)) {
            val bad = header(0x82, 16, 2, 33)
            bad[position] = (bad[position].toInt() xor 1).toByte()
            assertThrows(IOException::class.java) { NoAuthVideoProtocol.reply(bad, state(23, 2, 33), 2, 2, 33) }
        }
        assertThrows(IOException::class.java) { NoAuthVideoProtocol.reply(null, state(23, 2, 33), 2, 2, 33) }
        assertThrows(IOException::class.java) { NoAuthVideoProtocol.reply(ByteArray(23), state(23, 2, 33), 2, 2, 33) }
        assertThrows(IOException::class.java) {
            NoAuthVideoProtocol.reply(header(0x82, 16, 2, 33), ByteArray(17), 2, 2, 33)
        }
    }

    @Test
    fun acknowledgementNeedsConsistentCountersAndNoCleanupError() {
        for (flags in intArrayOf(8, 31, 32, -1)) {
            assertThrows(IOException::class.java) {
                NoAuthVideoProtocol.reply(header(0x82, 16, 2, 33), state(flags, 2, 33), 2, 2, 33)
            }
        }
        for (count in intArrayOf(-1, 0, 1, 3, 1801)) {
            assertThrows(IOException::class.java) {
                NoAuthVideoProtocol.reply(header(0x82, 16, 2, 33), state(23, count, 33), 2, 2, 33)
            }
        }
        val rejected = state(23, 2, 33)
        rejected[3] = 1
        assertThrows(IOException::class.java) { NoAuthVideoProtocol.reply(header(0x82, 16, 2, 33), rejected, 2, 2, 33) }
        assertThrows(IOException::class.java) {
            NoAuthVideoProtocol.reply(header(0x82, 16, 2, 33), state(23, 2, 32), 2, 2, 33)
        }
    }

    @Test
    fun windowReportAndCleanupDoNotConflateListenerSubmissionOrActualPicture() {
        for (flags in intArrayOf(0, 1, 2, 3, 7, 19, 22)) {
            assertFalse(NoAuthVideoProtocol.reply(header(0x88, 16, 0, 0), state(flags, 2, 33), 8, 0, 0).windowReported())
        }
        assertFalse(NoAuthVideoProtocol.Reply(23, 1, 0).windowReported())
        val good = NoAuthVideoProtocol.Reply(23, 2, 33)
        assertTrue(good.windowReported())
        assertFalse(good.cleanupReported())
        assertTrue(good.summary().contains("NOT decoded/post-frame counts"))
        assertTrue(NoAuthVideoProtocol.Reply(0, 2, 0).cleanupReported())
    }

    @Test
    fun heartbeatHasNoCredentialAndCounterIsBounded() {
        val request = NoAuthVideoProtocol.heartbeat(1)
        assertArrayEquals(header(8, 20, 0, 0), request.copyOf(24))
        assertArrayEquals(ByteArray(16), request.copyOfRange(24, 40))
        for (counter in intArrayOf(0, -1, 1805, Int.MAX_VALUE)) {
            assertThrows(IOException::class.java) { NoAuthVideoProtocol.heartbeat(counter) }
        }
    }

    companion object {
        @JvmField val IDR = byteArrayOf(0, 0, 1, 0x67, 1, 0, 0, 1, 0x68, 1, 0, 0, 1, 0x65, 1)
        @JvmField val P = byteArrayOf(0, 0, 0, 1, 0x41, 1)

        @JvmStatic
        fun header(type: Int, length: Int, sequence: Int, pts: Long): ByteArray =
            ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
                .put("DCV1".toByteArray(Charsets.US_ASCII)).putInt(type).putInt(length)
                .putInt(sequence).putInt(pts.toInt()).putInt(0).array()

        @JvmStatic
        fun state(flags: Int, submissions: Int, pts: Long): ByteArray =
            ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                .putInt(0).putInt(flags).putInt(submissions).putInt(pts.toInt()).array()
    }
}
