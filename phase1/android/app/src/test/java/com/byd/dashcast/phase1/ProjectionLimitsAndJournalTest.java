package com.byd.dashcast.phase1;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ProjectionLimitsAndJournalTest {
    @Test
    public void durationHasFiniteThirtyMinuteBoundary() {
        assertEquals(33_333L, ProjectionLimits.TARGET_FRAME_INTERVAL_US);
        ProjectionLimits.requireSeconds(1);
        ProjectionLimits.requireSeconds(600);
        ProjectionLimits.requireSeconds(1800);
        for (int seconds : new int[] {-1, 0, 1801, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> ProjectionLimits.requireSeconds(seconds));
        }
    }

    @Test
    public void journalRetainsSessionAcrossUiReadersAndBoundsMemory() {
        ProjectionJournal journal = new ProjectionJournal();
        journal.state(ProjectionSessionController.State.STREAMING, "first ACK, not actual picture");
        for (int index = 0; index < 1000; index++) {
            journal.append("stats " + index + "x".repeat(1000));
        }
        String snapshot = journal.snapshot();
        assertTrue(snapshot.length() < 132 * 1024);
        assertTrue(snapshot.contains("stats 999"));
        assertFalse(snapshot.contains("stats 0"));
        assertTrue(snapshot.startsWith("CONTINUOUS_SESSION STREAMING"));
        journal.state(ProjectionSessionController.State.QUARANTINED, "STOP unconfirmed");
        assertTrue(journal.quarantined());
        assertTrue(journal.snapshot().contains("STOP unconfirmed"));
    }

    @Test
    public void onlyCurrentLeaseCanReleaseGateAndOldDoubleCloseCannotReleaseNewOwner() {
        UsbOperationGate.Lease first = UsbOperationGate.acquire();
        assertNotNull(first);
        try {
            assertNull(UsbOperationGate.acquire());
        } finally {
            first.close();
        }
        UsbOperationGate.Lease second = UsbOperationGate.acquire();
        assertNotNull(second);
        try {
            first.close();
            assertNull(UsbOperationGate.acquire());
        } finally {
            second.close();
        }
    }
}
