package com.byd.dashcast.phase1.ncm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/** Negotiated bounds and malformed USB payload tests. */
class Ntb16CodecTest {
    @Test
    fun roundTripUsesNegotiatedAlignmentAndRemainder() {
        val frames = listOf(ByteArray(60), ByteArray(1514), ByteArray(100))
        val wire = Ntb16Codec.encode(frames, 65536, 16384, 16, 8, 2)
        assertEquals(16, u16(wire, 10))
        assertEquals(0, u16(wire, 6))
        for (index in frames.indices) {
            assertEquals(2, u16(wire, 16 + 8 + 4 * index) % 8)
            assertArrayEquals(frames[index], Ntb16Codec.decode(wire, wire.size)[index])
        }
    }

    @Test
    fun acceptsNdpAfterDatagram() {
        var wire = ByteArray(88)
        System.arraycopy(byteArrayOf('N'.code.toByte(), 'C'.code.toByte(), 'M'.code.toByte(), 'H'.code.toByte(),
            12, 0, 1, 0, 88, 0, 76, 0), 0, wire, 0, 12)
        System.arraycopy(byteArrayOf('N'.code.toByte(), 'C'.code.toByte(), 'M'.code.toByte(), '0'.code.toByte(),
            12, 0, 0, 0, 12, 0, 60, 0), 0, wire, 76, 12)
        // NDP needs one terminator as well as a datagram entry: extend it to 16 bytes.
        wire = wire.copyOf(92)
        wire[8] = 92
        wire[80] = 16
        assertEquals(1, Ntb16Codec.decode(wire, wire.size).size)
    }

    @Test
    fun rejectsTruncationOverlapsCrcAndMissingTerminator() {
        val valid = Ntb16Codec.encode(listOf(ByteArray(60)), 0, 2048, 4, 4, 0)
        assertThrows(IOException::class.java) { Ntb16Codec.decode(valid, valid.size - 1) }
        val overlap = valid.clone()
        overlap[20] = 12
        assertThrows(IOException::class.java) { Ntb16Codec.decode(overlap, overlap.size) }
        val crc = valid.clone()
        crc[15] = '1'.code.toByte()
        assertThrows(IOException::class.java) { Ntb16Codec.decode(crc, crc.size) }
        val chained = valid.clone()
        chained[18] = 12
        assertThrows(IOException::class.java) { Ntb16Codec.decode(chained, chained.size) }
        val noTerminator = valid.clone()
        noTerminator[24] = 40
        noTerminator[26] = 14
        assertThrows(IOException::class.java) { Ntb16Codec.decode(noTerminator, noTerminator.size) }
    }

    @Test
    fun rejectsOversizeFramesAndOverflowingNtb() {
        assertThrows(IOException::class.java) {
            Ntb16Codec.encode(listOf(ByteArray(1515)), 0, 16384, 4, 4, 0)
        }
        assertThrows(IOException::class.java) {
            Ntb16Codec.encode(listOf(ByteArray(1514), ByteArray(1514)), 0, 2048, 4, 4, 0)
        }
        assertThrows(IOException::class.java) {
            Ntb16Codec.encode(listOf(ByteArray(60)), 0, 16384, 4, 4, 4)
        }
    }

    private companion object {
        fun u16(data: ByteArray, offset: Int): Int =
            (data[offset].toInt() and 255) or ((data[offset + 1].toInt() and 255) shl 8)
    }
}
