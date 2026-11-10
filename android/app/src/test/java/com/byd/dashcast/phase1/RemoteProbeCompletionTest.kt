package com.byd.dashcast.phase1

import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class RemoteProbeCompletionTest {
    @Test
    fun completedProbeRequiresPicturesAndOwnedExit() {
        RemoteProbeCompletion.require(true, false, 2, null)
        assertThrows(IOException::class.java) { RemoteProbeCompletion.require(true, false, 1, null) }
    }

    @Test
    fun stopBeforeFirstPictureStillRequiresExitProof() {
        RemoteProbeCompletion.require(true, true, 0, null)
        assertThrows(ProjectionSessionController.CleanupUnconfirmedException::class.java) {
            RemoteProbeCompletion.require(false, true, 0, null)
        }
    }

    @Test
    fun missingExitQuarantinesInsteadOfMaskingCaptureFailure() {
        val failure = IOException("capture failed")
        val result: IOException = assertThrows(ProjectionSessionController.CleanupUnconfirmedException::class.java) {
            RemoteProbeCompletion.require(false, false, 20, failure)
        }
        assertSame(failure, result.cause)
    }

    @Test
    fun confirmedCleanupDoesNotTurnCaptureFailureIntoPass() {
        val failure = IOException("capture failed")
        assertSame(failure, assertThrows(IOException::class.java) {
            RemoteProbeCompletion.require(true, false, 20, failure)
        })
    }
}
