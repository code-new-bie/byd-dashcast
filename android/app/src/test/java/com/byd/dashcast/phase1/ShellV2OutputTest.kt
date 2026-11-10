package com.byd.dashcast.phase1

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.function.Consumer

/** Binary video never contains shell-v2 STDERR/EXIT framing, even across arbitrary ADB chunks. */
class ShellV2OutputTest {
    @Test
    fun idleConsumerFailureStillAllowsOwnedExitDrainAndPreservesCause() {
        val cause = IOException("heartbeat ACK missing")
        val decoder = ShellV2Output(object : AdbShellClient.ShellOutputHandler {
            override fun onOutput(data: ByteArray?, length: Int) { failUnexpectedOutput() }
            override fun onIdle() { throw cause }
            private fun failUnexpectedOutput() { throw AssertionError("draining must skip video") }
        }, Consumer {}, true)
        decoder.onIdle()
        assertTrue(decoder.consumerFailed())
        val wire = join(frame(1, byteArrayOf(1)), frame(3, byteArrayOf(0)))
        decoder.onOutput(wire, wire.size)
        assertTrue(decoder.exitConfirmed())
        assertSame(cause, assertThrows(IOException::class.java) { decoder.requireSuccess() })
    }

    @Test
    fun controlledSinkFailureDrainsDiagnosticsAndExitWithoutDeliveringMoreVideo() {
        val rejected = IOException("MEDIA_SUBMIT rejected")
        val logs = ArrayList<String>()
        val calls = intArrayOf(0)
        val decoder = ShellV2Output(AdbShellClient.ShellOutputHandler { _, _ ->
            calls[0]++
            throw rejected
        }, Consumer { logs.add(it) }, true)
        val wire = join(frame(1, byteArrayOf(1)), frame(1, byteArrayOf(2)),
            frame(2, "SHELL_CAPTURE_RESOURCES_RELEASED\n".toByteArray(Charsets.UTF_8)),
            frame(3, byteArrayOf(0)))
        for (value in wire) {
            decoder.onOutput(byteArrayOf(value), 1)
        }
        assertEquals(1, calls[0])
        assertTrue(decoder.consumerFailed())
        assertTrue(decoder.exitConfirmed())
        assertSame(rejected, assertThrows(IOException::class.java) { decoder.requireSuccess() })
        assertTrue(logs[0].contains("SHELL_CAPTURE_RESOURCES_RELEASED"))
        assertTrue(decoder.completionStatus().contains("consumer_failed=true"))
    }

    @Test
    fun controlledFailureStillRejectsTruncatedExitAndMalformedShellFraming() {
        val decoder = ShellV2Output(AdbShellClient.ShellOutputHandler { _, _ ->
            throw IOException("DCS1_WRONG_CAPTURE_PROTOCOL")
        }, Consumer {}, true)
        val wire = join(frame(1, byteArrayOf(1)), header(3, 1))
        decoder.onOutput(wire, wire.size)
        assertFalse(decoder.exitConfirmed())
        assertThrows(IOException::class.java) { decoder.requireSuccess() }
        assertThrows(IOException::class.java) { decoder.onOutput(byteArrayOf(0, 1), 2) }
        assertFalse(decoder.exitConfirmed())
    }

    @Test
    fun controlledRuntimeFailureKeepsCauseAndStillDrainsNonzeroExit() {
        val cause = IllegalStateException("sink unavailable")
        val decoder = ShellV2Output(AdbShellClient.ShellOutputHandler { _, _ -> throw cause }, Consumer {}, true)
        val wire = join(frame(1, byteArrayOf(1)), frame(3, byteArrayOf(1)))
        decoder.onOutput(wire, wire.size)
        assertTrue(decoder.exitConfirmed())
        assertSame(cause, assertThrows(IOException::class.java) { decoder.requireSuccess() }.cause)
    }

    @Test
    fun nonzeroExitIsConfirmedTerminationButStillCommandFailure() {
        val decoder = ShellV2Output(AdbShellClient.ShellOutputHandler { _, _ -> })
        val wire = frame(3, byteArrayOf(1))
        decoder.onOutput(wire, wire.size)
        assertTrue(decoder.exitConfirmed())
        assertThrows(IOException::class.java) { decoder.requireSuccess() }
    }

    @Test
    fun keepsCleanupEvidenceAtTheTailOfLongDiagnostics() {
        val logs = ArrayList<String>()
        val decoder = ShellV2Output(AdbShellClient.ShellOutputHandler { _, _ -> }, Consumer { logs.add(it) })
        val stderr = ByteArray(9000)
        stderr.fill('x'.code.toByte())
        val marker = "\nSHELL_CAPTURE_RESOURCES_RELEASED\n".toByteArray(Charsets.UTF_8)
        val wire = join(frame(2, stderr), frame(2, marker), frame(3, byteArrayOf(0)))
        decoder.onOutput(wire, wire.size)
        decoder.requireSuccess()
        assertTrue(logs[0].contains("SHELL_CAPTURE_RESOURCES_RELEASED"))
        assertTrue(logs[0].length < 4300)
    }

    @Test
    fun preservesBinaryStdoutAndDiscardsStderrAcrossSingleByteChunks() {
        val raw = ByteArrayOutputStream()
        val decoder = ShellV2Output(AdbShellClient.ShellOutputHandler { bytes, length -> raw.write(bytes!!, 0, length) })
        val stdout = byteArrayOf(0, 0, 0, 1, 0x65, 0xff.toByte(), 0, 0, 3, 1)
        val wire = join(frame(1, stdout), frame(2, byteArrayOf(99, 98, 97)), frame(3, byteArrayOf(0)))
        for (value in wire) {
            decoder.onOutput(byteArrayOf(value), 1)
        }
        decoder.requireSuccess()
        assertArrayEquals(stdout, raw.toByteArray())
        assertEquals(3, decoder.stderrBytes())
    }

