package com.byd.dashcast.phase1

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

/** @author Codex @date 2026/10/10 */
class AvcByteStreamTest {
    @Test
    fun annexBCopyRespectsInputPosition() {
        val input = ByteBuffer.wrap(byteArrayOf(99, 0, 0, 1, 0x65, 44))
        input.position(1)
        assertArrayEquals(byteArrayOf(0, 0, 1, 0x65, 44), AvcByteStream.normalize(input))
        assertEquals(1, input.position())
    }

    @Test
    fun convertsMultipleLengthPrefixedNals() {
        val source = byteArrayOf(0, 0, 0, 2, 0x67, 11, 0, 0, 0, 2, 0x68, 22)
        val expected = byteArrayOf(0, 0, 0, 1, 0x67, 11, 0, 0, 0, 1, 0x68, 22)
        assertArrayEquals(expected, AvcByteStream.normalize(ByteBuffer.wrap(source)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedNal() {
        AvcByteStream.normalize(ByteBuffer.wrap(byteArrayOf(0, 0, 0, 5, 0x67)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedPrefix() {
        AvcByteStream.normalize(ByteBuffer.wrap(byteArrayOf(0, 0)))
    }

    @Test
    fun usbCommandsAreWhitelistedUtf16LeWithoutBom() {
        assertEquals("SCMD_USBMODE1", InstrumentUsbCommand.HOST.commandText())
        assertEquals(26, InstrumentUsbCommand.NCM.payload().size)
        assertEquals('S'.code.toByte(), InstrumentUsbCommand.NCM.payload()[0])
        assertEquals(0.toByte(), InstrumentUsbCommand.NCM.payload()[1])
    }

    @Test
    fun removesExistingAudWithoutDroppingSpsOrFrame() {
        val source = byteArrayOf(0, 0, 0, 1, 0x67, 11, 0, 0, 1, 9, 0xf0.toByte(), 0, 0, 1, 0x65, 22)
        val expected = byteArrayOf(0, 0, 0, 1, 0x67, 11, 0, 0, 1, 0x65, 22)
        assertArrayEquals(expected, AvcByteStream.withoutAud(source))
    }
}
