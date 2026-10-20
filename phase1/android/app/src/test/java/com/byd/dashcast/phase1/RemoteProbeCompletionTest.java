package com.byd.dashcast.phase1;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public final class RemoteProbeCompletionTest {
    @Test
    public void completedProbeRequiresPicturesAndOwnedExit() throws Exception {
        RemoteProbeCompletion.require(true, false, 2, null);
        assertThrows(IOException.class, () -> RemoteProbeCompletion.require(true, false, 1, null));
    }

    @Test
    public void stopBeforeFirstPictureStillRequiresExitProof() throws Exception {
        RemoteProbeCompletion.require(true, true, 0, null);
        assertThrows(ProjectionSessionController.CleanupUnconfirmedException.class,
                () -> RemoteProbeCompletion.require(false, true, 0, null));
    }

    @Test
    public void missingExitQuarantinesInsteadOfMaskingCaptureFailure() {
        IOException failure = new IOException("capture failed");
        IOException result = assertThrows(ProjectionSessionController.CleanupUnconfirmedException.class,
                () -> RemoteProbeCompletion.require(false, false, 20, failure));
        assertSame(failure, result.getCause());
    }

    @Test
    public void confirmedCleanupDoesNotTurnCaptureFailureIntoPass() {
        IOException failure = new IOException("capture failed");
        assertSame(failure, assertThrows(IOException.class,
                () -> RemoteProbeCompletion.require(true, false, 20, failure)));
    }
}
