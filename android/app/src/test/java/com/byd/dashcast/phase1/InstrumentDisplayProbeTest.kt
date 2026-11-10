package com.byd.dashcast.phase1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.LongSupplier

/** @author Codex @date 2026/10/19 */
class InstrumentDisplayProbeTest {
    @Test
    fun requestRejectsEveryPathEscapeAndInvalidRange() {
        assertEquals(44, InstrumentDisplayProbe.request(0, 0, 0, 0).size)
        assertEquals(44, InstrumentDisplayProbe.request(2, 0, 65535, 3).size)
        for (row in arrayOf(intArrayOf(0, 1, 0, 0), intArrayOf(0, 0, 0, 1), intArrayOf(1, 1, 2, 0),
            intArrayOf(1, 0, 0, 0), intArrayOf(1, 0, 9, 0), intArrayOf(2, -1, 1, 0), intArrayOf(2, 1, -1, 0),
            intArrayOf(3, 1, 65536, 0), intArrayOf(4, 0, 0, 0), intArrayOf(2, 1, 1, 4))) {
            assertThrows(IOException::class.java) { InstrumentDisplayProbe.request(row[0], row[1], row[2], row[3]) }
        }
    }

    @Test
    fun collectsDisplayWindowAndStreamWithoutStartOrStop() {
        val fake = FakeTransport()
        fake.display = "dpy-2\n win-3 ctx-0 ssn-0 pid=100(rc_hmi.exe)\n" +
            " win-4 ctx-0 ssn-0 pid=200(dashcast-receiver)\n"
        val logs = ArrayList<String>()
        val probe = InstrumentDisplayProbe(AtomicBoolean(), NoAuthVideoClient.Logger { logs.add(it) }, LongSupplier { 0L })
        probe.negotiate(fake)
        probe.run(fake)
        assertTrue(logs.any { it.contains("owner_pid=100 object=3 pages=1") })
        assertTrue(logs.any { it.contains("kind=3 owner_pid=100 object=5 pages=1") })
        assertTrue(logs.any { it.contains("COLLECTION_ENDED") })
        assertTrue(logs.none { it.contains("PASS") })
        assertTrue(fake.operations.all { it == 9 })
        assertFalse(fake.closed)
    }

    @Test
    fun splitUtf8AcrossPagesIsDecodedOnlyAfterCollection() {
        val fake = FakeTransport()
        fake.display = "x".repeat(4095) + "仪表"
        val logs = ArrayList<String>()
        val probe = InstrumentDisplayProbe(AtomicBoolean(), NoAuthVideoClient.Logger { logs.add(it) }, LongSupplier { 0L })
        probe.negotiate(fake)
        probe.run(fake)
        assertTrue(logs.any { it.contains("仪表") })
    }

    @Test
    fun truncationNeverClaimsCompleteEvidence() {
        val fake = FakeTransport()
        fake.display = "x".repeat(17000)
        val logs = ArrayList<String>()
        val probe = InstrumentDisplayProbe(AtomicBoolean(), NoAuthVideoClient.Logger { logs.add(it) }, LongSupplier { 0L })
        probe.negotiate(fake)
        probe.run(fake)
        assertTrue(logs.any { it.contains("truncated=true") })
        assertTrue(logs.any { it.contains("partial=1") })
    }

    @Test
    fun rejectsMalformedHeaderSchemaEchoAndFlags() {
        for (mode in 1..7) {
            val fake = FakeTransport()
            fake.corruption = mode
            val probe = InstrumentDisplayProbe(AtomicBoolean(), NoAuthVideoClient.Logger { }, LongSupplier { 0L })
            assertThrows(IOException::class.java) { probe.negotiate(fake) }
        }
    }

    @Test
    fun missingOrDeniedFilesAreNotReportedAsPass() {
        val fake = FakeTransport()
        fake.allMissing = true
        val logs = ArrayList<String>()
        val probe = InstrumentDisplayProbe(AtomicBoolean(), NoAuthVideoClient.Logger { logs.add(it) }, LongSupplier { 0L })
        probe.negotiate(fake)
        probe.run(fake)
        assertTrue(logs.any { it.contains("files=0 unavailable=8") })
        assertTrue(logs.none { it.contains("PASS") })
    }

    @Test
    fun readFailureAndUnknownCloseAreTerminal() {
        for (code in intArrayOf(3, 4, 5, 6)) {
            val fake = FakeTransport()
            val probe = InstrumentDisplayProbe(AtomicBoolean(), NoAuthVideoClient.Logger { }, LongSupplier { 0L })
            probe.negotiate(fake)
            fake.failure = code
            assertThrows(IOException::class.java) { probe.step(fake) }
        }
    }

