package com.byd.dashcast.phase1

import com.byd.dashcast.phase1.NoAuthVideoProtocolTest.Companion.IDR
import com.byd.dashcast.phase1.NoAuthVideoProtocolTest.Companion.P
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import java.util.function.LongSupplier

/** Runs the actual client and composition coordinator, not a rewritten scheduler.
 * The independent peer expires its five-second lease even when media stays busy.
 * @author Codex
 * @date 2026/10/24
 */
class ProductStreamMaintenanceTest {
    private class Peer : NoAuthVideoTransport {
        var now = 100_000_000_000L
        var expiry = Long.MAX_VALUE
        var state = 0
        var owner = 0
        var mode = 0
        var reason = 0
        var sequence = 0
        var frames = 0
        var renewals = 0
        var activations = 0
        var restores = 0
        var stops = 0
        var lastPts = 0L
        var noDraw = false
        var closed = false
        var frameAckNanos = 0L
        val cancelled = AtomicBoolean()
        val wire = ArrayList<Int>()
        val replies = ArrayDeque<ByteArray>()

        fun client(center: Boolean): NoAuthVideoClient {
            closed = false
            replies.add(NoAuthVideoProtocolTest.header(0x85, 80, 0, 0)); replies.add(ByteArray(80))
            val client = NoAuthVideoClient(NoAuthVideoClient.Connector { _, _ -> this },
                cancelled, NoAuthVideoClient.Logger {}, LongSupplier { now }, true)
            client.enableUnlimitedStream()
            client.enableProductComposition(77, center)
            client.begin()
            return client
        }

        override fun write(data: ByteArray) {
            if (data[7].toInt() == 11) { throw IOException("older instrument closes here") }
            if (state == 1 && now >= expiry) { state = 2; reason = 1; mode = 0 }
            val request = ByteBuffer.wrap(data)
            val type = request.getInt(4)
            wire.add(type)
            if (type == 10) {
                val operation = request.getInt(28)
                if (operation == 1) {
                    assertTrue(state != 1); owner = request.getInt(32)
                    state = 1; reason = 0; mode = if (request.getInt(36) == 5001) 1 else 0
                    expiry = now + 5_000_000_000L; sequence += 2; activations++
                } else if (operation == 3) {
                    assertEquals(1, state); assertEquals(owner, request.getInt(32))
                    assertEquals(5000, request.getInt(36))
                    expiry = now + 5_000_000_000L; sequence += 2; renewals++
                } else if (operation == 2) {
                    assertEquals(owner, request.getInt(32)); state = 2; mode = 0
                    sequence += 2; restores++
                }
                val values = IntArray(32)
                values[0] = 0x31474244; values[1] = 1; values[2] = 128
                values[3] = sequence; values[8] = sequence; values[9] = state
                values[10] = 123; values[11] = reason; values[18] = 1
                values[12] = (now / 1_000_000_000L).toInt()
                values[13] = (now % 1_000_000_000L).toInt()
                values[14] = if (state == 1) (expiry / 1_000_000_000L).toInt() else 0
                values[15] = if (state == 1) (expiry % 1_000_000_000L).toInt() else 0
                values[17] = if (state == 1 && !noDraw) (now / 1_000_000L).toInt() else 0
                values[16] = values[17]
                values[21] = owner
                values[20] = values[21]
                values[30] = mode
                values[29] = if (state == 1) 0x4e4c4301 else 0x4e4c4300
                try {
                    // Reflection keeps the same test executable against frozen RWV4 and new RWV5.
                    values[31] = ProductCompositionSession::class.java.getDeclaredField("CAPABILITY").getInt(null)
                } catch (error: ReflectiveOperationException) { throw IOException(error) }
                val body = ByteBuffer.allocate(144).putInt(1).putInt(0x42475031)
                    .putInt(operation).putInt(0)
                for (value in values) { body.putInt(value) }
                replies.add(NoAuthVideoProtocolTest.header(0x8a, 144, 0, 0))
                replies.add(body.array().copyOfRange(0, 80))
                replies.add(body.array().copyOfRange(80, 144))
                return
            }
            if (type == 1) { frames = 0; lastPts = 0 }
            if (type == 2) {
                frames++; lastPts = Integer.toUnsignedLong(request.getInt(16))
                now += frameAckNanos
            }
            if (type == 3) { stops++ }
            val flags = if (type == 1) 1 else if (type == 2 || type == 8) 7 else 0
            replies.add(NoAuthVideoProtocolTest.header(type or 0x80, 16, if (type == 2) request.getInt(12) else 0,
                if (type == 2) lastPts else 0))
            replies.add(NoAuthVideoProtocolTest.state(flags, frames, if (type == 3) 0 else lastPts))
        }