    @Test
    fun eofAndNonzeroExitAreNotSuccessfulCapture() {
        val missing = ShellV2Output(AdbShellClient.ShellOutputHandler { _, _ -> })
        missing.onOutput(frame(1, byteArrayOf(1)), 6)
        assertThrows(IOException::class.java) { missing.requireSuccess() }
        val failure = ShellV2Output(AdbShellClient.ShellOutputHandler { _, _ -> })
        failure.onOutput(frame(3, byteArrayOf(7)), 6)
        assertThrows(IOException::class.java) { failure.requireSuccess() }
    }

    @Test
    fun truncatedHeaderOrPayloadCannotBecomeZeroExit() {
        for (wire in arrayOf(byteArrayOf(1, 4), header(1, 4), join(header(1, 4), byteArrayOf(0, 0)))) {
            val decoder = ShellV2Output(AdbShellClient.ShellOutputHandler { _, _ -> })
            decoder.onOutput(wire, wire.size)
            assertThrows(IOException::class.java) { decoder.requireSuccess() }
        }
    }

    @Test
    fun invalidChannelOversizeUnsignedLengthAndWrongExitLengthAreRejected() {
        for (wire in arrayOf(header(0, 1), header(5, 1), header(1, 1_048_577),
            header(2, -1), header(3, 0), header(3, 4))) {
            val decoder = ShellV2Output(AdbShellClient.ShellOutputHandler { _, _ -> })
            assertThrows(IOException::class.java) { decoder.onOutput(wire, wire.size) }
            assertThrows(IOException::class.java) { decoder.requireSuccess() }
        }
    }

    @Test
    fun duplicateExitAndDataAfterExitInvalidateTheRun() {
        val decoder = ShellV2Output(AdbShellClient.ShellOutputHandler { _, _ -> })
        val exit = frame(3, byteArrayOf(0))
        decoder.onOutput(exit, exit.size)
        decoder.requireSuccess()
        assertThrows(IOException::class.java) { decoder.onOutput(exit, exit.size) }
        assertThrows(IOException::class.java) { decoder.requireSuccess() }
    }

    @Test
    fun zeroLengthOutputFramesAndMultipleFramesPerChunkAreValid() {
        val raw = ByteArrayOutputStream()
        val decoder = ShellV2Output(AdbShellClient.ShellOutputHandler { bytes, length -> raw.write(bytes!!, 0, length) })
        val wire = join(header(1, 0), header(2, 0), frame(1, byteArrayOf(4)), frame(3, byteArrayOf(0)))
        decoder.onOutput(wire, wire.size)
        decoder.requireSuccess()
        assertArrayEquals(byteArrayOf(4), raw.toByteArray())
    }

    @Test
    fun invalidChunksAndObserverFailureAreNotSilentlyIgnored() {
        val decoder = ShellV2Output(AdbShellClient.ShellOutputHandler { _, _ -> throw IOException("sink failed") })
        assertThrows(IllegalArgumentException::class.java) { decoder.onOutput(null, 0) }
        assertThrows(IllegalArgumentException::class.java) { decoder.onOutput(ByteArray(1), 2) }
        val wire = frame(1, byteArrayOf(9))
        assertThrows(IOException::class.java) { decoder.onOutput(wire, wire.size) }
        assertThrows(IOException::class.java) { decoder.requireSuccess() }
    }

    @Test
    fun requestedCaptureDiagnosticsAreBoundedAndNeverEnterVideo() {
        val video = ByteArrayOutputStream()
        val logs = ArrayList<String>()
        val decoder = ShellV2Output(AdbShellClient.ShellOutputHandler { bytes, length -> video.write(bytes!!, 0, length) },
            Consumer { logs.add(it) })
        val text = ByteArray(5000); text.fill('x'.code.toByte())
        val wire = join(frame(2, text), frame(1, byteArrayOf(0, 0, 1)), frame(3, byteArrayOf(0)))
        decoder.onOutput(wire, wire.size); decoder.requireSuccess()
        assertArrayEquals(byteArrayOf(0, 0, 1), video.toByteArray())
        assertEquals(5000, decoder.stderrBytes())
        assertEquals(1, logs.size)
        assertTrue(logs[0].contains("[stderr middle truncated]"))
        assertTrue(logs[0].length < 4300)
    }

    @Test
    fun sinkFailureKeepsPreviouslyReceivedStderrAndReportsItOnlyOnce() {
        val logs = ArrayList<String>()
        val decoder = ShellV2Output(AdbShellClient.ShellOutputHandler { _, _ ->
            throw IOException("instrument rejected frame")
        }, Consumer { logs.add(it) })
        val wire = join(frame(2, byteArrayOf('o'.code.toByte(), 'k'.code.toByte())), frame(1, byteArrayOf(1)))
        assertThrows(IOException::class.java) { decoder.onOutput(wire, wire.size) }
        decoder.reportDiagnostics()
        assertThrows(IOException::class.java) { decoder.requireSuccess() }
        decoder.reportDiagnostics()
        assertEquals(listOf("ADB_CAPTURE_STDERR ok"), logs)
    }

    private companion object {
        fun frame(channel: Int, payload: ByteArray): ByteArray = join(header(channel, payload.size), payload)

        fun header(channel: Int, length: Int): ByteArray =
            ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).put(channel.toByte()).putInt(length).array()

        fun join(vararg values: ByteArray): ByteArray {
            val output = ByteArrayOutputStream()
            for (value in values) {
                output.write(value, 0, value.size)
            }
            return output.toByteArray()
        }
    }
}
