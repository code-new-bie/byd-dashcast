package com.byd.dashcast.phase1

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Virtual time/counters; not a claim of hours or days measured on the vehicle. */
class UnlimitedSessionTest {
    @Test fun unlimitedHasNoTwoHourDayOrMonthDeadlineButTimedToolsStayFinite() {
        ContinuousSessionPolicy.requireCaptureSeconds(0)
        for (seconds in listOf(7200L, 86400L, 7 * 86400L, 60 * 86400L)) {
            assertFalse(ContinuousSessionPolicy.timedOut(0, 100L, 100L + seconds * 1_000_000_000L))
        }
        assertTrue(ContinuousSessionPolicy.timedOut(7200, 0L, 7_200_000_000_000L))
        assertThrows(IllegalArgumentException::class.java) { ContinuousSessionPolicy.requireCaptureSeconds(-1) }
        assertThrows(IllegalArgumentException::class.java) { ProjectionLimits.requireSeconds(0) }
        assertTrue(ShellCaptureCommand.continuous("/data/app/x/base.apk", 0).endsWith(" 0 live"))
        assertThrows(IllegalArgumentException::class.java) { ShellCaptureCommand.create("/data/app/x/base.apk", 0) }
    }

    @Test fun wireSerialsWrapWithoutTransportResetOrFirstFrameAssumption() {
        val serials = StreamSerials(-2, -2)
        for (expected in listOf(-1, 0, 1, 2)) {
            assertEquals(expected, serials.nextFrame()); serials.acceptFrame(expected)
            assertEquals(expected, serials.nextHeartbeat())
            val packet = NoAuthVideoProtocol.frameUnlimited(NoAuthVideoProtocolTest.P, expected, 17, false)
            assertEquals(expected, ByteBuffer.wrap(packet).getInt(12))
        }
        assertThrows(IOException::class.java) {
            NoAuthVideoProtocol.frameUnlimited(NoAuthVideoProtocolTest.P, 1, 0, true)
        }
        assertThrows(IllegalStateException::class.java) { serials.acceptFrame(4) }
    }

    @Test fun versionThreeAndAckPreserveUnsignedSerialsAndPerPacketGuards() {
        assertEquals(3, ByteBuffer.wrap(NoAuthVideoProtocol.helloUnlimited()).getInt(36))
        for (bits in listOf(Int.MAX_VALUE, Int.MIN_VALUE, -1, 0, 1)) {
            val reply = NoAuthVideoProtocol.replyUnlimited(NoAuthVideoProtocolTest.header(0x82, 16, bits, 17),
                NoAuthVideoProtocolTest.state(23, bits, 17), 2, bits, 17)
            assertEquals(bits, reply.submissions); assertTrue(reply.windowReported())
        }
        assertThrows(IOException::class.java) {
            NoAuthVideoProtocol.reply(NoAuthVideoProtocolTest.header(0x82, 16, -1, 17),
                NoAuthVideoProtocolTest.state(23, -1, 17), 2, -1, 17, true)
        }
        assertThrows(IOException::class.java) { NoAuthVideoProtocol.frameUnlimited(ByteArray(0x200001), 0, 0, false) }
        assertThrows(IOException::class.java) { NoAuthVideoProtocol.frameUnlimited(NoAuthVideoProtocolTest.P, 0, -1, false) }
    }

    @Test fun countersSaturateAndLongRunRateDoesNotOverflow() {
        assertEquals(Long.MAX_VALUE, ContinuousSessionPolicy.add(Long.MAX_VALUE - 2, 5))
        assertEquals(3000L, ContinuousSessionPolicy.rateX100(155_520_001L, 0L, 5_184_000_000_000_000L, 1_000_000_000L))
        assertThrows(IllegalArgumentException::class.java) { ContinuousSessionPolicy.add(-1, 1) }
    }

