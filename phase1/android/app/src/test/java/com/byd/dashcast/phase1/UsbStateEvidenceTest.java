package com.byd.dashcast.phase1;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Isolated Android USB flag allowlist checks, with no Android device or privileged API. */
public final class UsbStateEvidenceTest {
    @Test
    public void retainsOnlyFixedUsbFlagsNotSerialsOrOtherApps() throws Exception {
        UsbStateEvidence evidence = new UsbStateEvidence();
        feed(evidence, "USB device serial=private-serial\nPackage=my.private.application\n"
                + "  current_functions=adb\n  host_connected: false\n"
                + " mCurrentFunctionsApplied=true\n configured=true\ncurrent_functions=adb; private-key=hidden\n");
        String result = evidence.snapshot();
        assertTrue(result.contains("current_functions=adb"));
        assertTrue(result.contains("host_connected=false"));
        assertTrue(result.contains("mCurrentFunctionsApplied=true"));
        assertTrue(result.contains("NOT QNX USB role"));
        assertFalse(result.contains("private-serial"));
        assertFalse(result.contains("private.application"));
        assertFalse(result.contains("hidden"));
    }

    @Test
    public void emptyMalformedAndOversizedLinesMeanUnknown() throws Exception {
        UsbStateEvidence evidence = new UsbStateEvidence();
        feed(evidence, "configured=" + "x".repeat(8192) + "\nError: permission denied\n");
        assertTrue(evidence.snapshot().contains("UNKNOWN"));
        assertThrows(IllegalArgumentException.class, () -> evidence.onOutput(new byte[1], -1));
    }

    @Test
    public void arbitraryChunkingAndEofRetainBoundedOutput() throws Exception {
        UsbStateEvidence evidence = new UsbStateEvidence();
        byte[] bytes = "connected=true".getBytes(StandardCharsets.UTF_8);
        for (byte value : bytes) {
            evidence.onOutput(new byte[] {value}, 1);
        }
        assertTrue(evidence.snapshot().contains("connected=true"));
        for (int index = 0; index < 1000; index++) {
            feed(evidence, "host_connected=false\n");
        }
        feed(evidence, "DASHCAST_EXIT_CODE=0\n");
        assertTrue(evidence.snapshot().contains("dumpsys_command_success=true"));
        assertTrue(evidence.snapshot().length() < 4096);
    }

    private static void feed(UsbStateEvidence evidence, String text) throws Exception {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        evidence.onOutput(data, data.length);
    }
}
