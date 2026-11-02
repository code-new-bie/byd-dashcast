package com.byd.dashcast.phase1;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

/** Duplicate-mode policy never confuses enumeration with video acknowledgement. */
public final class UsbRoleRequestPolicyTest {
    @Test public void existingSingleTargetNcmIsKeptWithoutRequest() throws Exception {
        assertTrue(UsbRoleRequestPolicy.keepExistingNcm(InstrumentUsbCommand.NCM, 1, 1));
    }
    @Test public void hostStillRequiresPhysicalDisconnection() {
        assertThrows(IOException.class,
                () -> UsbRoleRequestPolicy.keepExistingNcm(InstrumentUsbCommand.HOST, 1, 1));
    }
    @Test public void disconnectedPeerAllowsExplicitModeRequest() throws Exception {
        assertFalse(UsbRoleRequestPolicy.keepExistingNcm(InstrumentUsbCommand.NCM, 0, 0));
        assertFalse(UsbRoleRequestPolicy.keepExistingNcm(InstrumentUsbCommand.HOST, 0, 0));
    }
    @Test public void foreignOrMultipleNcmNeverTriggersCommandOrFalseAlreadyReady() {
        assertThrows(IOException.class,
                () -> UsbRoleRequestPolicy.keepExistingNcm(InstrumentUsbCommand.NCM, 1, 0));
        assertThrows(IOException.class,
                () -> UsbRoleRequestPolicy.keepExistingNcm(InstrumentUsbCommand.NCM, 2, 1));
    }
    @Test public void invalidObservationsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> UsbRoleRequestPolicy.keepExistingNcm(InstrumentUsbCommand.NCM, 0, 1));
    }
}