    private class Peer : NoAuthVideoTransport {
        val replies = ArrayDeque<ByteArray>()
        var frames = 0
        var heartbeats = 0
        var stopped = false
        var lastPts = 0L
        var version = 0
        init { replies.add(NoAuthVideoProtocolTest.header(0x85, 80, 0, 0)); replies.add(ByteArray(80)) }
        override fun write(data: ByteArray) {
            val wire = ByteBuffer.wrap(data)
            val op = wire.getInt(4); val sequence = wire.getInt(12)
            val pts = Integer.toUnsignedLong(wire.getInt(16))
            when (op) {
                7 -> version = wire.getInt(36)
                2 -> { frames++; assertEquals(frames, sequence); lastPts = pts }
                8 -> heartbeats++
                3 -> stopped = true
            }
            replies.add(NoAuthVideoProtocolTest.header(op or 0x80, 16, sequence, pts))
            replies.add(NoAuthVideoProtocolTest.state(if (op == 3 || op == 7) 0 else if (frames == 0) 1 else 23,
                frames, if (op == 3) 0 else lastPts))
        }
        override fun readExact(count: Int): ByteArray = replies.removeFirst().also { assertEquals(count, it.size) }
        override fun beginCleanup() = Unit
        override fun close() = Unit
    }

    @Test fun oneStreamCrossesOldFrameAndTwoHourBudgetsThenStopCleansSameConnection() {
        val peer = Peer(); val clock = AtomicLong(); val connectors = intArrayOf(0)
        val client = NoAuthVideoClient({ _, _ -> connectors[0]++; peer }, AtomicBoolean(), { }, { clock.get() }, true)
        client.enableUnlimitedStream(); client.begin()
        repeat(250001) { index ->
            clock.set(index * 33_333_333L)
            client.frame(if (index == 0) NoAuthVideoProtocolTest.IDR else NoAuthVideoProtocolTest.P,
                1_000_000L + index * 33333L)
        }
        clock.addAndGet(1_000_000_000L); client.maintain(); client.close()
        assertEquals(3, peer.version); assertEquals(1, connectors[0]); assertTrue(peer.stopped)
        assertTrue(client.cleanupConfirmed()); assertTrue(peer.heartbeats > 7200)
        assertTrue(peer.replies.isEmpty())
    }

    @Test fun captureCrossesOldAggregateByteBudgetWithoutAnAggregateBuffer() {
        val count = intArrayOf(0)
        val reader = FramedCaptureReader(object : VideoPatternProbe.Observer {
            override fun log(message: String) = Unit
            override fun frame(data: ByteArray, pts: Long, key: Boolean) { assertEquals(0x200000, data.size); count[0]++ }
        }, { }, true)
        val payload = ByteArray(0x200000)
        repeat(4100) { index ->
            val head = CaptureFrameProtocol.header(payload.size, index * 33333L, false)
            reader.onOutput(head, head.size); reader.onOutput(payload, payload.size)
        }
        val end = CaptureFrameProtocol.end(4099 * 33333L); reader.onOutput(end, end.size); reader.finish(false)
        assertEquals(4100, count[0])
        assertTrue(reader.statistics().contains("bytes=${4100L * (payload.size + 24) + 24}"))
    }

    @Test fun capturePassesOldCaptureCountButStillRejectsTruncatedAndOversizedFrames() {
        val count = intArrayOf(0)
        val reader = FramedCaptureReader(object : VideoPatternProbe.Observer {
            override fun log(message: String) = Unit
            override fun frame(data: ByteArray, pts: Long, key: Boolean) { count[0]++ }
        }, { }, true)
        repeat(500005) { index ->
            val header = CaptureFrameProtocol.header(1, index * 33333L, false)
            reader.onOutput(header, header.size); reader.onOutput(byteArrayOf(1), 1)
        }
        val end = CaptureFrameProtocol.end(500004 * 33333L); reader.onOutput(end, end.size); reader.finish(false)
        assertEquals(500005, count[0])
        val bad = FramedCaptureReader(object : VideoPatternProbe.Observer {
            override fun log(message: String) = Unit
            override fun frame(data: ByteArray, pts: Long, key: Boolean) = Unit
        }, { }, true)
        val head = CaptureFrameProtocol.header(2, 1, false)
        bad.onOutput(head, head.size); bad.onOutput(byteArrayOf(1), 1)
        assertThrows(IOException::class.java) { bad.finish(true) }
        assertThrows(IOException::class.java) { CaptureFrameProtocol.header(0x200001, 1, false) }
    }
}
