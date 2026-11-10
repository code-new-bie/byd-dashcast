package com.byd.dashcast.phase1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.util.function.LongSupplier

/** Deterministic same-stream control tests; not QNX physical composition evidence. */
class InstrumentCompositionTrialTest {
    @Test
    fun firstPictureStartsOneLeaseAndExpiryStopsWithoutRenewal() {
        val model = Model()
        val trial = model.trial()
        trial.prepare(model)
        assertEquals(listOf(0), model.operations)
        trial.activate(model)
        trial.tick(model)
        model.now += 5_000_000_000L
        model.state = 2
        trial.tick(model)
        trial.restore(model)
        assertTrue(model.stopped)
        assertEquals(1, model.operations.count { it == 1 })
        assertFalse(model.operations.contains(2))
        assertThrows(IOException::class.java) { trial.activate(model) }
    }

    @Test
    fun earlyStopRestoresOnlyOwnedNonce() {
        val model = Model()
        val trial = model.trial()
        trial.prepare(model)
        trial.activate(model)
        trial.restore(model)
        trial.restore(model)
        assertEquals(listOf(0, 1, 2, 0), model.operations)
        assertFalse(model.stopped)
    }

    @Test
    fun preexistingLeaseOrPendingRequestNeverSendsStart() {
        for (pending in booleanArrayOf(false, true)) {
            val model = Model()
            model.state = if (pending) 0 else 1
            model.pending = pending
            assertThrows(IOException::class.java) { model.trial().prepare(model) }
            assertEquals(listOf(0), model.operations)
        }
    }

    @Test
    fun hmiReplacementOrMissingDrawingNeverReportsSuccess() {
        for (replacement in booleanArrayOf(false, true)) {
            val model = Model()
            val trial = model.trial()
            trial.prepare(model)
            trial.activate(model)
            trial.tick(model)
            model.now += 5_000_000_000L
            model.state = 2
            if (replacement) { model.pid++ } else { model.skips = 0 }
            assertThrows(IOException::class.java) { trial.tick(model) }
            assertFalse(model.stopped)
        }
    }

    @Test
    fun sevenSecondBoundAndFailedRestoreAreNotSuccess() {
        val model = Model()
        val trial = model.trial()
        trial.prepare(model)
        trial.activate(model)
        model.now += 7_000_000_001L
        assertThrows(IOException::class.java) { trial.tick(model) }
        model.error = 3
        assertThrows(IOException::class.java) { trial.restore(model) }
        assertFalse(model.stopped)
    }

    @Test
    fun busySnapshotDoesNotCauseStartReplay() {
        val model = Model()
        val trial = model.trial()
        trial.prepare(model)
        model.error = 9
        trial.activate(model)
        trial.tick(model)
        model.error = 0
        model.now += 100_000_000L
        trial.tick(model)
        model.now += 5_000_000_000L
        model.state = 2
        trial.tick(model)
        assertTrue(model.stopped)
        assertEquals(1, model.operations.count { it == 1 })
    }

    private class Model : NoAuthVideoTransport {
        val operations = ArrayList<Int>()
        var now = 100_000_000_000L
        var state = 0
        var nonce = 0
        var pid = 123
        var skips = 2
        var error = 0
        var pending = false
        var stopped = false
        var response = ByteArray(0)
        var offset = 0

        fun trial(): InstrumentCompositionTrial =
            InstrumentCompositionTrial(NoAuthVideoClient.Logger {}, LongSupplier { now }, 77, Runnable { stopped = true })

        override fun write(data: ByteArray) {
            val request = ByteBuffer.wrap(data)
            assertEquals(10, request.getInt(4))
            val op = request.getInt(28)
            operations.add(op)
            if (op == 1) {
                nonce = request.getInt(32)
                assertEquals(77, nonce)
                assertEquals(5000, request.getInt(36))
                state = 1
            } else if (op == 2) {
                assertEquals(nonce, request.getInt(32))
                state = 2
            }
            val control = IntArray(32)
            control[0] = 0x31474244; control[1] = 1; control[2] = 128
            control[3] = 2; control[8] = if (pending) 0 else 2; control[9] = state
            control[10] = pid; control[12] = (now / 1_000_000_000L).toInt()
            control[14] = control[12] + 5; control[18] = 1; control[21] = nonce
            control[16] = if (nonce == 0) 0 else skips; control[17] = control[16]
            val output = ByteBuffer.allocate(168)
            output.putInt(0x44435631).putInt(0x8a).putInt(144).putInt(0).putInt(0).putInt(0)
            output.putInt(1).putInt(0x42475031).putInt(op).putInt(error)
            for (value in control) { output.putInt(value) }
            response = output.array(); offset = 0
        }

        override fun readExact(count: Int): ByteArray {
            val part = response.copyOfRange(offset, offset + count)
            offset += count
            return part
        }

        override fun beginCleanup() {}
        override fun close() {}
    }
}
