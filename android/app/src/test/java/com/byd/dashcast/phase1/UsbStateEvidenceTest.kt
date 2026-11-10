package com.byd.dashcast.phase1

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Isolated Android USB flag allowlist checks, with no Android device or privileged API. */
class UsbStateEvidenceTest {
    @Test
    fun retainsOnlyFixedUsbFlagsNotSerialsOrOtherApps() {
        val evidence = UsbStateEvidence()
        feed(evidence, "USB device serial=private-serial\nPackage=my.private.application\n" +
            "  current_functions=adb\n  host_connected: false\n" +
            " mCurrentFunctionsApplied=true\n configured=true\ncurrent_functions=adb; private-key=hidden\n")
        val result = evidence.snapshot()
        assertTrue(result.contains("current_functions=adb"))
        assertTrue(result.contains("host_connected=false"))
        assertTrue(result.contains("mCurrentFunctionsApplied=true"))
        assertTrue(result.contains("NOT QNX USB role"))
        assertFalse(result.contains("private-serial"))
        assertFalse(result.contains("private.application"))
        assertFalse(result.contains("hidden"))
    }

    @Test
    fun emptyMalformedAndOversizedLinesMeanUnknown() {
        val evidence = UsbStateEvidence()
        feed(evidence, "configured=" + "x".repeat(8192) + "\nError: permission denied\n")
        assertTrue(evidence.snapshot().contains("UNKNOWN"))
        assertThrows(IllegalArgumentException::class.java) { evidence.onOutput(ByteArray(1), -1) }
    }

    @Test
    fun arbitraryChunkingAndEofRetainBoundedOutput() {
        val evidence = UsbStateEvidence()
        val bytes = "connected=true".toByteArray(Charsets.UTF_8)
        for (value in bytes) {
            evidence.onOutput(byteArrayOf(value), 1)
        }
        assertTrue(evidence.snapshot().contains("connected=true"))
        for (index in 0 until 1000) {
            feed(evidence, "host_connected=false\n")
        }
        feed(evidence, "DASHCAST_EXIT_CODE=0\n")
        assertTrue(evidence.snapshot().contains("dumpsys_command_success=true"))
        assertTrue(evidence.snapshot().length < 4096)
    }

    private fun feed(evidence: UsbStateEvidence, text: String) {
        val data = text.toByteArray(Charsets.UTF_8)
        evidence.onOutput(data, data.size)
    }
}
