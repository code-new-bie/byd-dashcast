package com.byd.dashcast.phase1;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/** Unsupported or truncated capability commands remain UNKNOWN, not a negative capability claim. */
public final class ReadOnlyProbeResultTest {
    @Test
    public void preservesOnlyExplicitlySuccessfulNonEmptyOutput() throws Exception {
        assertEquals("CANFD\n", ReadOnlyProbeResult.requireOutput("CANFD\n\nDASHCAST_EXIT_CODE=0\n", false));
    }

    @Test
    public void missingDumpsysServiceCannotLookLikeAHealthyEmptyCodecList() {
        assertThrows(IOException.class, () -> ReadOnlyProbeResult.requireOutput(
                "Can't find service: media.codec\n\nDASHCAST_EXIT_CODE=0\n", false));
    }

    @Test
    public void unsupportedIpOptionIsNotHiddenByKeywordFiltering() {
        assertThrows(IOException.class, () -> ReadOnlyProbeResult.requireOutput(
                "ip: Unknown option details\n\nDASHCAST_EXIT_CODE=1\n", false));
    }

    @Test
    public void eofWithoutExitCodeAndTruncatedOutputRemainUnknown() {
        assertThrows(IOException.class, () -> ReadOnlyProbeResult.requireOutput("CANFD\n", false));
        assertThrows(IOException.class, () -> ReadOnlyProbeResult.requireOutput(
                "CANFD\n\nDASHCAST_EXIT_CODE=0\n", true));
    }

    @Test
    public void emptySuccessIsNotAUsefulReadOnlySnapshot() {
        assertThrows(IOException.class, () -> ReadOnlyProbeResult.requireOutput("\nDASHCAST_EXIT_CODE=0\n", false));
    }
}
