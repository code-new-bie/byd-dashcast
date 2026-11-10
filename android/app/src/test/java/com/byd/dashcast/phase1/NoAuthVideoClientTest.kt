package com.byd.dashcast.phase1

import com.byd.dashcast.phase1.NoAuthVideoProtocolTest.Companion.IDR
import com.byd.dashcast.phase1.NoAuthVideoProtocolTest.Companion.P
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.LongSupplier

/** Independent scripted binary receiver; tests cancellation/error/cleanup, not QNX display. */
class NoAuthVideoClientTest {
    @Test
    fun physicalRemovalClosesOnlyOwnedLinkWithoutFabricatingRemoteStop() {
        val link = Link().ready()
        val logs = ArrayList<String>()
        val client = client(link, AtomicBoolean(), logs)
        client.begin()
        client.closeAfterPhysicalDetach()
        client.close()
        assertTrue(link.closed)
        assertFalse(client.cleanupConfirmed())
        assertEquals(2, link.writes.size)
        assertTrue(logs.any { it.startsWith("PHYSICAL_DETACH_REMOTE_CLEANUP_PENDING") })
        assertFalse(logs.any { it.startsWith("STOP_CLEANUP_REPORTED") })
    }

    @Test
    fun productDoesNotActivateBackgroundForShortColdLauncherAnimation() {
        val link = Link().offer().productBackground(0, 0, 0)
            .ack(7, 0, 0, 0, 0, 0).ack(1, 0, 0, 1, 0, 0)
        for (i in 1..22) { link.ack(2, i, (i - 1) * 33L, 7, i, (i - 1) * 33L) }
        link.ack(3, 0, 0, 0, 22, 0)
        val clock = longArrayOf(0)
        val client = NoAuthVideoClient(NoAuthVideoClient.Connector { _, _ -> link },
            AtomicBoolean(), NoAuthVideoClient.Logger {}, LongSupplier { clock[0] }, true)
        client.enableProductComposition(77)
        client.begin()
        for (i in 0 until 22) { client.frame(if (i == 0) IDR else P, i * 33_000L) }
        assertFalse(client.readyForStreaming())
        clock[0] = 12_000_000_000L
        assertThrows(IOException::class.java) { client.maintain() }
        client.close()
        assertTrue(client.cleanupConfirmed())
        assertEquals(1, link.writes.count { it[7].toInt() == 10 })
        assertTrue(link.reads.isEmpty())
    }

    @Test
    fun productActivatesExactlyOnceAfterSustainedSourceProgress() {
        val link = Link().offer().productBackground(0, 0, 0)
            .ack(7, 0, 0, 0, 0, 0).ack(1, 0, 0, 1, 0, 0)
        for (i in 1..31) { link.ack(2, i, (i - 1) * 34L, 7, i, (i - 1) * 34L) }
        link.productBackground(1, 1, 77)
        val client = NoAuthVideoClient(NoAuthVideoClient.Connector { _, _ -> link },
            AtomicBoolean(), NoAuthVideoClient.Logger {}, LongSupplier { 0L }, true)
        client.enableProductComposition(77)
        client.begin()
        for (i in 0 until 30) { client.frame(if (i == 0) IDR else P, i * 34_000L) }
        assertFalse(client.readyForStreaming())
        client.frame(P, 30 * 34_000L)
        assertTrue(client.readyForStreaming())
        assertEquals(2, link.writes.count { it[7].toInt() == 10 })
        assertTrue(link.reads.isEmpty())
        client.closeAfterPhysicalDetach()
    }

    @Test
    fun failedBackgroundRestoreStillSendsMediaStopButCannotClaimCleanSession() {
        val link = Link().offer().background(0, 0, 0).ack(7, 0, 0, 0, 0, 0)
            .ack(1, 0, 0, 1, 0, 0).ack(2, 1, 0, 7, 1, 0).background(1, 1, 77)
            .ack(3, 0, 0, 0, 1, 0)
        link.failBackgroundRestore = true
        val client = NoAuthVideoClient(NoAuthVideoClient.Connector { _, _ -> link },
            AtomicBoolean(), NoAuthVideoClient.Logger {}, LongSupplier { 0L }, true)
        client.enableCompositionTrial(77, Runnable {})
        client.begin()
        client.frame(IDR, 0)
        assertThrows(IOException::class.java) { client.close() }
        assertFalse(client.cleanupConfirmed())
        assertTrue(link.closed && link.reads.isEmpty())
        assertEquals(3, link.writes[link.writes.size - 1][7].toInt())
    }

