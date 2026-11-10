package com.byd.dashcast.phase1

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 1.23.0: the switch selects the instrument mode, the link follows it, and a dot shows the link. */
class Product1230Test {
    private val connected = ProductConnection.CONNECTED
    private val absent = ProductConnection.ABSENT

    @Test fun dotFollowsTheModeAndTheLink() {
        assertEquals(LinkDot.OFF, LinkDot.of(false, connected, false, false, false))
        assertEquals(LinkDot.OFF, LinkDot.of(false, absent, false, false, true))
        assertEquals(LinkDot.CONNECTING, LinkDot.of(true, absent, true, false, false))
        assertEquals(LinkDot.READY, LinkDot.of(true, connected, false, false, false))
        assertEquals(LinkDot.CASTING, LinkDot.of(true, connected, false, true, false))
        assertEquals(LinkDot.FAILED, LinkDot.of(true, absent, false, false, true))
        // Waiting for the instrument after a detach, or for USB access, is still connecting.
        assertEquals(LinkDot.CONNECTING, LinkDot.of(true, absent, false, false, false))
        assertEquals(LinkDot.CONNECTING, LinkDot.of(true, ProductConnection.PERMISSION, false, false, false))
        assertEquals(LinkDot.FAILED, LinkDot.of(true, ProductConnection.AMBIGUOUS, false, false, false))
    }

    @Test fun anUnansweredRequestKeepsProjectionModeInsteadOfSendingHost() {
        val source = File("src/main/java/com/byd/dashcast/phase1/ProductController.kt").readText()
        assertFalse(source.contains("restoreHostAfterTimeout"))
        val failed = source.substringAfter("private fun linkFailed").substringBefore("private fun linkReady")
        assertFalse(failed.contains("instrumentConnection"))
        assertFalse(failed.contains("HOST"))
        // Only the user's U-disk choice sends Host.
        val mode = source.substringAfter("fun setProjectionMode").substringBefore("fun retryLink")
        assertTrue(mode.contains("switchRole(InstrumentUsbCommand.HOST)"))
    }

    @Test fun switchShowsTheSavedModeAndTheHeaderShowsTheDot() {
        val source = File("src/main/java/com/byd/dashcast/phase1/ProductActivity.kt").readText()
        assertTrue(source.contains("roleSwitch?.isChecked = settings.instrumentConnection"))
        assertTrue(source.contains("control.setProjectionMode(on)"))
        assertTrue(source.contains("paintLinkDot(linkState(usb))"))
    }
}
