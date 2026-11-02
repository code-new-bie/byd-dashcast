package com.byd.dashcast.phase1;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Raw exec output never implies successful command execution without an explicit exit status.
 * @author Codex
 * @date 2026/10/10
 */
public final class AdbCommandOutputTest {
    @Test
    public void removesOnlyTheFinalZeroExitMarker() throws Exception {
        assertEquals("2000\n", AdbCommandOutput.requireSuccess("2000\n\nDASHCAST_EXIT_CODE=0\n"));
    }

    @Test
    public void rejectsFailureMissingOrTruncatedStatus() {
        for (String output : new String[] {"Error\nDASHCAST_EXIT_CODE=1\n", "Broadcast completed: result=0",
                "2000\nDASHCAST_EXIT_CODE=", "\nDASHCAST_EXIT_CODE=0\ntrailing text", ""}) {
            assertThrows(IOException.class, () -> AdbCommandOutput.requireSuccess(output));
        }
    }

    @Test
    public void rejectsAmErrorsEvenWhenTheShellReportsZero() {
        assertThrows(IOException.class,
                () -> AdbCommandOutput.requireSuccess("Error: Not found\nDASHCAST_EXIT_CODE=0\n"));
        assertThrows(IOException.class,
                () -> AdbCommandOutput.requireSuccess("Permission Denial\nDASHCAST_EXIT_CODE=0\n"));
    }

    @Test
    public void capturesExitAndStderrWithoutWritingFiles() {
        String command = AdbCommandOutput.wrap("id -u");
        assertTrue(command.startsWith("(id -u) 2>&1;"));
        assertTrue(command.contains("dashcast_status=$?"));
        assertThrows(IllegalArgumentException.class, () -> AdbCommandOutput.wrap("id\nreboot"));
        assertThrows(IllegalArgumentException.class, () -> AdbCommandOutput.wrap("id\0"));
    }
}