    @Test
    fun idleMaintenanceKeepsOneRealFrameAliveAndStillConfirmsStop() {
        val link = Link().ready().ack(2, 1, 0, 7, 1, 0)
        for (i in 0 until 6) {
            link.ack(8, 0, 0, 7, 1, 0)
        }
        link.ack(3, 0, 0, 0, 1, 0)
        val clock = longArrayOf(0)
        val client = NoAuthVideoClient(NoAuthVideoClient.Connector { _, _ -> link },
            AtomicBoolean(), NoAuthVideoClient.Logger {}, LongSupplier { clock[0] }, true)
        client.begin()
        client.frame(IDR, 0)
        for (i in 1..6) {
            clock[0] = i * 1_000_000_000L
            client.maintain()
            client.maintain()
        }
        assertTrue(client.statistics().contains("acknowledged=1"))
        client.close()
        assertTrue(client.cleanupConfirmed())
        assertEquals(6, link.writes.count { it[7].toInt() == 8 })
        assertEquals(1, link.writes.count { it[7].toInt() == 2 })
        assertTrue(link.reads.isEmpty())
    }

    @Test
    fun compositionIsContinuousOnlyAndUsesDistinctFixedPreset() {
        val shortClient = client(Link(), AtomicBoolean(), ArrayList())
        assertThrows(IOException::class.java) { shortClient.enableCompositionTrial(77, Runnable {}) }
        val preset = IntArray(6)
        ByteBuffer.wrap(NoAuthVideoProtocol.start(true), 24, 24).asIntBuffer().get(preset)
        assertArrayEquals(intArrayOf(0, 0, 1920, 720, 30, 1), preset)
    }

    @Test
    fun preservedSourceGapCannotBypassTheExistingTwoSecondGuard() {
        val link = Link().offer().ack(7, 0, 0, 0, 0, 0)
            .ack(1, 0, 0, 1, 0, 0).ack(2, 1, 0, 7, 1, 0).ack(3, 0, 0, 0, 1, 0)
        val client = client(link, AtomicBoolean(), ArrayList())
        val timeline = FrameRateGate(30)
        client.begin()
        client.frame(IDR, timeline.outputPts(0))
        assertThrows(IOException::class.java) { client.frame(IDR, timeline.outputPts(3_000_000)) }
        client.close()
        assertTrue(client.cleanupConfirmed())
        assertEquals(1L, link.writes.count { it[7].toInt() == 2 }.toLong())
    }

    @Test
    fun f1IsExplicitShortOnlyAndChecksCapabilityBeforeHello() {
        val link = Link().offer()
        link.reads.add(NoAuthVideoProtocolTest.header(0x89, 40, 0, 0))
        link.reads.add(f1State(0, 0, 0, 0, 1))
        link.ack(7, 0, 0, 0, 0, 0).ack(1, 0, 0, 1, 0, 0)
            .ack(2, 1, 0, 7, 1, 0)
        link.reads.add(NoAuthVideoProtocolTest.header(0x89, 40, 0, 0))
        link.reads.add(f1State(1, 0, 1, 2, 0))
        link.ack(3, 0, 0, 0, 1, 0)
        val client = client(link, AtomicBoolean(), ArrayList())
        client.enableDisplayReadback()
        client.begin()
        assertThrows(IOException::class.java) { client.enableDisplayReadback() }
        client.frame(IDR, 0)
        client.close()
        assertTrue(client.cleanupConfirmed())
        val expected = intArrayOf(9, 7, 1, 2, 9, 3)
        assertEquals(expected.size, link.writes.size)
        for (i in expected.indices) {
            assertEquals(expected[i], link.writes[i][7].toInt())
        }
        val continuous = NoAuthVideoClient(NoAuthVideoClient.Connector { _, _ -> Link() },
            AtomicBoolean(), NoAuthVideoClient.Logger { }, true)
        assertThrows(IOException::class.java) { continuous.enableDisplayReadback() }
    }

