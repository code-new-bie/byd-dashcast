package com.byd.dashcast.phase1

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Duplicate-mode policy never confuses enumeration with video acknowledgement. */
class UsbRoleRequestPolicyTest {
    @Test fun existingSingleTargetNcmIsKeptWithoutRequest() {
        assertTrue(UsbRoleRequestPolicy.keepExistingNcm(InstrumentUsbCommand.NCM, 1, 1))
    }
    @Test fun hostStillRequiresPhysicalDisconnection() {
        assertThrows(IOException::class.java) {
            UsbRoleRequestPolicy.keepExistingNcm(InstrumentUsbCommand.HOST, 1, 1)
        }
    }
    @Test fun disconnectedPeerAllowsExplicitModeRequest() {
        assertFalse(UsbRoleRequestPolicy.keepExistingNcm(InstrumentUsbCommand.NCM, 0, 0))
        assertFalse(UsbRoleRequestPolicy.keepExistingNcm(InstrumentUsbCommand.HOST, 0, 0))
    }
    @Test fun foreignOrMultipleNcmNeverTriggersCommandOrFalseAlreadyReady() {
        assertThrows(IOException::class.java) {
            UsbRoleRequestPolicy.keepExistingNcm(InstrumentUsbCommand.NCM, 1, 0)
        }
        assertThrows(IOException::class.java) {
            UsbRoleRequestPolicy.keepExistingNcm(InstrumentUsbCommand.NCM, 2, 1)
        }
    }
    @Test fun invalidObservationsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            UsbRoleRequestPolicy.keepExistingNcm(InstrumentUsbCommand.NCM, 0, 1)
        }
    }
}
