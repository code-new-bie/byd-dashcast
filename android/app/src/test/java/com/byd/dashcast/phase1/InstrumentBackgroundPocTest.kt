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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.LongSupplier

/** Deterministic protocol/state tests, not Android/QNX/visual execution. */
class InstrumentBackgroundPocTest {
    @Test
    fun requestIsFixedFiveSecondsAndNoVideoStart() {
        val packet = InstrumentBackgroundPoc.request(1, 77)
        assertArrayEquals(intArrayOf(0x44435631, 10, 16, 0, 0, 0, 1, 1, 77, 5000), words(packet))
        assertArrayEquals(intArrayOf(0x44435631, 10, 16, 0, 0, 0, 1, 0, 0, 0),
            words(InstrumentBackgroundPoc.request(0, 0)))
    }

    @Test
    fun invalidRequestRangesRejected() {
        for (row in arrayOf(intArrayOf(-1, 0), intArrayOf(3, 1), intArrayOf(0, 1), intArrayOf(1, 0), intArrayOf(2, -1))) {
            assertThrows(IOException::class.java) { InstrumentBackgroundPoc.request(row[0], row[1]) }
        }
    }

    @Test
    fun independentExpiryObservedWithoutRenewalOrExtraStop() {
        val model = Model()
        val probe = probe(model)
        probe.run(model)
        assertEquals(1, model.operations.count { it == 1 })
        assertFalse(model.operations.contains(2))
        assertEquals(1, model.maxActive)
        assertTrue(model.logs.any { it.contains("BG_POC_NATIVE_RESTORED") })
        assertThrows(IOException::class.java) { probe.run(model) }
    }

    @Test
    fun missingHooksDoesNotReportFeaturePass() {
        val model = Model()
        model.draws = false
        val error = assertThrows(IOException::class.java) { probe(model).run(model) }
        assertTrue(error.message!!.contains("DRAW_NOT_OBSERVED"))
        assertEquals(1, model.operations.count { it == 1 })
    }

    @Test
    fun preexistingActiveNeverSendsStartOrRestore() {
        val model = Model()
        model.control[9] = 1
        model.deadline = model.now + 5_000_000_000L
        assertThrows(IOException::class.java) { probe(model).run(model) }
        assertEquals(listOf(0), model.operations)
    }

    @Test
    fun notReadyTimesOutWithoutSendingStart() {
        val model = Model()
        model.serverError = 3
        assertThrows(IOException::class.java) { probe(model).run(model) }
        assertFalse(model.operations.contains(1))
        assertFalse(model.operations.contains(2))
        assertTrue(model.operations.size <= 32)
    }

    @Test
    fun cancellationSendsOnlyOwnedRestoreWithoutReplayingStart() {
        val model = Model()
        model.cancelAfterStart = true
        assertThrows(IOException::class.java) { probe(model).run(model) }
        assertTrue(model.cleanup)
        assertEquals(1, model.operations.count { it == 1 })
        assertEquals(1, model.operations.count { it == 2 })
    }

    @Test
    fun changedHmiEpochAbortsAndAttemptsOwnedRestore() {
        val model = Model()
        model.changeEpoch = true
        val error = assertThrows(IOException::class.java) { probe(model).run(model) }
        assertTrue(error.message!!.contains("CHANGED_OR_UNAVAILABLE"))
        assertTrue(model.operations.contains(2))
    }

    @Test
    fun malformedEnvelopeAndSnapshotRejected() {
        val model = Model()
        model.write(InstrumentBackgroundPoc.request(0, 0))
        val header = model.readExact(24)
        InstrumentBackgroundPoc.validateHeader(header)
        for (offset in intArrayOf(0, 7, 11, 15, 19, 23)) {
            val bad = header.clone(); bad[offset] = (bad[offset].toInt() xor 1).toByte()
            assertThrows(IOException::class.java) { InstrumentBackgroundPoc.validateHeader(bad) }
        }
        val body = model.readExact(80)
        val tail = model.readExact(64)
        val full = ByteArray(144)
        System.arraycopy(body, 0, full, 0, 80)
        System.arraycopy(tail, 0, full, 80, 64)
        InstrumentBackgroundPoc.parseReply(full, 0)
        for (word in intArrayOf(0, 1, 2, 4, 5, 6)) {
            val bad = full.clone(); bad[word * 4] = (bad[word * 4].toInt() xor 1).toByte()
            assertThrows(IOException::class.java) { InstrumentBackgroundPoc.parseReply(bad, 0) }
        }
        assertThrows(IOException::class.java) { InstrumentBackgroundPoc.parseReply(ByteArray(145), 0) }
    }