    @Test
    fun oldReceiverF1RejectionNeverSendsHelloStartOrVideo() {
        val link = Link().offer()
        val client = client(link, AtomicBoolean(), ArrayList())
        client.enableDisplayReadback()
        assertThrows(IOException::class.java) { client.begin() }
        assertEquals(1, link.writes.size)
        assertEquals(9, link.writes[0][7].toInt())
        assertTrue(link.closed && client.cleanupConfirmed())
    }

    @Test
    fun continuousClientNegotiatesVersionTwoAndStopConfirmsCleanup() {
        val link = Link().ready().ack(3, 0, 0, 0, 0, 0)
        val client = NoAuthVideoClient(NoAuthVideoClient.Connector { _, _ -> link },
            AtomicBoolean(), NoAuthVideoClient.Logger {}, LongSupplier { 0L }, true)
        client.begin()
        assertEquals(2, link.writes[0][39].toInt())
        assertFalse(client.cleanupConfirmed())
        client.close()
        assertTrue(client.cleanupConfirmed())
    }

    @Test
    fun continuousVersionRejectionNeverStartsMediaOrFallsBackToShortProtocol() {
        val link = Link().offer()
        link.reads.add(NoAuthVideoProtocolTest.header(0x87, 16, 0, 0))
        link.reads.add(NoAuthVideoProtocolTest.state(0, 0, 0))
        link.reads.peekLast()!![3] = 1
        val client = NoAuthVideoClient(NoAuthVideoClient.Connector { _, _ -> link },
            AtomicBoolean(), NoAuthVideoClient.Logger {}, true)
        assertThrows(IOException::class.java) { client.begin() }
        assertEquals(1, link.writes.size)
        assertEquals(7, link.writes[0][7].toInt())
        assertTrue(link.closed && client.cleanupConfirmed())
    }

    private class Link : NoAuthVideoTransport {
        val reads = ArrayDeque<ByteArray>()
        val writes = ArrayList<ByteArray>()
        var cleanup = false
        var closed = false
        var failStop = false
        var failBackgroundRestore = false
        var servesLogs = false

        fun productBackground(operation: Int, status: Int, nonce: Int): Link {
            background(operation, status, nonce)
            val tail = ByteBuffer.wrap(reads.peekLast()!!)
            tail.putInt(52, if (status == 1) ProductCompositionSession.NAVIGATION_ACTIVE
            else ProductCompositionSession.NAVIGATION_READY)
            tail.putInt(60, ProductCompositionSession.CAPABILITY)
            return this
        }

        fun background(operation: Int, status: Int, nonce: Int): Link {
            reads.add(NoAuthVideoProtocolTest.header(0x8a, 144, 0, 0))
            val body = ByteBuffer.allocate(144)
            body.putInt(1).putInt(0x42475031).putInt(operation).putInt(0)
            val words = IntArray(32)
            words[0] = 0x31474244; words[1] = 1; words[2] = 128
            words[9] = status; words[10] = 123; words[18] = 1; words[21] = nonce
            words[14] = if (status == 1) 5 else 0
            for (value in words) { body.putInt(value) }
            reads.add(body.array().copyOfRange(0, 80))
            reads.add(body.array().copyOfRange(80, 144))
            return this
        }

        fun offer(): Link { reads.add(NoAuthVideoProtocolTest.header(0x85, 80, 0, 0)); reads.add(ByteArray(80)); return this }

        fun ack(type: Int, sequence: Int, pts: Long, flags: Int, count: Int, lastPts: Long): Link {
            reads.add(NoAuthVideoProtocolTest.header(type or 0x80, 16, sequence, pts))
            reads.add(NoAuthVideoProtocolTest.state(flags, count, lastPts)); return this
        }

        fun ready(): Link = offer().ack(7, 0, 0, 0, 0, 0).ack(1, 0, 0, 1, 0, 0)

