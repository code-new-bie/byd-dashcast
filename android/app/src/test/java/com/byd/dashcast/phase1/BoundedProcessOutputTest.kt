package com.byd.dashcast.phase1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** Owned-child timeout and output limits without invoking Android or a real shell. */
class BoundedProcessOutputTest {
    @Test
    fun drainsAndBoundsSuccessfulOutput() {
        val process = FakeProcess(true, ByteArray(32 * 1024))
        assertEquals(16 * 1024, BoundedProcessOutput.read(process, 1000).length)
        assertFalse(process.killed)
        assertTrue(process.closed)
    }

    @Test
    fun timeoutKillsOnlyTheOwnedChildAndConfirmsItsExit() {
        val process = FakeProcess(false, ByteArray(0))
        val error = assertThrows(IOException::class.java) { BoundedProcessOutput.read(process, 1) }
        assertTrue(error.message!!.contains("APP_START_TIMEOUT"))
        assertTrue(process.killed)
        assertFalse(process.isAlive())
        assertTrue(process.closed)
    }

    private class FakeProcess(private var ended: Boolean, bytes: ByteArray) : Process() {
        var killed = false
        var closed = false
        private val input: InputStream = object : ByteArrayInputStream(bytes) {
            override fun close() {
                closed = true
            }
        }

        override fun getInputStream(): InputStream = input

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun waitFor(): Int = 0

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = ended

        override fun exitValue(): Int = 0

        override fun isAlive(): Boolean = !ended

        override fun destroy() {
            ended = true
            killed = true
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
    }
}
