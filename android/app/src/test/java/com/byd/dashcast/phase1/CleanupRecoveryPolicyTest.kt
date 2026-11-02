package com.byd.dashcast.phase1

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class CleanupRecoveryPolicyTest {
    @Test fun allLocalProofCombinationsRemainBlockedUnlessComplete() {
        for (capture in listOf(false, true)) for (usb in listOf(false, true))
            for (partial in listOf(false, true)) for (peer in listOf(false, true)) {
                val expected = when {
                    !capture || !usb || partial -> CleanupRecoveryPolicy.Outcome.LOCAL_UNCONFIRMED
                    !peer -> CleanupRecoveryPolicy.Outcome.PEER_RECOVERY_REQUIRED
                    else -> CleanupRecoveryPolicy.Outcome.COMPLETE
                }
                assertEquals(expected, CleanupRecoveryPolicy.classify(capture, usb, partial, peer))
            }
    }

    @Test fun missingRestoreOrStopReplyIsRecoverableOnlyWithPositiveLocalEvidence() {
        assertEquals(CleanupRecoveryPolicy.Outcome.PEER_RECOVERY_REQUIRED,
            CleanupRecoveryPolicy.classify(true, true, false, false))
        assertEquals(CleanupRecoveryPolicy.Outcome.LOCAL_UNCONFIRMED,
            CleanupRecoveryPolicy.classify(false, true, false, false))
        assertEquals(CleanupRecoveryPolicy.Outcome.LOCAL_UNCONFIRMED,
            CleanupRecoveryPolicy.classify(true, false, false, true))
    }

    @Test fun peerPendingEndsWorkerButDoesNotAuthorizeAnotherStartOnThatOwner() {
        val states = mutableListOf<String>()
        val owner = ProjectionSessionController(Runnable::run) { state, _ -> states += state.name }
        assertTrue(owner.start { _, _, _, cleaning ->
            cleaning.run()
            throw ProjectionSessionController.PeerCleanupPendingException("local resources closed", IOException("STOP reply lost"))
        })
        assertEquals(listOf("STARTING", "STOPPING", "RECOVERY_REQUIRED"), states)
        assertFalse(owner.active())
        assertFalse(owner.start { _, _, _, _ -> fail("No new session before peer proof") })
    }

    @Test fun trulyUnknownLocalCleanupAndUnexpectedFailureRemainHardQuarantined() {
        val owner = ProjectionSessionController(Runnable::run) { _, _ -> }
        assertTrue(owner.start { _, _, _, _ ->
            throw ProjectionSessionController.CleanupUnconfirmedException("capture EXIT missing", null)
        })
        assertEquals(ProjectionSessionController.State.QUARANTINED, owner.state())
        assertFalse(owner.start { _, _, _, _ -> fail("Unsafe retry") })
    }

    @Test fun boundedReasonsKeepSuppressedAndNestedCausesWithoutAuthorizingByText() {
        val first = IOException("primary STOP error", IOException("PRODUCT_RESTORE_UNCONFIRMED"))
        first.addSuppressed(IOException("USB close error"))
        val detail = CleanupRecoveryPolicy.describe(first)
        assertTrue(detail.contains("primary STOP error"))
        assertTrue(detail.contains("PRODUCT_RESTORE_UNCONFIRMED"))
        assertTrue(detail.contains("USB close error"))
        assertEquals(CleanupRecoveryPolicy.Outcome.LOCAL_UNCONFIRMED,
            CleanupRecoveryPolicy.classify(false, true, false, true))
    }

    @Test fun cyclicOrHugeThrowableTreesDoNotBreakCleanupReporting() {
        val a = IOException("a".repeat(10_000))
        val b = IOException("b")
        a.initCause(b); b.initCause(a)
        repeat(30) { a.addSuppressed(IOException("suppressed$it")) }
        assertTrue(CleanupRecoveryPolicy.describe(a).length <= 1800)
        assertEquals("", CleanupRecoveryPolicy.describe(null))
    }

    @Test fun historicalIncidentIsBoundedAndCarriesItsOwnVersionInsteadOfInventingCurrentState() {
        val record = RecoveryIncidentStore.format(ProjectionSessionController.State.RECOVERY_REQUIRED,
            "cause".repeat(2000), "1.7.2-debug", 12345)
        assertTrue(record.startsWith("LAST_CLEANUP_INCIDENT version=1.7.2-debug at=12345"))
        assertTrue(record.contains("state=RECOVERY_REQUIRED"))
        assertTrue(record.length < 2400)
        assertFalse(record.contains("PROJECTION_STATE=STOPPED"))
    }
}
