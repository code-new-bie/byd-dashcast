package com.byd.dashcast.phase1

import org.junit.Assert.*
import org.junit.Test

class ContentViewportTest {
    @Test fun fullSharpPictureCoversTheOpeningWithoutClippingToTheSafeBox() {
        val p = ContentViewport.full()
        assertEquals(1696.0 / 456, p.sourceWidth.toDouble() / p.sourceHeight, 0.0)
        assertEquals(p.sourceWidth.toDouble()/p.sourceHeight, p.encodedWidth.toDouble()/p.encodedHeight, 0.0)
        assertArrayEquals(floatArrayOf(112f/1920, 152f/720, 1696f/1920, 456f/720), p.normalized(), .00001f)
        assertEquals(568, p.encodedTop+p.encodedHeight)
        assertTrue(p.encodedLeft < ContentViewport.fullSafeBounds()[0])
        assertTrue(p.encodedTop < ContentViewport.fullSafeBounds()[1])
        assertTrue(p.encodedWidth > ContentViewport.fullSafeBounds()[2])
    }
    @Test fun paddingPassHasBoundedCostAndNeverSamplesSharpContentOutsideInnerBounds() {
        assertEquals(512, ContentViewport.PADDING_WIDTH * ContentViewport.PADDING_HEIGHT)
        assertFalse(ContentViewport.FRAGMENT.contains("mix(sharp"))
        assertTrue(ContentViewport.FRAGMENT.contains("texture2D(padding"))
        assertTrue(ContentViewport.PADDING_FRAGMENT.contains("sum/64.0"))
    }
    @Test fun centerKeepsSharpVideoUnderTheEntireSuppliedOverlayAndItsFeather() {
        val p = ContentViewport.center()
        assertEquals(16.0 / 9, p.sourceWidth.toDouble() / p.sourceHeight, 0.0)
        assertEquals(768.0, p.encodedWidth * .4, 0.0)
        assertEquals(432.0, p.encodedHeight * .6, 0.0)
        assertEquals(768.0 / 432, p.physicalAspect.toDouble(), .00001)
        assertArrayEquals(floatArrayOf(0f,0f,1f,1f),p.normalized(),0f)
    }
    @Test fun namedAndBusinessSourcesKeepPhysicalAspectWhenTheirLogicalSizeChanges() {
        val base=ContentViewport.center()
        val named=base.withSourceSize(1920,720)
        val business=ContentViewport.forBusiness(BusinessDisplayProfile(1920,990,240),true)
        assertEquals(base.physicalAspect,named.physicalAspect,0f)
        assertEquals(base.physicalAspect,business.physicalAspect,0f)
        assertEquals(990,business.sourceHeight)
        assertArrayEquals(base.normalized(),named.normalized(),0f)
        assertArrayEquals(base.normalized(),business.normalized(),0f)
    }
    @Test fun invalidPhysicalAspectIsRejectedBeforeRendering() {
        for (aspect in listOf(0f,-1f,Float.NaN,Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                ContentViewport(1920,1080,0,0,1920,720,aspect)
            }
        }
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
