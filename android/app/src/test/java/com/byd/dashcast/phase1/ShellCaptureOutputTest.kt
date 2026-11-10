package com.byd.dashcast.phase1

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Actual child JVM FD test; no global System stream mutation in the JUnit process.
 * @author Codex
 * @date 2026/10/16
 */
class ShellCaptureOutputTest {
    @Test
    fun frameworkPrintlnCannotCorruptDcs1OrRawAvcStdout() {
        val classpath = File(javaClass.protectionDomain.codeSource!!.location.toURI()).toString() +
            File.pathSeparator +
            File(ShellCaptureOutput::class.java.protectionDomain.codeSource!!.location.toURI()) +
            // The APK carries the Kotlin runtime that app_process loads; the child JVM needs it too.
            File.pathSeparator +
            File(Unit::class.java.protectionDomain.codeSource!!.location.toURI())
        val child = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path,
            "-cp", classpath, Probe::class.java.name).start()
        try {
            assertTrue(child.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, child.exitValue())
            assertArrayEquals(CaptureFrameProtocol.end(0), child.inputStream.readAllBytes())
            assertEquals("HotFixRoot: synthetic-test\n",
                String(child.errorStream.readAllBytes(), Charsets.UTF_8).replace("\r\n", "\n"))
        } finally {
            if (child.isAlive) {
                child.destroyForcibly()
                assertTrue(child.waitFor(2, TimeUnit.SECONDS))
            }
            child.inputStream.close()
            child.errorStream.close()
            child.outputStream.close()
        }
    }

    object Probe {
        @JvmStatic
        fun main(args: Array<String>) {
            ShellCaptureOutput.isolateFrameworkDiagnostics()
            println("HotFixRoot: synthetic-test")
            val video = ShellCaptureOutput.videoStream()
            video.write(CaptureFrameProtocol.end(0))
            video.flush()
        }
    }
}
