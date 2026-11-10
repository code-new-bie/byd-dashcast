package com.byd.dashcast.phase1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionLimitsAndJournalTest {
    @Test
    fun durationHasFiniteTwoHourBoundary() {
        assertEquals(33_333L, ProjectionLimits.TARGET_FRAME_INTERVAL_US)
        ProjectionLimits.requireSeconds(1)
        ProjectionLimits.requireSeconds(600)
        ProjectionLimits.requireSeconds(1800)
        ProjectionLimits.requireSeconds(7200)
        for (seconds in intArrayOf(-1, 0, 7201, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { ProjectionLimits.requireSeconds(seconds) }
        }
    }

    @Test
    fun journalRetainsSessionAcrossUiReadersAndBoundsMemory() {
        val journal = ProjectionJournal()
        journal.state(ProjectionSessionController.State.STREAMING, "first ACK, not actual picture")
        for (index in 0 until 1000) {
            journal.append("stats " + index + "x".repeat(1000))
        }
        val snapshot = journal.snapshot()
        assertTrue(snapshot.length < 132 * 1024)
        assertTrue(snapshot.contains("stats 999"))
        assertFalse(snapshot.contains("stats 0"))
        assertTrue(snapshot.startsWith("CONTINUOUS_SESSION STREAMING"))
        journal.state(ProjectionSessionController.State.QUARANTINED, "STOP unconfirmed")
        assertTrue(journal.quarantined())
        assertTrue(journal.snapshot().contains("STOP unconfirmed"))
    }

    @Test
    fun onlyCurrentLeaseCanReleaseGateAndOldDoubleCloseCannotReleaseNewOwner() {
        val first = UsbOperationGate.acquire()
        assertNotNull(first)
        try {
            assertNull(UsbOperationGate.acquire())
        } finally {
            first!!.close()
        }
        val second = UsbOperationGate.acquire()
        assertNotNull(second)
        try {
            first.close()
            assertNull(UsbOperationGate.acquire())
        } finally {
            second!!.close()
        }
    }
}