        override fun write(data: ByteArray) {
            if (failStop && data[7].toInt() == 3) { throw IOException("test link lost") }
            if (failBackgroundRestore && data[7].toInt() == 10 && data[31].toInt() == 2) {
                throw IOException("test background restore unavailable")
            }
            if (data[7].toInt() == 11 && !servesLogs) { throw IOException("older instrument closes here") }
            writes.add(data.clone())
        }

        override fun readExact(count: Int): ByteArray {
            if (reads.isEmpty()) { throw IOException("test EOF") }
            assertTrue("Above the real transport's read limit", count <= NoAuthVideoProtocol.MAX_REPLY_READ_BYTES)
            val reply = reads.removeFirst()
            assertEquals(count, reply.size)
            return reply
        }

        override fun beginCleanup() { cleanup = true }
        override fun close() { closed = true }
    }

    private fun client(link: Link, cancelled: AtomicBoolean, logs: MutableList<String>): NoAuthVideoClient =
        NoAuthVideoClient(NoAuthVideoClient.Connector { port, flag ->
            assertEquals(DashcastPorts.PROJECTION, port)
            assertSame(cancelled, flag)
            link
        }, cancelled, NoAuthVideoClient.Logger { logs.add(it) }, LongSupplier { 0L })

    @Test
    fun instrumentLogsAreCollectedAfterStop() {
        val link = Link().ready().ack(3, 0, 0, 0, 0, 0)
        link.servesLogs = true
        link.reads.add(NoAuthVideoProtocolTest.header(0x8b, 6, 0, 0))
        link.reads.add("9.8.76".toByteArray(Charsets.UTF_8))
        link.reads.add(NoAuthVideoProtocolTest.header(0x8b, NoAuthVideoProtocol.LOG_TAIL_BYTES, 0, 0))
        val usb = ByteArray(NoAuthVideoProtocol.LOG_TAIL_BYTES)
        usb.fill('x'.code.toByte())
        link.reads.add(usb)
        link.reads.add(NoAuthVideoProtocolTest.header(0x8b, 0, 0, 0))
        val logs = ArrayList<String>()
        val folder = Files.createTempDirectory("instrument-logs").toFile()
        InstrumentLogStore.attach(folder)
        client(link, AtomicBoolean(), logs).use { client ->
            client.begin()
        }
        val savedUsb = InstrumentLogStore.read("usb.log")
        val savedCast = InstrumentLogStore.read("cast.log")
        InstrumentLogStore.attach(null as File?)
        assertTrue(savedUsb.startsWith("# fetched at ") && savedUsb.endsWith("xxx"))
        assertEquals(16384, savedUsb.length - savedUsb.indexOf('\n') - 1)
        assertTrue(savedCast.startsWith("# fetched at "))
        assertEquals(InstrumentLogStore.MISSING, InstrumentLogStore.read("usb.log"))
        assertTrue(link.reads.isEmpty())
        assertEquals(11, link.writes[link.writes.size - 1][7].toInt())
        assertEquals(1, link.writes[link.writes.size - 1][31].toInt())
        assertTrue(logs.any { it == "INSTRUMENT_VERSION 9.8.76" })
        assertTrue(InstrumentVersionMemo.shown().startsWith("9.8.76"))
        assertTrue(logs.contains("INSTRUMENT_LOG usb.log bytes=16384"))
        assertTrue(logs.contains("INSTRUMENT_LOG cast.log bytes=0"))
    }

    @Test
    fun anOlderInstrumentDoesNotFailTheStop() {
        val link = Link().ready().ack(3, 0, 0, 0, 0, 0)
        val logs = ArrayList<String>()
        client(link, AtomicBoolean(), logs).use { client ->
            client.begin()
        }
        assertTrue(logs.any { it.startsWith("STOP_CLEANUP_REPORTED") })
        assertTrue(logs.any { it.startsWith("INSTRUMENT_LOG_UNAVAILABLE") })
    }

