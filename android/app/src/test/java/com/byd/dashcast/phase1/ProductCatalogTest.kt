package com.byd.dashcast.phase1

import org.junit.Assert.*
import org.junit.Test

class ProductCatalogTest {
    @Test fun knownNavigationIsAdviceNotMainScreenFallback() {
        val nav = ProductApp("com.autonavi.amapautoclone/.Main", "高德分身", true,
            AppEntryCapability(true, false, "", ""))
        val video = ProductApp("com.example.video/.Main", "视频", false)
        assertTrue(ProductAppCatalog.navigation(nav.component))
        assertFalse(ProductAppCatalog.navigation(video.component))
        assertEquals(nav, ProductAppCatalog.select(listOf(video, nav), null))
        assertEquals(video, ProductAppCatalog.select(listOf(video, nav), video.component))
        assertNull(ProductAppCatalog.select(listOf(video), null))
        assertTrue(nav.target().isRemote)
    }
    @Test fun permissionAndFirmwareMessagesExplainUserAction() {
        assertTrue(ProductErrors.explain(java.io.IOException("PRODUCT_MATCHING_FIRMWARE_REQUIRED")).contains("配套"))
        assertTrue(ProductErrors.explain(java.io.IOException("USB_PERMISSION_REQUIRED")).contains("USB"))
        assertTrue(ProductErrors.explain(java.io.IOException("CLEANUP_UNCONFIRMED")).contains("重启应用"))
        // A refused or silent DCV1 port: most often an instrument package from before the port change.
        assertTrue(ProductErrors.explain(java.io.IOException("DCV1_PORT_UNAVAILABLE port=17475: SYN timeout")).contains("配套"))
    }

    @Test fun layoutFailureNeverTellsTheUserToFlashAnotherFirmware() {
        for (code in listOf("PRODUCT_LAYOUT_NOT_READY", "PRODUCT_LAYOUT_PROFILE_MISMATCH",
            "PRODUCT_NAVIGATION_LAYOUT_UNCONFIRMED", "PRODUCT_LAYOUT_RESYNC_UNCONFIRMED")) {
            val message = ProductErrors.explain(java.io.IOException(code))
            assertTrue(message.contains("布局"))
            assertFalse(message.contains("版本不匹配"))
        }
    }

    @Test fun hmiRestartHasItsOwnRecoveryMessage() {
        val message = ProductErrors.explain(java.io.IOException("PRODUCT_HMI_CHANGED_OR_UNAVAILABLE"))
        assertTrue(message.contains("重新启动"))
        assertFalse(message.contains("版本不匹配"))
    }
}
