package com.byd.dashcast.phase1

import java.util.Objects

/**
 * Separates command submission from the unplugged-peer phase and observed USB enumeration.
 * None of these Android-side states is an instrument role acknowledgement.
 *
 * @author Codex
 * @date 2026/10/10
 */
internal object UsbSwitchEvidence {
    enum class State {
        CANCELLED_UNCONFIRMED, HOST_UNCONFIRMED, PEER_NOT_OBSERVED,
        PREEXISTING_NCM, NEW_NCM_OBSERVED
    }

    @JvmStatic
    fun classify(command: InstrumentUsbCommand?, before: Int, after: Int, cancelled: Boolean): State {
        Objects.requireNonNull(command)
        if (before < 0 || after < 0) {
            throw IllegalArgumentException("USB device counts cannot be negative")
        }
        return when {
            cancelled -> State.CANCELLED_UNCONFIRMED
            command == InstrumentUsbCommand.HOST -> State.HOST_UNCONFIRMED
            after == 0 -> State.PEER_NOT_OBSERVED
            before == 0 -> State.NEW_NCM_OBSERVED
            else -> State.PREEXISTING_NCM
        }
    }
}
