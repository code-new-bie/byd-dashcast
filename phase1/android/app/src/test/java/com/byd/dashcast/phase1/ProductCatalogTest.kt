package com.byd.dashcast.phase1

import org.junit.Assert.*
import org.junit.Test

class ProductCatalogTest {
    @Test fun knownNavigationIsAdviceNotMainScreenFallback() {
        val nav = ProductApp("com.autonavi.amapautoclone/.Main", "高德分身", true)
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
        assertTrue(ProductErrors.explain(java.io.IOException("CLEANUP_UNCONFIRMED")).contains("停止重试"))
    }
}