    @Test
    fun happyPathUsesOneNoAuthStreamAndReportsWindowNotDecodedFrames() {
        val link = Link().ready().ack(2, 1, 0, 7, 1, 0).ack(2, 2, 33, 23, 2, 33)
            .ack(8, 0, 0, 23, 2, 33).ack(3, 0, 0, 0, 2, 0)
        val logs = ArrayList<String>()
        client(link, AtomicBoolean(), logs).use { client ->
            client.begin()
            client.frame(IDR, 1000000)
            client.frame(P, 1033000)
            client.requireWindowReport()
            assertTrue(client.statistics().contains("acknowledged=2"))
        }
        assertTrue(link.closed && link.cleanup && link.reads.isEmpty())
        assertEquals(6, link.writes.size)
        assertEquals(7, link.writes[0][7].toInt())
        assertEquals(3, link.writes[5][7].toInt())
        assertTrue(logs.any { it.startsWith("STOP_CLEANUP_REPORTED") })
        assertTrue(logs.any { it.contains("NOT observed motion/decoded-frame count") })
    }

    @Test
    fun cancelStillAttemptsSameStreamStopExactlyOnce() {
        val link = Link().ready().ack(3, 0, 0, 0, 0, 0)
        val flag = AtomicBoolean()
        val client = client(link, flag, ArrayList())
        client.begin(); flag.set(true)
        assertThrows(IOException::class.java) { client.frame(IDR, 0) }
        client.close(); client.close()
        assertTrue(link.closed && link.cleanup)
        assertEquals(3, link.writes.size)
    }

