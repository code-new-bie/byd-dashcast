package com.byd.dashcast.phase1.ncm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cleanup order and failure-isolation regressions; all resource operations are fake. */
class NcmReleaseTest {
    @Test
    fun deactivateBulkBeforeReleasingClaimsAndClosingTheConnection() {
        val steps = FakeSteps()
        assertTrue(NcmRelease.finish(steps))
        assertEquals(listOf("ALT0", "RELEASE_DATA", "RELEASE_CONTROL", "CLOSE"), steps.calls)
    }

    @Test
    fun deactivationRejectionStillReleasesBothClaimsAndCloses() {
        val steps = FakeSteps()
        steps.reject = "ALT0"
        assertFalse(NcmRelease.finish(steps))
        assertEquals(listOf("ALT0", "RELEASE_DATA", "RELEASE_CONTROL", "CLOSE"), steps.calls)
    }

    @Test
    fun releaseRejectionDoesNotSkipLaterResourcesOrPretendSuccess() {
        for (failed in arrayOf("RELEASE_DATA", "RELEASE_CONTROL")) {
            val steps = FakeSteps()
            steps.reject = failed
            assertFalse(NcmRelease.finish(steps))
            assertEquals(4, steps.calls.size)
        }
    }

    @Test
    fun exceptionsInEveryResourceStepAreIsolatedAndReported() {
        for (failed in arrayOf("ALT0", "RELEASE_DATA", "RELEASE_CONTROL", "CLOSE")) {
            val steps = FakeSteps()
            steps.fail = failed
            assertFalse(NcmRelease.finish(steps))
            assertEquals(listOf("ALT0", "RELEASE_DATA", "RELEASE_CONTROL", "CLOSE"), steps.calls)
        }
    }

    @Test
    fun missingAdapterIsRejectedBeforeAnyResourceOperation() {
        assertThrows(IllegalArgumentException::class.java) { NcmRelease.finish(null) }
    }

    private class FakeSteps : NcmRelease.Steps {
        val calls = ArrayList<String>()
        var reject: String? = null
        var fail: String? = null

        private fun step(name: String): Boolean {
            calls.add(name)
            if (name == fail) {
                throw IllegalStateException("USB unplugged during cleanup")
            }
            return name != reject
        }

        override fun deactivateData(): Boolean = step("ALT0")

        override fun releaseData(): Boolean = step("RELEASE_DATA")

        override fun releaseControl(): Boolean = step("RELEASE_CONTROL")

        override fun closeConnection() {
            step("CLOSE")
        }
    }
}
