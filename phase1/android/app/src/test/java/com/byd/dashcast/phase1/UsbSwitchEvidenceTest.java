package com.byd.dashcast.phase1;

import org.junit.Test;

import static com.byd.dashcast.phase1.UsbSwitchEvidence.State.CANCELLED_UNCONFIRMED;
import static com.byd.dashcast.phase1.UsbSwitchEvidence.State.HOST_UNCONFIRMED;
import static com.byd.dashcast.phase1.UsbSwitchEvidence.State.NEW_NCM_OBSERVED;
import static com.byd.dashcast.phase1.UsbSwitchEvidence.State.PEER_NOT_OBSERVED;
import static com.byd.dashcast.phase1.UsbSwitchEvidence.State.PREEXISTING_NCM;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/** USB peer enumeration never substitutes for QNX receipt or backend acknowledgement. */
public final class UsbSwitchEvidenceTest {
    @Test
    public void unpluggedNcmRequestIsNotClassifiedAsSwitchFailure() {
        assertEquals(PEER_NOT_OBSERVED, UsbSwitchEvidence.classify(InstrumentUsbCommand.NCM, 0, 0, false));
    }

    @Test
    public void hostRequestRemainsUnknownEvenWhenNoPeerIsPresent() {
        assertEquals(HOST_UNCONFIRMED, UsbSwitchEvidence.classify(InstrumentUsbCommand.HOST, 0, 0, false));
    }

    @Test
    public void observedNcmIsNotAnAcknowledgementOfTheCurrentRequest() {
        assertEquals(NEW_NCM_OBSERVED, UsbSwitchEvidence.classify(InstrumentUsbCommand.NCM, 0, 1, false));
        assertEquals(PREEXISTING_NCM, UsbSwitchEvidence.classify(InstrumentUsbCommand.NCM, 1, 1, false));
    }

    @Test
    public void cancellationDoesNotImplyHostOrSwitchFailure() {
        assertEquals(CANCELLED_UNCONFIRMED, UsbSwitchEvidence.classify(InstrumentUsbCommand.NCM, 0, 0, true));
        assertEquals(CANCELLED_UNCONFIRMED, UsbSwitchEvidence.classify(InstrumentUsbCommand.HOST, 0, 1, true));
    }

    @Test
    public void missingInputIsRejected() {
        assertThrows(NullPointerException.class, () -> UsbSwitchEvidence.classify(null, 0, 0, false));
        assertThrows(IllegalArgumentException.class,
                () -> UsbSwitchEvidence.classify(InstrumentUsbCommand.NCM, -1, 0, false));
        assertThrows(IllegalArgumentException.class,
                () -> UsbSwitchEvidence.classify(InstrumentUsbCommand.NCM, 0, -1, false));
    }
}
