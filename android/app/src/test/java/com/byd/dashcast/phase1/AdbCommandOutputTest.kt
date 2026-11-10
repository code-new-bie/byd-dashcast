package com.byd.dashcast.phase1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Raw exec output never implies successful command execution without an explicit exit status.
 * @author Codex
 * @date 2026/10/10
 */
class AdbCommandOutputTest {
    @Test
    fun removesOnlyTheFinalZeroExitMarker() {
        assertEquals("2000\n", AdbCommandOutput.requireSuccess("2000\n\nDASHCAST_EXIT_CODE=0\n"))
    }

    @Test
    fun rejectsFailureMissingOrTruncatedStatus() {
        for (output in arrayOf("Error\nDASHCAST_EXIT_CODE=1\n", "Broadcast completed: result=0",
            "2000\nDASHCAST_EXIT_CODE=", "\nDASHCAST_EXIT_CODE=0\ntrailing text", "")) {
            assertThrows(IOException::class.java) { AdbCommandOutput.requireSuccess(output) }
        }
    }

    @Test
    fun rejectsAmErrorsEvenWhenTheShellReportsZero() {
        assertThrows(IOException::class.java) {
            AdbCommandOutput.requireSuccess("Error: Not found\nDASHCAST_EXIT_CODE=0\n")
        }
        assertThrows(IOException::class.java) {
            AdbCommandOutput.requireSuccess("Permission Denial\nDASHCAST_EXIT_CODE=0\n")
        }
    }

    @Test
    fun capturesExitAndStderrWithoutWritingFiles() {
        val command = AdbCommandOutput.wrap("id -u")
        assertTrue(command.startsWith("(id -u) 2>&1;"))
        assertTrue(command.contains("dashcast_status=\$?"))
        assertThrows(IllegalArgumentException::class.java) { AdbCommandOutput.wrap("id\nreboot") }
        assertThrows(IllegalArgumentException::class.java) { AdbCommandOutput.wrap("id\u0000") }
    }
}
