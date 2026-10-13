package com.byd.dashcast.phase1;

import java.util.Objects;

/**
 * Separates command submission from the unplugged-peer phase and observed USB enumeration.
 * None of these Android-side states is an instrument role acknowledgement.
 *
 * @author Codex
 * @date 2026/10/10
 */
final class UsbSwitchEvidence {
    enum State {
        CANCELLED_UNCONFIRMED, HOST_UNCONFIRMED, PEER_NOT_OBSERVED,
        PREEXISTING_NCM, NEW_NCM_OBSERVED
    }

    private UsbSwitchEvidence() {
    }

    static State classify(InstrumentUsbCommand command, int before, int after, boolean cancelled) {
        Objects.requireNonNull(command);
        if (before < 0 || after < 0) {
            throw new IllegalArgumentException("USB device counts cannot be negative");
        }
        if (cancelled) {
            return State.CANCELLED_UNCONFIRMED;
        }
        if (command == InstrumentUsbCommand.HOST) {
            return State.HOST_UNCONFIRMED;
        }
        if (after == 0) {
            return State.PEER_NOT_OBSERVED;
        }
        return before == 0 ? State.NEW_NCM_OBSERVED : State.PREEXISTING_NCM;
    }
}
