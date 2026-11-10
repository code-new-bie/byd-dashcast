package com.byd.dashcast.phase1

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FramedCaptureReaderTest {
    @Test
    fun idleAndReadTicksNeverCreatePicturesOrAdvancePts() {
        val picturesAndTicks = intArrayOf(0, 0)
        val reader = FramedCaptureReader(object : VideoPatternProbe.Observer {
            override fun log(text: String) {}
            override fun frame(data: ByteArray, ptsMicros: Long, keyFrame: Boolean) { picturesAndTicks[0]++ }
            override fun tick() { picturesAndTicks[1]++ }
        })
        reader.onOutput(CaptureFrameProtocol.idle(0), 24)
        val first = frame(123, NoAuthVideoProtocolTest.IDR)
        reader.onOutput(first, first.size)
        reader.onOutput(CaptureFrameProtocol.idle(123), 24)
        reader.onIdle()
        reader.onOutput(CaptureFrameProtocol.end(123), 24)
        assertThrows(IOException::class.java) { reader.finish(false) }
        reader.finish(true)
        reader.onIdle()
        assertArrayEquals(intArrayOf(1, 3), picturesAndTicks)
        assertThrows(IOException::class.java) { reader.onOutput(CaptureFrameProtocol.idle(123), 24) }
    }

    @Test
    fun idleMustMatchLastActualPictureTimestamp() {
        val reader = FramedCaptureReader(Sink())
        val first = frame(100, byteArrayOf(1))
        reader.onOutput(first, first.size)
        assertThrows(IOException::class.java) { reader.onOutput(CaptureFrameProtocol.idle(101), 24) }
        val empty = FramedCaptureReader(Sink())
        assertThrows(IOException::class.java) { empty.onOutput(CaptureFrameProtocol.idle(1), 24) }
    }

    @Test
    fun wrongProtocolReportsOnlyTheFourStructuralBytes() {
        val header = "HotFixRoot: synthetic-test".toByteArray(Charsets.UTF_8)
        val error = assertThrows(IOException::class.java) { CaptureFrameProtocol.parse(header.copyOf(24)) }
        assertEquals("DCS1_WRONG_CAPTURE_PROTOCOL magic_hex=486f7446", error.message)
    }

    private class Sink : VideoPatternProbe.Observer {
        val pts = ArrayList<Long>()
        val frames = ArrayList<ByteArray>()
        override fun log(text: String) {}
        override fun frame(data: ByteArray, ptsMicros: Long, keyFrame: Boolean) { frames.add(data); pts.add(ptsMicros) }
    }

    @Test
    fun fragmentedFramesPreserveActualEncoderTimestampsAndPayload() {
        val sink = Sink()
        val reader = FramedCaptureReader(sink)
        val first = frame(123456789L, NoAuthVideoProtocolTest.IDR)
        val second = frame(123490122L, NoAuthVideoProtocolTest.P)
        for (value in first) {
            reader.onOutput(byteArrayOf(value), 1)
        }
        reader.onOutput(second, second.size)
        reader.onOutput(CaptureFrameProtocol.end(123490122L), 24)
        reader.finish(false)
        assertEquals(listOf(123456789L, 123490122L), sink.pts)
        assertArrayEquals(NoAuthVideoProtocolTest.IDR, sink.frames[0])
        assertArrayEquals(NoAuthVideoProtocolTest.P, sink.frames[1])
        assertThrows(IOException::class.java) { reader.onOutput(first, first.size) }
    }

    @Test
    fun rejectsWrongMagicOversizeNegativePtsFlagsAndReservedBeforeAllocation() {
        for (field in intArrayOf(0, 4, 8, 16, 20)) {
            val header = CaptureFrameProtocol.header(1, 1, false)
            val values = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
            if (field == 8) {
                values.putLong(field, -1L)
            } else {
                values.putInt(field, if (field == 4) 0x200001 else 99)
            }
            val reader = FramedCaptureReader(Sink())
            assertThrows(IOException::class.java) { reader.onOutput(header, header.size) }
        }
    }

    @Test
    fun truncatedFramesAndNonMonotonicPtsCannotFinishSuccessfully() {
        val sink = Sink()
        val partial = FramedCaptureReader(sink)
        val complete = frame(1, byteArrayOf(1, 2))
        partial.onOutput(complete, complete.size - 1)
        assertThrows(IOException::class.java) { partial.finish(true) }
        val repeated = FramedCaptureReader(sink)
        repeated.onOutput(complete, complete.size)
        assertThrows(IOException::class.java) { repeated.onOutput(complete, complete.size) }
        assertThrows(IOException::class.java) { repeated.finish(true) }
    }

    @Test
    fun fullThirtyMinuteFrameBudgetIsStreamedWithoutAggregateBuffer() {
        val count = intArrayOf(0)
        val reader = FramedCaptureReader(object : VideoPatternProbe.Observer {
            override fun log(text: String) {}
            override fun frame(data: ByteArray, ptsMicros: Long, keyFrame: Boolean) { count[0]++ }
        })
        for (index in 0 until ProjectionLimits.MAX_FRAMES) {
            val wire = frame(index * 33333L, byteArrayOf(1))
            reader.onOutput(wire, wire.size)
        }
        reader.onOutput(CaptureFrameProtocol.end((ProjectionLimits.MAX_FRAMES - 1) * 33333L), 24)
        reader.finish(false)
        assertEquals(240000, count[0])
    }

    @Test
    fun stopBeforeAnyPictureIsNotACompletedVideoVerification() {
        val stopped = FramedCaptureReader(Sink())
        stopped.onOutput(CaptureFrameProtocol.end(0), 24)
        stopped.finish(true)
        assertThrows(IOException::class.java) { FramedCaptureReader(Sink()).finish(false) }
        assertThrows(IOException::class.java) { CaptureFrameProtocol.header(0, 0, false) }
        assertThrows(IOException::class.java) { CaptureFrameProtocol.header(0x200001, 0, false) }
    }

    @Test
    fun endMustBeExplicitMatchLastPtsAndForbidFurtherFrames() {
        val missing = FramedCaptureReader(Sink())
        val first = frame(1, byteArrayOf(1))
        missing.onOutput(first, first.size)
        assertThrows(IOException::class.java) { missing.finish(true) }
        assertThrows(IOException::class.java) { missing.onOutput(CaptureFrameProtocol.end(2), 24) }
        val ended = FramedCaptureReader(Sink())
        ended.onOutput(first, first.size)
        ended.onOutput(CaptureFrameProtocol.end(1), 24)
        assertThrows(IOException::class.java) { ended.onOutput(first, first.size) }
    }

    private companion object {
        fun frame(pts: Long, body: ByteArray): ByteArray {
            val stream = ByteArrayOutputStream()
            stream.write(CaptureFrameProtocol.header(body.size, pts, true))
            stream.write(body)
            return stream.toByteArray()
        }
    }
}
