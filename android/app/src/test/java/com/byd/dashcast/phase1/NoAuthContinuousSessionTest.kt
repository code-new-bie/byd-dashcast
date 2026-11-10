package com.byd.dashcast.phase1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.function.LongSupplier

/** 54,000 frames/30 minutes of virtual timestamps over an in-memory ordered peer, not real USB. */
class NoAuthContinuousSessionTest {
    private class Peer : NoAuthVideoTransport {
        val replies = ArrayDeque<ByteArray>()
        var frames = 0
        var lastPts = 0L
        var heartbeats = 0
        var stopped = false

        init {
            replies.add(NoAuthVideoProtocolTest.header(0x85, 80, 0, 0))
            replies.add(ByteArray(80))
        }

        override fun write(data: ByteArray) {
            val wire = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            wire.position(4)
            val kind = wire.getInt()
            wire.getInt()
            val sequence = wire.getInt()
            val pts = Integer.toUnsignedLong(wire.getInt())
            if (kind == 7) { assertEquals(2, data[39].toInt()) }
            if (kind == 2) { assertEquals(++frames, sequence); lastPts = pts }
            if (kind == 8) { heartbeats++ }
            if (kind == 3) { stopped = true }
            replies.add(NoAuthVideoProtocolTest.header(kind or 0x80, 16, sequence, pts))
            replies.add(NoAuthVideoProtocolTest.state(if (kind == 3 || kind == 7) 0 else if (frames == 0) 1 else 23,
                frames, if (kind == 3) 0 else lastPts))
        }

        override fun readExact(count: Int): ByteArray {
            val data = replies.remove(); assertEquals(count, data.size); return data
        }
        override fun beginCleanup() {}
        override fun close() {}
    }

    @Test
    fun continuousFramesPassOldMinuteLimitWithHeartbeatAndSameStreamStop() {
        val peer = Peer()
        val clock = AtomicLong()
        val client = NoAuthVideoClient(NoAuthVideoClient.Connector { _, _ -> peer },
            AtomicBoolean(), NoAuthVideoClient.Logger {}, LongSupplier { clock.get() }, true)
        client.use { owned ->
            owned.begin()
            for (index in 0 until 54000) {
                clock.set(index * 1_000_000_000L / 30)
                owned.frame(if (index == 0) NoAuthVideoProtocolTest.IDR else NoAuthVideoProtocolTest.P,
                    1_000_000L + index * 1_000_000L / 30)
            }
            owned.requireWindowReport()
            assertTrue(owned.statistics().contains("acknowledged=54000"))
            assertTrue(owned.statistics().contains("source_duration_ms=1799966"))
        }
        assertEquals(54000, peer.frames)
        assertTrue(peer.heartbeats in 1700..1801)
        assertTrue(peer.stopped && client.cleanupConfirmed())
        assertTrue(peer.replies.isEmpty())
    }

    @Test
    fun shortAndLongContractsKeepIndependentFrameBounds() {
        assertThrows(IOException::class.java) { NoAuthVideoProtocol.frame(NoAuthVideoProtocolTest.P, 1801, 60000) }
        NoAuthVideoProtocol.frame(NoAuthVideoProtocolTest.P, 1801, 60000, true)
        NoAuthVideoProtocol.frame(NoAuthVideoProtocolTest.P, 65000, 1800000, true)
        NoAuthVideoProtocol.frame(NoAuthVideoProtocolTest.P, 65001, 1800000, true)
        NoAuthVideoProtocol.frame(NoAuthVideoProtocolTest.P, 240000, 7200000, true)
        assertThrows(IOException::class.java) {
            NoAuthVideoProtocol.frame(NoAuthVideoProtocolTest.P, 240001, 7200000, true)
        }
        assertThrows(IOException::class.java) { NoAuthVideoProtocol.heartbeat(240005, true) }
    }

    @Test
    fun encoderEndClosesNativeBeforeWaitingForAndroidCaptureCleanupExit() {
        val peer = Peer()
        val client = NoAuthVideoClient(NoAuthVideoClient.Connector { _, _ -> peer },
            AtomicBoolean(), NoAuthVideoClient.Logger {}, LongSupplier { 0L }, true)
        client.begin()
        val reader = FramedCaptureReader(object : VideoPatternProbe.Observer {
            override fun log(text: String) {}
            override fun frame(data: ByteArray, ptsMicros: Long, keyFrame: Boolean) {
                client.frame(data, ptsMicros)
            }
        }, Runnable {
            try {
                client.requireWindowReport()
                client.close()
            } catch (error: IOException) {
                throw AssertionError(error)
            }
        })
        for (index in 0 until 2) {
            val data = if (index == 0) NoAuthVideoProtocolTest.IDR else NoAuthVideoProtocolTest.P
            reader.onOutput(CaptureFrameProtocol.header(data.size, index * 33333L, index == 0), 24)
            reader.onOutput(data, data.size)
        }
        reader.onOutput(CaptureFrameProtocol.end(33333L), 24)
        assertTrue(peer.stopped && client.cleanupConfirmed())
        // shell EXIT may follow much later; the instrument video-idle watchdog cannot race this STOP.
        reader.finish(false)
    }
}