    @Test
    fun uncertainStartSnapshotQueriesInsteadOfRetryingStart() {
        val model = Model()
        model.startSnapshotBusy = true
        probe(model).run(model)
        assertEquals(1, model.operations.count { it == 1 })
    }

    @Test
    fun frozenHmiNeverBecomesSuccessAndRequestCountIsBounded() {
        val model = Model()
        model.freeze = true
        assertThrows(IOException::class.java) { probe(model).run(model) }
        assertTrue(model.operations.size <= 101)
        assertTrue(model.operations.contains(2))
    }

    private class Model : NoAuthVideoTransport {
        val cancelled = AtomicBoolean()
        val operations = ArrayList<Int>()
        val logs = ArrayList<String>()
        val control = IntArray(32)
        var reply = ByteArray(0)
        var offset = 0
        var serverError = 0
        var maxActive = 0
        var now = 100_000_000_000L
        var deadline = 0L
        var draws = true
        var cleanup = false
        var cancelAfterStart = false
        var changeEpoch = false
        var freeze = false
        var startSnapshotBusy = false

        init {
            control[0] = 0x31474244; control[1] = 1; control[2] = 128
            control[10] = 123; control[18] = 1
        }

        override fun write(data: ByteArray) {
            val request = words(data)
            assertEquals(10, request[1])
            val operation = request[7]
            operations.add(operation)
            if (operation == 1) {
                control[3] += 2; control[8] = control[3]; control[9] = 1; control[21] = request[8]
                deadline = now + 5_000_000_000L
            } else if (operation == 2) {
                control[9] = 2
            }
            if (operation == 0 && control[9] == 1 && !freeze) {
                if (now >= deadline) {
                    control[9] = 2; control[25] = control[24]
                } else if (draws) {
                    control[16]++; control[17]++
                }
                if (changeEpoch) { control[18]++ }
            }
            if (control[9] == 1) {
                maxActive = 1
                control[14] = (deadline / 1_000_000_000L).toInt()
                control[15] = (deadline % 1_000_000_000L).toInt()
            }
            control[12] = (now / 1_000_000_000L).toInt()
            control[13] = (now % 1_000_000_000L).toInt()
            control[24]++
            val error = if (operation == 1 && startSnapshotBusy) 9 else serverError
            val buffer = ByteBuffer.allocate(168).order(ByteOrder.BIG_ENDIAN)
            buffer.putInt(0x44435631).putInt(0x8a).putInt(144).putInt(0).putInt(0).putInt(0)
            buffer.putInt(1).putInt(0x42475031).putInt(operation).putInt(error)
            for (value in control) { buffer.putInt(value) }
            reply = buffer.array(); offset = 0
        }

        override fun readExact(count: Int): ByteArray {
            assertTrue(count <= 80)
            val piece = reply.copyOfRange(offset, offset + count)
            offset += count
            return piece
        }

        override fun beginCleanup() { cleanup = true }

        override fun close() { }
    }

    private companion object {
        fun probe(model: Model): InstrumentBackgroundPoc =
            InstrumentBackgroundPoc(model.cancelled, NoAuthVideoClient.Logger { model.logs.add(it) }, 77,
                LongSupplier { model.now }, InstrumentBackgroundPoc.Waiter { delta ->
                    model.now += delta
                    if (model.cancelAfterStart && model.operations.contains(1)) {
                        model.cancelled.set(true)
                    }
                })

        fun words(bytes: ByteArray): IntArray {
            val values = IntArray(bytes.size / 4)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            for (i in values.indices) {
                values[i] = buffer.getInt()
            }
            return values
        }
    }
}
