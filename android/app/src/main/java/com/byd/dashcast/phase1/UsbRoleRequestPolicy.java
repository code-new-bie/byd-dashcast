package com.byd.dashcast.phase1;

import java.io.IOException;

/**
 * An enumerated target NCM is retained, never disconnected just to request NCM again.
 * Enumeration alone does not certify TCP, video, or a new instrument acknowledgement.
 * @author Codex
 * @date 2026/10/12
 */
final class UsbRoleRequestPolicy {
    private UsbRoleRequestPolicy() {
    }

    static boolean keepExistingNcm(InstrumentUsbCommand command, int allNcm, int targetNcm)
            throws IOException {
        if (allNcm < 0 || targetNcm < 0 || targetNcm > allNcm || command == null) {
            throw new IllegalArgumentException("Invalid USB role observation");
        }
        if (allNcm > 0 && command == InstrumentUsbCommand.HOST) {
            throw new IOException("NCM USB device still attached; disconnect the peer before Host");
        }
        if (allNcm > 0 && (allNcm != 1 || targetNcm != 1)) {
            throw new IOException("Ambiguous or foreign NCM device; no role request sent");
        }
        return command == InstrumentUsbCommand.NCM && targetNcm == 1;
    }
}
