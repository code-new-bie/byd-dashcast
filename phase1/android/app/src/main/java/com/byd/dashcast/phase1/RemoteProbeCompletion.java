package com.byd.dashcast.phase1;

import java.io.IOException;

/** Exit/resource proof is mandatory even on cancellation or capture failure.
 * @author Codex
 * @date 2026/10/20
 */
final class RemoteProbeCompletion {
    private RemoteProbeCompletion() {}

    static void require(boolean exitConfirmed, boolean stopped, int pictures, IOException failure)
            throws IOException {
        if (!exitConfirmed) {
            throw new ProjectionSessionController.CleanupUnconfirmedException(
                    "REMOTE_PROBE_EXIT_OR_RESOURCES_UNCONFIRMED", failure);
        }
        if (failure != null) {
            throw failure;
        }
        if (!stopped && pictures < 2) {
            throw new IOException("REMOTE_PROBE_FEWER_THAN_TWO_PICTURES");
        }
    }
}