    @Test
    fun cancellationAndClockBudgetDoNotIssueFurtherRequests() {
        val fake = FakeTransport()
        val cancel = AtomicBoolean()
        val clock = longArrayOf(0)
        val logs = ArrayList<String>()
        val probe = InstrumentDisplayProbe(cancel, NoAuthVideoClient.Logger { logs.add(it) }, LongSupplier { clock[0] })
        probe.negotiate(fake)
        cancel.set(true)
        assertThrows(IOException::class.java) { probe.step(fake) }
        cancel.set(false)
        clock[0] = 10_000_000_000L
        probe.step(fake)
        assertEquals(1, fake.operations.size)
        assertTrue(logs.any { it.contains("BUDGET_LIMIT") })
        assertThrows(IOException::class.java) { probe.negotiate(fake) }
    }

    @Test
    fun duplicateWindowsAreReadOnceAndIdOverflowFails() {
        val fake = FakeTransport()
        fake.display = " win-3 ctx-0 pid=100(hmi)\n win-3 ctx-0 pid=100(hmi)\n"
        val probe = InstrumentDisplayProbe(AtomicBoolean(), NoAuthVideoClient.Logger { }, LongSupplier { 0L })
        probe.negotiate(fake)
        probe.run(fake)
        assertEquals(1L, fake.resources.count { it == "2/100/3/0" }.toLong())
        val overflow = FakeTransport()
        overflow.display = " win-3 ctx-0 pid=999999999999999(hmi)\n"
        val bad = InstrumentDisplayProbe(AtomicBoolean(), NoAuthVideoClient.Logger { }, LongSupplier { 0L })
        bad.negotiate(overflow)
        bad.step(overflow)
        assertThrows(IOException::class.java) { bad.step(overflow) }
    }

    @Test
    fun activeFrameSessionCanEndWithPartialReadback() {
        val logs = ArrayList<String>()
        val probe = InstrumentDisplayProbe(AtomicBoolean(), NoAuthVideoClient.Logger { logs.add(it) }, LongSupplier { 0L })
        probe.negotiate(FakeTransport())
        probe.end()
        assertTrue(logs.any { it.contains("VIDEO_ENDED_BEFORE_COLLECTION") })
    }

    internal class FakeTransport : NoAuthVideoTransport {
        private var reply = ByteArray(0)
        private var offset = 0
        var display = "dpy-2\n"
        var corruption = 0
        var failure = 0
        var allMissing = false
        var closed = false
        val operations = ArrayList<Int>()
        val resources = ArrayList<String>()

        override fun write(data: ByteArray) {
            val wire = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            wire.position(4)
            operations.add(wire.getInt())
            wire.position(28)
            val kind = wire.getInt()
            val owner = wire.getInt()
            val id = wire.getInt()
            val page = wire.getInt()
            resources.add("$kind/$owner/$id/$page")
            val text = when (kind) {
                1 -> display
                2 -> "id string = Kanzi\nstream = str-5\n"
                else -> "format = RGBA8888\n"
            }
            val bytes = text.toByteArray(Charsets.UTF_8)
            val status = if (kind == 0) 0 else if (failure != 0) failure
            else if (allMissing || (kind == 1 && id != 2)) 2 else 0
            val start = minOf(bytes.size, page * 4096)
            val part = if (kind == 0 || status != 0) ByteArray(0)
            else bytes.copyOfRange(start, minOf(bytes.size, start + 4096))
            val flags = if (kind == 0) 1 else if (status != 0) 0 else if (part.size < 4096) 1 else if (page == 3) 2 else 0
            val response = ByteBuffer.allocate(64 + part.size).order(ByteOrder.BIG_ENDIAN)
            response.putInt(if (corruption == 1) 0 else 0x44435631).putInt(0x89)
            response.putInt(if (corruption == 2) 1000000 else 40 + part.size).putInt(0).putInt(0).putInt(0)
            response.putInt(if (corruption == 3) 2 else 1).putInt(0x46315031).putInt(status)
            response.putInt(kind).putInt(if (corruption == 4) owner + 1 else owner).putInt(id).putInt(page)
            response.putInt(if (corruption == 5) part.size + 1 else part.size)
            response.putInt(if (corruption == 6) 3 else flags).putInt(if (corruption == 7) 1 else 0).put(part)
            reply = response.array()
            offset = 0
        }

        override fun readExact(count: Int): ByteArray {
            assertTrue(count <= 80)
            if (offset + count > reply.size) {
                throw IOException("truncated")
            }
            val result = reply.copyOfRange(offset, offset + count)
            offset += count
            return result
        }

        override fun beginCleanup() {
        }

        override fun close() {
            closed = true
        }
    }
}
