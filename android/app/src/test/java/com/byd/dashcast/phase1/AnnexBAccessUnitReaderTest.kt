package com.byd.dashcast.phase1

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

/** AU grouping and input bounds only; synthetic slice prefixes do not test a hardware decoder. */
class AnnexBAccessUnitReaderTest {
    @Test
    fun noAudStreamIsSplitByFirstMacroblockNotAdbChunks() {
        val output = Collector()
        val first = join(SPS, PPS, slice(4, 5, 0, 2))
        val second = slice(4, 1, 0, 0)
        val wire = join(first, second)
        for (value in wire) {
            output.reader.onOutput(byteArrayOf(value), 1)
        }
        output.reader.finish()
        assertEquals(2, output.frames.size)
        assertArrayEquals(first, output.frames[0])
        assertArrayEquals(second, output.frames[1])
        assertEquals(listOf(true, false), output.keys)
    }

    @Test
    fun mixedThreeAndFourByteStartCodesNormalizeWithoutChangingNalPayload() {
        val output = Collector()
        val wire = join(SPS, PPS, slice(3, 5, 0, 2), slice(3, 1, 0, 0))
        output.reader.onOutput(wire, wire.size)
        output.reader.finish()
        assertArrayEquals(join(SPS, PPS, slice(4, 5, 0, 2)), output.frames[0])
        assertArrayEquals(slice(4, 1, 0, 0), output.frames[1])
    }

    @Test
    fun additionalSlicesStayInOnePicture() {
        val output = Collector()
        val first = join(SPS, PPS, slice(4, 5, 0, 2), slice(4, 5, 120, 2))
        val wire = join(first, slice(4, 1, 0, 0))
        output.reader.onOutput(wire, wire.size)
        output.reader.finish()
        assertEquals(2, output.frames.size)
        assertArrayEquals(first, output.frames[0])
    }

    @Test
    fun audAndParameterSetsBelongToTheNextPicture() {
        val output = Collector()
        val aud = nal(4, 9, byteArrayOf(0xf0.toByte()))
        val first = join(aud, SPS, PPS, slice(4, 5, 0, 2))
        val second = join(aud, slice(4, 1, 0, 0))
        val wire = join(first, second)
        output.reader.onOutput(wire, wire.size)
        output.reader.finish()
        assertArrayEquals(first, output.frames[0])
        assertArrayEquals(second, output.frames[1])
    }

    @Test
    fun unsupportedBFramesMissingFirstSliceAndMalformedPrefixesFailClosed() {
        for (bad in arrayOf(slice(4, 1, 0, 1), slice(4, 1, 1, 0),
            nal(4, 1, byteArrayOf(0, 0, 0, 0)), nal(4, 2, byteArrayOf(0x80.toByte())),
            join(SPS, PPS, slice(4, 5, 0, 2), slice(4, 1, 120, 0)))) {
            val reader = AnnexBAccessUnitReader { _, _ -> }
            assertThrows(IOException::class.java) { reader.onOutput(bad, bad.size); reader.finish() }
        }
    }

    @Test
    fun textInsteadOfH264AndNoPictureDoNotCountAsCaptureSuccess() {
        val text = AnnexBAccessUnitReader { _, _ -> }
        assertThrows(IOException::class.java) {
            text.onOutput(byteArrayOf('E'.code.toByte(), 'r'.code.toByte(), 'r'.code.toByte()), 3)
        }
        assertThrows(IOException::class.java) { text.finish() }
        val empty = AnnexBAccessUnitReader { _, _ -> }
        assertThrows(IOException::class.java) { empty.finish() }
        val config = AnnexBAccessUnitReader { _, _ -> }
        assertThrows(IOException::class.java) { config.onOutput(SPS, SPS.size); config.finish() }
    }

    @Test
    fun callbackFailureCannotBeRetriedAsSuccessfulFinish() {
        val reader = AnnexBAccessUnitReader { _, _ -> throw IOException("sink") }
        val wire = join(SPS, PPS, slice(4, 5, 0, 2))
        assertThrows(IOException::class.java) { reader.onOutput(wire, wire.size); reader.finish() }
        assertThrows(IOException::class.java) { reader.finish() }
        assertEquals(0, reader.frameCount())
    }

