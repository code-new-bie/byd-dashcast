package com.byd.dashcast.phase1

import org.junit.Assert.*
import org.junit.Test

class StandbyMode1202Test {
    @Test fun standbyDisplayFollowsTheSelectedInstrumentMode() {
        assertEquals(1912 to 498, ExternalDisplayWire.width(false) to ExternalDisplayWire.height(false))
        assertEquals(1870 to 810, ExternalDisplayWire.width(true) to ExternalDisplayWire.height(true))
        // Same sizes the native map path uses, so both entries lay the map out identically per mode.
        assertEquals(ContentViewport.full().sourceWidth to ContentViewport.full().sourceHeight,
            ExternalDisplayWire.width(false) to ExternalDisplayWire.height(false))
        assertEquals(ContentViewport.center().sourceWidth to ContentViewport.center().sourceHeight,
            ExternalDisplayWire.width(true) to ExternalDisplayWire.height(true))
        assertEquals(ExternalDisplayWire.DENSITY, ProjectionDisplayProfile.densityDpi(1870, 810))
        assertEquals(ExternalDisplayWire.DENSITY, ProjectionDisplayProfile.densityDpi(1912, 498))
    }
}
