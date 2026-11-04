package com.byd.dashcast.phase1

import android.view.WindowManager
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 1.22.1: an unanswered connect request is detected; the IME no longer squeezes the cast page. */
class Product1221Test {
    @Test fun onlyAnUnansweredConnectRequestCountsAsAFailedLink() {
        val unknown = HotRoleTransition.Result.REQUEST_SENT_PEER_UNKNOWN
        assertTrue(HotRoleTransition.requestUnanswered(InstrumentUsbCommand.NCM, unknown, false))
        assertFalse(HotRoleTransition.requestUnanswered(InstrumentUsbCommand.NCM, unknown, true))
        assertFalse(HotRoleTransition.requestUnanswered(InstrumentUsbCommand.HOST, unknown, false))
        for (done in listOf(HotRoleTransition.Result.EXISTING_NCM, HotRoleTransition.Result.NCM_ENUMERATED)) {
            assertFalse(HotRoleTransition.requestUnanswered(InstrumentUsbCommand.NCM, done, false))
        }
    }

    @Test fun castPageKeepsItsLayoutWhenTheHostedAppOpensTheKeyboard() {
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING, CastPageKeyboard.mode(0))
        for (page in 1..2) {
            assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE, CastPageKeyboard.mode(page))
        }
        // build() is the only place that sets the mode, and it uses this rule.
        val source = File("src/main/java/com/byd/dashcast/phase1/ProductActivity.kt").readText()
        assertEquals(1, Regex("setSoftInputMode\\(").findAll(source).count())
        assertTrue(source.contains("window.setSoftInputMode(CastPageKeyboard.mode(page))"))
    }
}