    @Test
    fun interruptedWorkerRestoresInterruptAfterCleanup() {
        val link = Link().ready().ack(3, 0, 0, 0, 0, 0)
        val client = client(link, AtomicBoolean(), ArrayList())
        client.begin()
        Thread.currentThread().interrupt()
        try {
            client.close()
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
        assertTrue(link.closed)
    }

    @Test
    fun wrongMagicClosesWithoutSendingOrOpeningWindow() {
        val link = Link().offer(); link.reads.peekFirst()!![1] = 'I'.code.toByte()
        val client = client(link, AtomicBoolean(), ArrayList())
        assertThrows(IOException::class.java) { client.begin() }
        assertTrue(link.closed && link.writes.isEmpty())
        assertThrows(IOException::class.java) { client.begin() }
    }

    @Test
    fun failedStartStillRequestsCleanup() {
        val link = Link().offer().ack(7, 0, 0, 0, 0, 0).ack(1, 0, 0, 0, 0, 0)
            .ack(3, 0, 0, 0, 0, 0)
        val client = client(link, AtomicBoolean(), ArrayList())
        assertThrows(IOException::class.java) { client.begin() }
        assertTrue(link.closed && link.cleanup)
        assertEquals(3, link.writes.size)
    }

    @Test
    fun wrongFrameAckNeverCountsAsSubmitted() {
        val link = Link().ready().ack(2, 2, 0, 23, 2, 0).ack(3, 0, 0, 0, 0, 0)
        client(link, AtomicBoolean(), ArrayList()).use { client ->
            client.begin()
            assertThrows(IOException::class.java) { client.frame(IDR, 0) }
            assertTrue(client.statistics().contains("acknowledged=0"))
        }
    }

    @Test
    fun missingVisibilityReportFailsDespiteSubmittedFrames() {
        val link = Link().ready().ack(2, 1, 0, 3, 1, 0).ack(2, 2, 33, 3, 2, 33)
            .ack(8, 0, 0, 7, 2, 33).ack(8, 0, 0, 7, 2, 33).ack(8, 0, 0, 7, 2, 33)
            .ack(3, 0, 0, 0, 2, 0)
        client(link, AtomicBoolean(), ArrayList()).use { client ->
            client.begin(); client.frame(IDR, 0); client.frame(P, 33000)
            assertThrows(IOException::class.java) { client.requireWindowReport() }
        }
        assertTrue(link.closed)
    }

    @Test
    fun oneFrameCannotConfirmMotionAndStopStateMustBeEmpty() {
        val link = Link().ready().ack(2, 1, 0, 23, 1, 0).ack(3, 0, 0, 1, 1, 0)
        val client = client(link, AtomicBoolean(), ArrayList())
        client.begin(); client.frame(IDR, 0)
        assertThrows(IOException::class.java) { client.requireWindowReport() }
        assertThrows(IOException::class.java) { client.close() }
        assertTrue(link.closed)
        client.close()
    }

    @Test
    fun linkLossAtStopIsNotSwallowedOrReportedClean() {
        val link = Link().ready(); link.failStop = true
        val logs = ArrayList<String>()
        val client = client(link, AtomicBoolean(), logs)
        client.begin()
        assertThrows(IOException::class.java) { client.close() }
        assertTrue(link.closed)
        assertTrue(logs.any { it.startsWith("STOP_UNCONFIRMED") })
        assertFalse(logs.any { it.startsWith("STOP_CLEANUP_REPORTED") })
    }

    @Test
    fun repeatedBeginAndInvalidPtsAreRejectedBeforeSending() {
        val link = Link().ready().ack(2, 1, 0, 23, 1, 0).ack(3, 0, 0, 0, 1, 0)
        client(link, AtomicBoolean(), ArrayList()).use { client ->
            assertThrows(IOException::class.java) { client.frame(IDR, 0) }
            client.begin()
            assertThrows(IOException::class.java) { client.begin() }
            client.frame(IDR, 1000000)
            for (pts in longArrayOf(-1, 999999, 3001000)) {
                assertThrows(IOException::class.java) { client.frame(P, pts) }
            }
        }
    }

    @Test
    fun closedOrCancelledNewSessionNeverConnects() {
        val link = Link()
        val client = client(link, AtomicBoolean(true), ArrayList())
        assertThrows(IOException::class.java) { client.begin() }
        client.close()
        assertFalse(link.closed)
        assertThrows(IOException::class.java) { client.requireWindowReport() }
    }

    @Test
    fun stagedErrorWithConfirmedCleanupDoesNotStopAnAlreadyClosedPeer() {
        for (code in intArrayOf(2, 3, 4, 5)) {
            val link = Link().ready()
            link.reads.add(NoAuthVideoProtocolTest.header(0x82, 16, 1, 0))
            val error = NoAuthVideoProtocolTest.state(0, 0, 0); error[3] = code.toByte(); link.reads.add(error)
            val logs = ArrayList<String>()
            client(link, AtomicBoolean(), logs).use { client ->
                client.begin()
                val rejected = assertThrows(IOException::class.java) { client.frame(IDR, 0) }
                assertTrue(rejected.message!!.contains("flags=0 submissions=0"))
                assertThrows(IOException::class.java) { client.frame(IDR, 1) }
            }
            assertEquals(3, link.writes.size)
            assertTrue(link.closed)
            assertTrue(logs.any { it.startsWith("ERROR_CLEANUP_REPORTED") })
        }
    }

    @Test
    fun failedCleanupAndLegacyErrorCannotSkipStopOrFakeClean() {
        for (rejected in arrayOf(intArrayOf(2, 8), intArrayOf(1, 0), intArrayOf(3, 1))) {
            val link = Link().ready()
            link.reads.add(NoAuthVideoProtocolTest.header(0x82, 16, 1, 0))
            val error = NoAuthVideoProtocolTest.state(rejected[1], 0, 0); error[3] = rejected[0].toByte()
            link.reads.add(error)
            link.ack(3, 0, 0, 0, 0, 0)
            val logs = ArrayList<String>()
            client(link, AtomicBoolean(), logs).use { client ->
                client.begin(); assertThrows(IOException::class.java) { client.frame(IDR, 0) }
            }
            assertEquals(4, link.writes.size)
            assertFalse(logs.any { it.startsWith("ERROR_CLEANUP_REPORTED") })
        }
    }

    private companion object {
        fun f1State(kind: Int, owner: Int, id: Int, status: Int, flags: Int): ByteArray =
            ByteBuffer.allocate(40).putInt(1).putInt(0x46315031).putInt(status)
                .putInt(kind).putInt(owner).putInt(id).putInt(0).putInt(0).putInt(flags).putInt(0).array()
    }
}
