package com.byd.dashcast.phase1

import org.junit.Assert.*
import org.junit.Test

class ContentViewportTest {
    @Test fun fullExcludesOpaqueInformationCoresAndFeathersFromTheSourceLayout() {
        val p = ContentViewport.full()
        assertEquals(4.0, p.sourceWidth.toDouble() / p.sourceHeight, 0.0)
        assertEquals(4.0, p.encodedWidth.toDouble() / p.encodedHeight, 0.0)
        assertArrayEquals(floatArrayOf(.1f, 192f / 720, .8f, 384f / 720), p.normalized(), .00001f)
        assertEquals(528, p.encodedTop+p.encodedHeight)
    }
    @Test fun paddingPassHasBoundedCostAndNeverSamplesSharpContentOutsideInnerBounds() {
        assertEquals(512, ContentViewport.PADDING_WIDTH * ContentViewport.PADDING_HEIGHT)
        assertFalse(ContentViewport.FRAGMENT.contains("mix(sharp"))
        assertTrue(ContentViewport.FRAGMENT.contains("texture2D(padding"))
        assertTrue(ContentViewport.PADDING_FRAGMENT.contains("sum/64.0"))
    }
    @Test fun centerInnerAspectIncludesBothFeatherMarginsAndWindowResampling() {
        val p = ContentViewport.center()
        assertEquals(15.0 / 8, p.sourceWidth.toDouble() / p.sourceHeight, 0.0)
        assertEquals(720.0, p.encodedWidth * .4, 0.0)
        assertEquals(384.0, p.encodedHeight * .6, 0.0)
        assertEquals(15.0 / 8, p.encodedWidth * .4 / (p.encodedHeight * .6), 0.0)
    }
    @Test fun fixedTokensRoundTripAcrossTheControlledAdbBoundary() {
        for (center in listOf(false, true)) for (mode in listOf(CaptureOptions.Mode.FIT, CaptureOptions.Mode.CROP)) {
            val p = CaptureOptions.of(mode).forContentViewport(center)
            val decoded = CaptureOptions.fromShellArguments(arrayOf("0", "live", p.shellArguments()))
            assertEquals(mode, decoded.mode())
            assertEquals(center, decoded.centerWindow())
            assertTrue(decoded.contentViewport())
            assertEquals(p.token(), decoded.token())
        }
    }
    @Test fun legacyGeometryRemainsExplicitAndUnchanged() {
        assertFalse(CaptureOptions.parse("fit").contentViewport())
        assertFalse(CaptureOptions.parse("center-fit").contentViewport())
        assertTrue(CaptureOptions.parse("center-fit").centerWindow())
        assertEquals("stretch", CaptureOptions.fromShellArguments(arrayOf("5")).token())
    }
    @Test fun rejectsUnboundedOrAmbiguousViewportInput() {
        for (token in listOf("viewport-full-stretch", "viewport-center-fit;id", "viewport-fit", "viewport-full-fit ")) {
            assertThrows(IllegalArgumentException::class.java) { CaptureOptions.parse(token) }
        }
        assertThrows(IllegalArgumentException::class.java) { CaptureOptions.of(CaptureOptions.Mode.STRETCH).forContentViewport(true) }
        assertThrows(IllegalArgumentException::class.java) { ContentViewport(1920, 480, 1900, 0, 1728, 432) }
    }
}