    @Test
    fun finishIsIdempotentButMoreOutputIsRejected() {
        val output = Collector()
        val wire = join(SPS, PPS, slice(4, 5, 0, 2), byteArrayOf(0, 0))
        output.reader.onOutput(wire, wire.size)
        output.reader.finish()
        output.reader.finish()
        assertEquals(1, output.reader.frameCount())
        assertEquals(wire.size, output.reader.byteCount())
        assertThrows(IOException::class.java) { output.reader.onOutput(wire, wire.size) }
    }

    @Test
    fun accessUnitAndFrameCountLimitsAreEnforced() {
        val huge = AnnexBAccessUnitReader { _, _ -> }
        val payload = ByteArray(NoAuthVideoProtocol.MAX_FRAME_BYTES)
        payload.fill(0x80.toByte())
        val wire = join(byteArrayOf(0, 0, 0, 1, 0x65), payload)
        assertThrows(IOException::class.java) { huge.onOutput(wire, wire.size); huge.finish() }
        val many = AnnexBAccessUnitReader { _, _ -> }
        val picture = slice(4, 1, 0, 0)
        assertThrows(IOException::class.java) {
            for (index in 0 until NoAuthVideoProtocol.MAX_TEST_FRAMES + 2) {
                many.onOutput(picture, picture.size)
            }
            many.finish()
        }
        assertEquals(NoAuthVideoProtocol.MAX_TEST_FRAMES, many.frameCount())
    }

    @Test
    fun validatesInputsAndRequiresSpsPpsIdrForReceiverFirstFrame() {
        val output = Collector()
        assertThrows(IllegalArgumentException::class.java) { output.reader.onOutput(null, 0) }
        assertThrows(IllegalArgumentException::class.java) { output.reader.onOutput(ByteArray(1), 2) }
        assertThrows(NullPointerException::class.java) { AnnexBAccessUnitReader(null) }
        val bad = slice(4, 1, 0, 0)
        assertThrows(IOException::class.java) { output.reader.onOutput(bad, bad.size); output.reader.finish() }
    }

    private class Collector {
        val frames = ArrayList<ByteArray>()
        val keys = ArrayList<Boolean>()
        val reader = AnnexBAccessUnitReader { data, key ->
            NoAuthVideoProtocol.frame(data, frames.size + 1, 0)
            assertTrue(data.size > 4)
            frames.add(data)
            keys.add(key)
        }
    }

    private companion object {
        val SPS = nal(4, 7, byteArrayOf(0x42, 0xc0.toByte(), 0x28, 0x80.toByte()))
        val PPS = nal(4, 8, byteArrayOf(0xce.toByte(), 0x80.toByte()))

        fun slice(prefix: Int, type: Int, firstMb: Int, sliceType: Int): ByteArray {
            var bits = ue(firstMb) + ue(sliceType) + "1"
            while (bits.length % 8 != 0) {
                bits += "0"
            }
            val payload = ByteArray(bits.length / 8)
            for (index in payload.indices) {
                payload[index] = Integer.parseInt(bits.substring(index * 8, index * 8 + 8), 2).toByte()
            }
            return nal(prefix, type, payload)
        }

        fun ue(value: Int): String {
            val bits = Integer.toBinaryString(value + 1)
            return "0".repeat(bits.length - 1) + bits
        }

        fun nal(prefix: Int, type: Int, payload: ByteArray): ByteArray {
            val start = if (prefix == 3) byteArrayOf(0, 0, 1) else byteArrayOf(0, 0, 0, 1)
            return join(start, byteArrayOf((type or 0x60).toByte()), payload)
        }

        fun join(vararg values: ByteArray): ByteArray {
            val output = ByteArrayOutputStream()
            for (value in values) {
                output.write(value, 0, value.size)
            }
            return output.toByteArray()
        }
    }
}