        override fun readExact(count: Int): ByteArray {
            val data = replies.pollFirst()
            if (data == null || data.size != count) { throw IOException("test peer reply order") }
            return data
        }
        override fun beginCleanup() { }
        override fun close() { closed = true }
    }

    @Test fun busyThirtyFpsWithoutAnyIdleCallbackRenewsForTwoMinutes() {
        for (center in booleanArrayOf(false, true)) {
            val peer = Peer()
            peer.client(center).use { client ->
                for (frame in 0 until 3601) {
                    peer.now = 100_000_000_000L + frame * 33_333_334L
                    client.frame(if (frame == 0) IDR else P, frame * 33_333L)
                    if (client.readyForStreaming()) {
                        assertEquals("busy frames must not starve the native lease", 1, peer.state)
                    }
                }
                assertTrue(peer.renewals >= 110)
                assertEquals(1, peer.activations)
                assertEquals(if (center) 1 else 0, peer.mode)
            }
            assertEquals(1, peer.restores); assertEquals(1, peer.stops); assertTrue(peer.closed)
        }
    }

    @Test fun busySixteenFpsDoesNotNeedAThirtyFpsThresholdOrIdleCallback() {
        val peer = Peer()
        peer.client(false).use { client ->
            for (frame in 0..480) {
                peer.now = 100_000_000_000L + frame * 62_500_000L
                client.frame(if (frame == 0) IDR else P, frame * 62_500L)
                if (client.readyForStreaming()) { assertEquals(1, peer.state) }
            }
            assertTrue(peer.renewals > 20)
        }
    }

    private fun activate(peer: Peer, client: NoAuthVideoClient) {
        for (frame in 0..40) {
            peer.now += 33_333_334L
            client.frame(if (frame == 0) IDR else P, frame * 33_333L)
        }
        assertTrue(client.readyForStreaming())
    }

    @Test fun staticSourceIdleStillRenewsWithoutInventingFrames() {
        val peer = Peer()
        peer.client(false).use { client ->
            activate(peer, client)
            val frames = peer.frames
            for (tick in 0 until 80) {
                peer.now += 250_000_000L; client.maintain()
                assertEquals(1, peer.state)
            }
            assertEquals(frames, peer.frames); assertTrue(peer.renewals >= 19)
        }
    }

    @Test fun busyPathRejectsNativeRevocationBeforeAnotherFrame() {
        for (foreign in booleanArrayOf(false, true)) {
            val peer = Peer(); val client = peer.client(false)
            activate(peer, client)
            if (foreign) { peer.owner = 88 } else { peer.state = 2; peer.reason = 1 }
            val frames = peer.frames; peer.now += 300_000_000L
            assertThrows(IOException::class.java) { client.frame(P, 41 * 33_333L) }
            assertEquals(frames, peer.frames)
            peer.owner = 77; peer.reason = 0
            client.close(); assertTrue(client.cleanupConfirmed())
        }
    }

    @Test fun fourSecondUnconfirmedBusyGapCannotBeRenewedBlindly() {
        val peer = Peer(); val client = peer.client(false)
        activate(peer, client)
        val frames = peer.frames
        val renewals = peer.renewals
        peer.now += 4_000_000_000L
        val error = assertThrows(IOException::class.java) { client.frame(P, 41 * 33_333L) }
        assertTrue(error.message!!.contains("CONFIRMATION_LOST"))
        assertEquals(frames, peer.frames); assertEquals(renewals, peer.renewals)
        client.close(); assertTrue(client.cleanupConfirmed())
    }

    @Test fun noNativeDrawCannotBeMaskedByBusyVideoFrames() {
        val peer = Peer(); peer.noDraw = true
        val client = peer.client(false); activate(peer, client)
        peer.now += 1_000_000_000L
        val error = assertThrows(IOException::class.java) { client.frame(P, 41 * 33_333L) }
        assertTrue(error.message!!.contains("DRAW_NOT_CONFIRMED"))
        assertEquals(0, peer.renewals); client.close()
    }

    @Test fun stopAndNewSessionDoNotCarryAnOldOwnerOrReplayStart() {
        val peer = Peer()
        for (round in 0 until 3) {
            peer.client(round % 2 != 0).use { client ->
                activate(peer, client)
                for (tick in 0 until 10) { peer.now += 250_000_000L; client.maintain() }
            }
            assertEquals(2, peer.state); assertTrue(peer.closed); assertTrue(peer.replies.isEmpty())
        }
        assertEquals(3, peer.activations); assertEquals(3, peer.restores); assertEquals(3, peer.stops)
    }

    @Test fun cancelNeverRenewsAndRestoreStillPrecedesStop() {
        val peer = Peer(); val client = peer.client(false); activate(peer, client)
        val renewals = peer.renewals; peer.cancelled.set(true); peer.now += 1_000_000_000L
        assertThrows(IOException::class.java) { client.frame(P, 41 * 33_333L) }
        assertEquals(renewals, peer.renewals); client.close()
        assertEquals(3, peer.wire[peer.wire.size - 1])
        assertEquals(1, peer.restores); assertEquals(1, peer.stops)
    }

