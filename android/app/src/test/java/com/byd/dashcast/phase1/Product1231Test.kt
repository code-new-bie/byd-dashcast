package com.byd.dashcast.phase1

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 1.23.1: crash hardening found by the start-up audit. */
class Product1231Test {
    private val handler = File("src/main/java/com/byd/dashcast/phase1/ProjectionService.java").readText()
        .substringAfter("public int onStartCommand").substringBefore("private void leaveWithoutSession")

    @Test fun projectionServiceEntersTheForegroundBeforeAnyCheckThatCanStopIt() {
        // Android 10 kills the app when a service started with startForegroundService() stops first.
        val beforeForeground = handler.substringBefore("startForeground(NOTIFICATION_ID, notification());")
        assertNotEquals(handler, beforeForeground)
        for (check in listOf("finishing", "START_IGNORED", "PRODUCT_START_CANCELLED", "START_BLOCKED",
                "UsbOperationGate.acquire", "stopSelf")) {
            assertFalse(check, beforeForeground.contains(check))
        }
        // Only the STOP intent (sent with startService) skips it, and it is the only bare stopSelf before the
        // session cleanup paths; every other early exit removes the notification too.
        assertTrue(beforeForeground.contains("if (!stopRequest)"))
        assertEquals(1, Regex("startForeground\\(NOTIFICATION_ID").findAll(handler).count())
        val earlyExits = handler.substringBefore("try {")
        assertEquals(1, Regex("\\bstopSelf\\(\\)").findAll(earlyExits).count())
        assertEquals(4, Regex("leaveWithoutSession\\(\\)").findAll(earlyExits).count())
    }

    @Test fun anAppTheCatalogCannotTargetIsLeftOutInsteadOfFailingTheList() {
        val ok = AppEntryCapability(true, false, "TEST", "")
        assertNull(ProductAppCatalog.entry("com.example.app/com.example.应用", { "应用" }) { ok })
        assertNull(ProductAppCatalog.entry("com.example.app/com.example.Main", { throw RuntimeException("label") }) { ok })
        val map = ProductAppCatalog.entry("com.byd.automap/com.byd.automap.Main", { "地图" }) { ok }
        assertNotNull(map)
        assertEquals("地图", map!!.label)
        assertTrue(map.navigation)
        assertTrue(map.capability.allowed)
    }

    @Test fun usbStatusTextsSurviveAMissingUsbService() {
        // observe() throws IOException when there is no UsbManager; these feed the details text on the UI thread.
        val source = File("src/main/java/com/byd/dashcast/phase1/ProductController.kt").readText()
        for (name in listOf("fun usbSummary", "fun ncmObserved")) {
            val body = source.substringAfter(name).substringBefore("\n    fun ")
            assertTrue(name, body.contains("catch (error: Exception)"))
        }
    }

    @Test fun aboutTextNamesTheMatchingInstrumentPackage() {
        val source = File("src/main/java/com/byd/dashcast/phase1/ProductActivity.kt").readText()
        assertTrue(source.contains("配套仪表包为1.25.0"))
        assertFalse(source.contains("1.19系列"))
    }
}
