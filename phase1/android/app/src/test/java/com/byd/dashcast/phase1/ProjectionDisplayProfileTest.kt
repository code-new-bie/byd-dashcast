package com.byd.dashcast.phase1

import org.junit.Assert.*
import org.junit.Test

class ProjectionDisplayProfileTest {
    @Test fun centerDensityCompensatesForPhysicalResamplingWithoutCropping() {
        val density = ProjectionDisplayProfile.densityDpi(1200, 640)
        assertEquals(240, density)
        assertEquals(14.4, 16.0*density/160*.6, .0001)
        assertTrue(16.0*density/160*.6 > 16.0*.6)
    }

    @Test fun fullUsesTheEffectiveBoundsAtOneToOneScale() {
        val full = ContentViewport.full()
        assertEquals(1696, full.sourceWidth)
        assertEquals(440, full.sourceHeight)
        assertEquals(160, ProjectionDisplayProfile.densityDpi(full.sourceWidth, full.sourceHeight))
        assertEquals(full.sourceWidth, full.encodedWidth)
        assertEquals(full.sourceHeight, full.encodedHeight)
    }

    @Test fun legacyProbeDensitiesAreNotChanged() {
        for ((width, height) in listOf(1920 to 720, 1280 to 720, 1920 to 480)) {
            assertEquals(160, ProjectionDisplayProfile.densityDpi(width, height))
        }
    }

    @Test fun noUnboundedFallbackOrGlobalDensity() {
        for ((width, height) in listOf(0 to 0, 1920 to 1080, 1728 to 432, 1200 to 720)) {
            assertThrows(IllegalArgumentException::class.java) { ProjectionDisplayProfile.densityDpi(width, height) }
        }
    }

    @Test fun nativeGeometryCapabilityIsDifferentFromFrozenRwv3() {
        assertEquals(0x5257563d, ProductCompositionSession.CAPABILITY)
        assertNotEquals(0x5257563c, ProductCompositionSession.CAPABILITY)
        assertNotEquals(0x5257563b, ProductCompositionSession.CAPABILITY)
        assertNotEquals(0x5257563a, ProductCompositionSession.CAPABILITY)
        assertNotEquals(0x52575639, ProductCompositionSession.CAPABILITY)
        assertNotEquals(0x52575638, ProductCompositionSession.CAPABILITY)
        assertNotEquals(0x52575637, ProductCompositionSession.CAPABILITY)
        assertNotEquals(0x52575636, ProductCompositionSession.CAPABILITY)
        assertNotEquals(0x52575635, ProductCompositionSession.CAPABILITY)
        assertNotEquals(0x52575634, ProductCompositionSession.CAPABILITY)
        assertNotEquals(0x52575633, ProductCompositionSession.CAPABILITY)
    }
}
