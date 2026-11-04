package com.byd.dashcast.phase1

import org.junit.Assert.*
import org.junit.Test

class ContentViewportTest {
    @Test fun fullSharpPictureCoversTheOpeningAndItsFadeInBand() {
        val p = ContentViewport.full()
        assertEquals(1912 to 498, p.sourceWidth to p.sourceHeight)
        assertEquals(p.sourceWidth to p.sourceHeight, p.encodedWidth to p.encodedHeight)
        assertArrayEquals(floatArrayOf(4f/1920, 133f/720, 1912f/1920, 498f/720), p.normalized(), .00001f)
        // The clear opening of the 1.19.3 mask (x 134-1785, y 139-536) plus 130px / 50px of fade-in band.
        assertTrue(p.encodedLeft <= 134 - 130 && p.encodedLeft + p.encodedWidth >= 1785 + 130 + 1)
        assertTrue(p.encodedTop <= 139 - 50 && p.encodedTop + p.encodedHeight >= 536 + 50 + 1)
        // The former 1696x456 opening keeps its place inside, at the same 1:1 scale.
        assertTrue(p.encodedLeft < 112 && p.encodedTop < 112 && p.encodedLeft + p.encodedWidth > 1808)
        assertTrue(p.encodedTop + p.encodedHeight > 568)
    }
    @Test fun paddingPassHasBoundedCostAndNeverSamplesSharpContentOutsideInnerBounds() {
        assertEquals(512, ContentViewport.PADDING_WIDTH * ContentViewport.PADDING_HEIGHT)
        assertFalse(ContentViewport.FRAGMENT.contains("mix(sharp"))
        assertTrue(ContentViewport.FRAGMENT.contains("texture2D(padding"))
        assertTrue(ContentViewport.PADDING_FRAGMENT.contains("sum/64.0"))
    }
    @Test fun centerKeepsSharpVideoUnderTheOldWindowAndItsFadeInBand() {
        val p = ContentViewport.center()
        // Drawn 1:1 now, at the old 0.6 source scale: 1870x810 source -> 1122x486 pixels.
        assertEquals(1870 to 810, p.sourceWidth to p.sourceHeight)
        assertEquals(p.sourceWidth * .6, p.encodedWidth.toDouble(), 1e-9)
        assertEquals(p.sourceHeight * .6, p.encodedHeight.toDouble(), 1e-9)
        assertEquals(p.encodedWidth.toDouble() / p.encodedHeight, p.physicalAspect.toDouble(), .00001)
        // The clear opening of the 1.19.3 small mask (x 599-1320, y 151-536) plus 200px / 50px of band.
        assertTrue(p.encodedLeft <= 599 - 200 && p.encodedLeft + p.encodedWidth >= 1320 + 200 + 1)
        assertTrue(p.encodedTop <= 151 - 50 && p.encodedTop + p.encodedHeight >= 536 + 50 + 1)
        // A fitted business layout stays inside the old clear small window.
        assertArrayEquals(floatArrayOf(600f/1920, 184f/720, 720f/1920, 384f/720), p.readableCandidates().single(), .00001f)
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
