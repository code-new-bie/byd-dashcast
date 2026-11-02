package com.byd.dashcast.phase1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Product1210Test {
    @Test fun followSystemUsesTheSystemNightStateAndExplicitChoicesOverrideIt() {
        assertFalse(ThemePolicy.light(ProductSettings.THEME_SYSTEM, systemNight = true))
        assertTrue(ThemePolicy.light(ProductSettings.THEME_SYSTEM, systemNight = false))
        assertFalse(ThemePolicy.light(ProductSettings.THEME_DARK, systemNight = false))
        assertTrue(ThemePolicy.light(ProductSettings.THEME_LIGHT, systemNight = true))
    }

    @Test fun accessibilityListKeepsOtherServicesAndAddsOursOnce() {
        val ours = AccessibilitySetting.component("com.byd.dashcast.phase1.debug")
        assertEquals("com.byd.dashcast.phase1.debug/com.byd.dashcast.phase1.MirrorIconService", ours)
        assertEquals(ours, AccessibilitySetting.withService(null, ours))
        assertEquals(ours, AccessibilitySetting.withService("null", ours))
        assertEquals(ours, AccessibilitySetting.withService("", ours))
        assertEquals("a.b/.X:$ours", AccessibilitySetting.withService("a.b/.X", ours))
        assertEquals("a.b/.X:c.d/.Y:$ours", AccessibilitySetting.withService(" a.b/.X::c.d/.Y ", ours))
        assertNull(AccessibilitySetting.withService("a.b/.X:$ours", ours))
    }

    @Test fun shellUsbGrantOnlyAcceptsAnExactDeviceAndAnAppUid() {
        assertTrue(ShellUsbGrant.validArguments(arrayOf("/dev/bus/usb/001/007", "4660", "22136", "10123")))
        assertFalse(ShellUsbGrant.validArguments(arrayOf("/dev/bus/usb/001/007", "4660", "22136")))
        assertFalse(ShellUsbGrant.validArguments(arrayOf("/dev/bus/usb/1/7", "4660", "22136", "10123")))
        assertFalse(ShellUsbGrant.validArguments(arrayOf("/dev/bus/usb/001/007;id", "4660", "22136", "10123")))
        assertFalse(ShellUsbGrant.validArguments(arrayOf("/dev/bus/usb/001/007", "70000", "22136", "10123")))
        assertFalse(ShellUsbGrant.validArguments(arrayOf("/dev/bus/usb/001/007", "4660", "22136", "2000")))
    }
}
