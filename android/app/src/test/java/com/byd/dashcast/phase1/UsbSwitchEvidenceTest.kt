package com.byd.dashcast.phase1

import com.byd.dashcast.phase1.UsbSwitchEvidence.State.CANCELLED_UNCONFIRMED
import com.byd.dashcast.phase1.UsbSwitchEvidence.State.HOST_UNCONFIRMED
import com.byd.dashcast.phase1.UsbSwitchEvidence.State.NEW_NCM_OBSERVED
import com.byd.dashcast.phase1.UsbSwitchEvidence.State.PEER_NOT_OBSERVED
import com.byd.dashcast.phase1.UsbSwitchEvidence.State.PREEXISTING_NCM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** USB peer enumeration never substitutes for QNX receipt or backend acknowledgement. */
class UsbSwitchEvidenceTest {
    @Test
    fun unpluggedNcmRequestIsNotClassifiedAsSwitchFailure() {
        assertEquals(PEER_NOT_OBSERVED, UsbSwitchEvidence.classify(InstrumentUsbCommand.NCM, 0, 0, false))
    }

    @Test
    fun hostRequestRemainsUnknownEvenWhenNoPeerIsPresent() {
        assertEquals(HOST_UNCONFIRMED, UsbSwitchEvidence.classify(InstrumentUsbCommand.HOST, 0, 0, false))
    }

    @Test
    fun observedNcmIsNotAnAcknowledgementOfTheCurrentRequest() {
        assertEquals(NEW_NCM_OBSERVED, UsbSwitchEvidence.classify(InstrumentUsbCommand.NCM, 0, 1, false))
        assertEquals(PREEXISTING_NCM, UsbSwitchEvidence.classify(InstrumentUsbCommand.NCM, 1, 1, false))
    }

    @Test
    fun cancellationDoesNotImplyHostOrSwitchFailure() {
        assertEquals(CANCELLED_UNCONFIRMED, UsbSwitchEvidence.classify(InstrumentUsbCommand.NCM, 0, 0, true))
        assertEquals(CANCELLED_UNCONFIRMED, UsbSwitchEvidence.classify(InstrumentUsbCommand.HOST, 0, 1, true))
    }

    @Test
    fun missingInputIsRejected() {
        assertThrows(NullPointerException::class.java) { UsbSwitchEvidence.classify(null, 0, 0, false) }
        assertThrows(IllegalArgumentException::class.java) {
            UsbSwitchEvidence.classify(InstrumentUsbCommand.NCM, -1, 0, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UsbSwitchEvidence.classify(InstrumentUsbCommand.NCM, 0, -1, false)
        }
    }
}