    private fun adbStream(batch: Boolean) {
        val realPath: String? = System.getProperty("dashcast.continuity.avc")
        val realPictures = ArrayList<ByteArray>()
        val realKeys = ArrayList<Boolean>()
        if (realPath != null) {
            val raw = Files.readAllBytes(Path.of(realPath))
            assertTrue(raw.size < 1024 * 1024)
            val avc = AnnexBAccessUnitReader { data, key ->
                realPictures.add(data); realKeys.add(key)
            }
            avc.onOutput(raw, raw.size); avc.finish(); assertEquals(180, realPictures.size)
        }
        val peer = Peer(); peer.frameAckNanos = if (realPath == null) 55_000_000L else 75_000_000L
        val client = peer.client(false)
        val input = ByteArrayOutputStream()
        adb(input, 0x4e584e43, 0x01000000, 4096,
            "device::features=shell_v2;\u0000".toByteArray(Charsets.UTF_8))
        adb(input, 0x59414b4f, 7, 1, ByteArray(0))
        val pictures = ByteArrayOutputStream()
        for (frame in 0 until 180) {
            val data = if (realPath == null) (if (frame == 0) IDR else P) else realPictures[frame]
            val key = if (realPath == null) frame == 0 else realKeys[frame]
            pictures.write(CaptureFrameProtocol.header(data.size, frame * 33_333L, key))
            pictures.write(data)
            // First bind/confirm normally, then a valid sub-4096-byte WRTE batches
            // 100 small protocol AUs (60 actual software AVC AUs when provided).
            // Synchronous FRAME ACK/pacer work consumes wall time
            // INSIDE onOutput; ADB beforeRead cannot pulse again until it returns.
            val batchEnd = if (realPath == null) 139 else 99
            if (!batch || frame < 40 || frame == batchEnd || frame == 179) {
                val payload = shell(1, pictures.toByteArray())
                if (batch && frame == batchEnd) { assertTrue(payload.size <= 4096) }
                var start = 0
                while (start < payload.size) {
                    adb(input, 0x45545257, 7, 1, payload.copyOfRange(start, minOf(start + 4096, payload.size)))
                    start += 4096
                }
                pictures.reset()
            }
        }
        adb(input, 0x45545257, 7, 1, shell(1, CaptureFrameProtocol.end(179 * 33_333L)))
        adb(input, 0x45545257, 7, 1, shell(3, byteArrayOf(0)))
        adb(input, 0x45534c43, 7, 1, ByteArray(0))
        val pacer = FramePacer(30, LongSupplier { peer.now }, FramePacer.Sleeper { nanos -> peer.now += nanos })
        val contract = AvcReceiverContract()
        val closeClient = Runnable {
            try { client.close() } catch (error: IOException) { throw IllegalStateException(error) }
        }
        val reader = FramedCaptureReader(object : VideoPatternProbe.Observer {
            override fun log(text: String) { }
            override fun tick() { client.maintain() }
            override fun frame(data: ByteArray, ptsMicros: Long, keyFrame: Boolean) {
                if (realPath != null) { contract.accept(data, Consumer {}) }
                pacer.awaitNextSlot(); client.frame(data, ptsMicros)
            }
        }, closeClient, true)
        val adbClient = AdbShellClient("127.0.0.1", 5555, 20_000, null,
            Consumer {}, AtomicBoolean(), LongSupplier { peer.now })
        try {
            adbClient.executeControlledProtocol(ByteArrayInputStream(input.toByteArray()),
                ByteArrayOutputStream(), "owned-capture-fixture", reader, AtomicBoolean(), closeClient)
            reader.finish(false)
            assertEquals(180, peer.frames); assertTrue(peer.renewals >= 7)
            assertEquals(1, peer.restores); assertEquals(1, peer.stops)
        } finally { client.close() }
    }

    @Test fun actualAdbReadPulseAlreadyMaintainsOrdinaryNonBatchedFrames() {
        adbStream(false)
    }

    @Test fun actualAdbBatchedWrteCannotStarveLeaseInsideSynchronousFrameConsumer() {
        adbStream(true)
    }

    private companion object {
        fun adb(out: ByteArrayOutputStream, type: Int, arg0: Int, arg1: Int, payload: ByteArray) {
            var checksum = 0
            for (b in payload) { checksum += b.toInt() and 255 }
            out.write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(type).putInt(arg0)
                .putInt(arg1).putInt(payload.size).putInt(checksum).putInt(type xor -1).array())
            out.write(payload)
        }

        fun shell(channel: Int, payload: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).put(channel.toByte())
                .putInt(payload.size).array())
            out.write(payload); return out.toByteArray()
        }
    }
}
