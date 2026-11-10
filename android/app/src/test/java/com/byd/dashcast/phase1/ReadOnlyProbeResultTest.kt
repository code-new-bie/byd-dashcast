package com.byd.dashcast.phase1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/** Unsupported or truncated capability commands remain UNKNOWN, not a negative capability claim. */
class ReadOnlyProbeResultTest {
    @Test
    fun preservesOnlyExplicitlySuccessfulNonEmptyOutput() {
        assertEquals("CANFD\n", ReadOnlyProbeResult.requireOutput("CANFD\n\nDASHCAST_EXIT_CODE=0\n", false))
    }

    @Test
    fun missingDumpsysServiceCannotLookLikeAHealthyEmptyCodecList() {
        assertThrows(IOException::class.java) {
            ReadOnlyProbeResult.requireOutput("Can't find service: media.codec\n\nDASHCAST_EXIT_CODE=0\n", false)
        }
    }

    @Test
    fun unsupportedIpOptionIsNotHiddenByKeywordFiltering() {
        assertThrows(IOException::class.java) {
            ReadOnlyProbeResult.requireOutput("ip: Unknown option details\n\nDASHCAST_EXIT_CODE=1\n", false)
        }
    }

    @Test
    fun eofWithoutExitCodeAndTruncatedOutputRemainUnknown() {
        assertThrows(IOException::class.java) { ReadOnlyProbeResult.requireOutput("CANFD\n", false) }
        assertThrows(IOException::class.java) {
            ReadOnlyProbeResult.requireOutput("CANFD\n\nDASHCAST_EXIT_CODE=0\n", true)
        }
    }

    @Test
    fun emptySuccessIsNotAUsefulReadOnlySnapshot() {
        assertThrows(IOException::class.java) { ReadOnlyProbeResult.requireOutput("\nDASHCAST_EXIT_CODE=0\n", false) }
    }
}
